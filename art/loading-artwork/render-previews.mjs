// Render the HTML layout with a local Chromium browser; the artwork stays unchanged.
// Start the repository HTTP server on 8765 and Chromium with --remote-debugging-port=9876.
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const directory = fileURLToPath(new URL('.', import.meta.url));
const manifest = JSON.parse(await readFile(`${directory}manifest.json`, 'utf8'));
await mkdir(`${directory}previews`, { recursive: true });
const embeddedArt = Object.fromEntries(await Promise.all(manifest.artworks.map(async artwork => [
  artwork.name,
  `data:image/webp;base64,${(await readFile(new URL(`../../${artwork.asset}`, import.meta.url))).toString('base64')}`,
])));
const gallery = (await readFile(`${directory}preview.html`, 'utf8'))
  .replace('const artworks = [', `const embeddedArt = ${JSON.stringify(embeddedArt)};\n    const artworks = [`)
  .replace('../../app/src/weaselfin/res/drawable-nodpi/weaselplex_loading_${name}.webp', '${embeddedArt[name]}');
await writeFile(`${directory}previews/gallery.html`, gallery);
console.log('Saved gallery.html with all 32 artworks embedded');
if (process.argv.includes('--gallery-only')) process.exit(0);
const tabs = await (await fetch('http://127.0.0.1:9876/json')).json();
const tab = tabs.find(tab => tab.type === 'page');
if (!tab) throw new Error('No local Chromium page is available');
const socket = new WebSocket(tab.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  socket.addEventListener('open', resolve, { once: true });
  socket.addEventListener('error', reject, { once: true });
});
let sequence = 0;
const requests = new Map();
const events = new Map();
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data);
  if (message.id) {
    const request = requests.get(message.id);
    if (!request) return;
    clearTimeout(request.timeout);
    requests.delete(message.id);
    if (message.error) request.reject(new Error(JSON.stringify(message.error)));
    else request.resolve(message.result);
  } else if (events.has(message.method)) {
    const event = events.get(message.method);
    clearTimeout(event.timeout);
    events.delete(message.method);
    event.resolve(message.params);
  }
});
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence;
    const timeout = setTimeout(() => {
      requests.delete(id);
      reject(new Error(`Timed out: ${method}`));
    }, 30000);
    requests.set(id, { resolve, reject, timeout });
    socket.send(JSON.stringify({ id, method, params }));
  });
}
function once(method) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => {
      events.delete(method);
      reject(new Error(`Timed out: ${method}`));
    }, 30000);
    events.set(method, { resolve, timeout });
  });
}
await send('Page.enable');
await send('Runtime.enable');
async function capture(filename, query, height = 1080) {
  await send('Emulation.setDeviceMetricsOverride', {
    width: 1920, height, deviceScaleFactor: 1, mobile: false,
  });
  const loaded = once('Page.loadEventFired');
  const navigation = await send('Page.navigate', {
    url: `http://127.0.0.1:8765/art/loading-artwork/preview.html?${query}`,
  });
  if (navigation.errorText) throw new Error(navigation.errorText);
  await loaded;
  const rendered = await send('Runtime.evaluate', {
    expression: `(async () => {
      await window.artworkPreviewReady;
      await new Promise(requestAnimationFrame);
      await new Promise(requestAnimationFrame);
      return {
        missingImages: [...document.images].filter(image => !image.naturalWidth).length,
        overflow: document.documentElement.scrollHeight > innerHeight,
        overlayCount: [...document.querySelectorAll('.stage')].reduce((total, stage) => total + stage.children.length - 1, 0)
      };
    })()`,
    awaitPromise: true, returnByValue: true,
  });
  const status = rendered.result.value;
  if (!status || status.missingImages || status.overflow || status.overlayCount) {
    throw new Error(`Incomplete preview ${filename}: ${JSON.stringify(status)}`);
  }
  const screenshot = await send('Page.captureScreenshot', {
    format: 'png', captureBeyondViewport: false, fromSurface: true,
  });
  await writeFile(`${directory}previews/${filename}.png`, Buffer.from(screenshot.data, 'base64'));
  console.log(`Saved ${filename}.png`);
}
try {
  for (const artwork of manifest.artworks) {
    await capture(`${artwork.name}-artwork`, `art=${artwork.name}&capture=1`);
  }
  await capture('original-six-artwork', 'sheet=original', 1732);
  await capture('new-six-artwork', 'sheet=new', 1732);
} finally {
  socket.close();
}
