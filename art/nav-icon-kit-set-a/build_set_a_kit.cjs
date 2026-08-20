const fs = require('fs');
const path = require('path');
const sharp = require('sharp');

const ROOT = __dirname;
const DRAWABLE = path.join(ROOT, 'drawable');
const SIZE = 960;

const icons = [
  {
    file: 'ic_nav_search.xml',
    label: 'Search',
    paths: [
      {
        evenOdd: true,
        data: 'M360,110 A250,250 0,1 1,360,610 A250,250 0,1 1,360,110 Z M360,210 A150,150 0,1 0,360,510 A150,150 0,1 0,360,210 Z',
      },
      {
        data: 'M520,520 L590,450 L850,710 Q880,740 850,770 L800,820 Q770,850 740,820 Z',
      },
    ],
  },
  {
    file: 'ic_nav_home.xml',
    label: 'Home',
    paths: [
      {
        data: 'M100,430 L480,100 L860,430 Q880,448 862,470 H770 V830 H585 V600 H375 V830 H190 V470 H98 Q80,448 100,430 Z',
      },
    ],
  },
  {
    file: 'ic_nav_favorites.xml',
    label: 'Favorites',
    paths: [
      {
        data: 'M480,850 L140,530 C55,445 80,265 210,180 C315,115 425,160 480,260 C535,160 645,115 750,180 C870,265 890,445 820,530 Z',
      },
    ],
  },
  {
    file: 'ic_nav_requests.xml',
    label: 'Discover / Requests',
    paths: [
      {
        evenOdd: true,
        data: 'M350,110 A240,240 0,1 1,350,590 A240,240 0,1 1,350,110 Z M350,205 A145,145 0,1 0,350,495 A145,145 0,1 0,350,205 Z',
      },
      {
        data: 'M505,505 L575,435 L835,695 Q865,725 835,755 L785,805 Q755,835 725,805 Z',
      },
      {
        data: 'M315,235 H385 V315 H465 V385 H385 V465 H315 V385 H235 V315 H315 Z',
      },
    ],
  },
  {
    file: 'ic_nav_movies.xml',
    label: 'Movies',
    paths: [
      {
        evenOdd: true,
        data: 'M120,360 H840 Q860,360 860,390 V820 Q860,840 840,840 H120 Q100,840 100,820 V390 Q100,360 120,360 Z M380,500 L380,700 L590,600 Z',
      },
      {
        data: 'M100,300 H860 V390 H100 Z M120,170 L260,140 L330,270 L190,300 Z M300,135 L440,105 L510,235 L370,265 Z M480,100 L620,80 L690,210 L550,235 Z M660,80 H810 Q850,80 860,120 V175 L730,200 Z',
      },
    ],
  },
  {
    file: 'ic_nav_tvshows.xml',
    label: 'TV Shows',
    paths: [
      {
        data: 'M420,300 L290,170 L340,120 L480,260 L620,120 L670,170 L540,300 Z',
      },
      {
        evenOdd: true,
        data: 'M160,300 H800 Q840,300 840,340 V780 Q840,820 800,820 H160 Q120,820 120,780 V340 Q120,300 160,300 Z M230,390 H730 Q760,390 760,420 V680 Q760,710 730,710 H230 Q200,710 200,680 V420 Q200,390 230,390 Z',
      },
      {
        data: 'M260,450 H440 V650 H260 Z M520,450 H700 V650 H520 Z M240,820 H390 V870 H240 Z M570,820 H720 V870 H570 Z',
      },
    ],
  },
  {
    file: 'ic_nav_standup.xml',
    label: 'Stand Up Comedy',
    paths: [
      {
        data: 'M570,120 A100,100 0,1 1,570,320 A100,100 0,1 1,570,120 Z',
      },
      {
        data: 'M500,285 L575,340 L385,610 Q370,635 345,620 L300,585 Q275,565 295,535 Z',
      },
      {
        data: 'M500,440 H555 V730 H680 V790 H375 V730 H500 Z',
      },
      {
        data: 'M160,780 A320,90 0,1 0,800,780 A320,90 0,1 0,160,780 Z',
      },
    ],
  },
  {
    file: 'ic_nav_ufc.xml',
    label: 'UFC',
    paths: [
      {
        evenOdd: true,
        data: 'M300,90 H660 L870,300 V660 L660,870 H300 L90,660 V300 Z M350,200 H610 L760,350 V610 L610,760 H350 L200,610 V350 Z',
      },
      {
        evenOdd: true,
        data: 'M270,430 V350 Q270,300 320,300 H335 Q380,300 380,345 V370 H395 V320 Q395,275 440,275 H455 Q500,275 500,320 V370 H515 V330 Q515,285 560,285 H575 Q620,285 620,330 V390 H650 Q700,390 700,440 V570 Q700,630 640,630 H610 Q580,700 480,710 H400 Q310,710 280,630 L240,530 Q220,470 270,430 Z M365,300 H395 V430 H365 Z M485,285 H515 V430 H485 Z M605,300 H635 V430 H605 Z M350,500 C430,460 550,460 640,500 L610,550 C520,520 430,520 370,560 Z',
      },
    ],
  },
  {
    file: 'ic_nav_boxing.xml',
    label: 'Boxing',
    paths: [
      {
        evenOdd: true,
        data: 'M145,320 C130,275 145,230 180,205 C175,165 205,130 245,135 C260,100 310,95 335,125 C375,110 420,140 425,180 C465,200 480,245 465,285 C480,340 450,395 400,430 L335,470 L405,545 L300,640 L225,555 L270,495 C180,480 120,405 145,320 Z M290,365 C345,355 380,315 380,255 C425,315 410,405 340,445 Z',
      },
      {
        evenOdd: true,
        data: 'M815,320 C830,275 815,230 780,205 C785,165 755,130 715,135 C700,100 650,95 625,125 C585,110 540,140 535,180 C495,200 480,245 495,285 C480,340 510,395 560,430 L625,470 L555,545 L660,640 L735,555 L690,495 C780,480 840,405 815,320 Z M670,365 C615,355 580,315 580,255 C535,315 550,405 620,445 Z',
      },
      {
        data: 'M275,555 L385,455 L620,730 L505,845 Z M685,555 L575,455 L340,730 L455,845 Z',
      },
    ],
  },
  {
    file: 'ic_nav_4k_movies.xml',
    label: '4K Movies (LAN)',
    paths: [
      {
        evenOdd: true,
        data: 'M130,280 H830 Q850,280 850,300 V640 Q850,660 830,660 H130 Q110,660 110,640 V300 Q110,280 130,280 Z M220,390 L220,560 L390,475 Z',
      },
      {
        data: 'M110,220 H850 V300 H110 Z M130,120 L255,95 L315,205 L190,230 Z M300,90 L425,70 L485,180 L360,205 Z M470,70 L595,70 L655,180 L530,180 Z M640,70 H800 Q840,70 850,110 V160 L705,185 Z',
      },
      {
        data: 'M540,350 H620 V430 H540 Z M660,350 H740 V430 H660 Z M540,470 H620 V550 H540 Z M660,470 H740 V550 H660 Z',
      },
      {
        data: 'M450,650 H510 V750 H720 V790 H750 V850 H650 V790 H510 V850 H450 V790 H310 V850 H210 V790 H240 V750 H450 Z',
      },
    ],
  },
  {
    file: 'ic_nav_4k_tv.xml',
    label: '4K TV Shows (LAN)',
    paths: [
      {
        data: 'M420,260 L300,140 L350,90 L480,220 L610,90 L660,140 L540,260 Z',
      },
      {
        evenOdd: true,
        data: 'M150,260 H810 Q850,260 850,300 V640 Q850,680 810,680 H150 Q110,680 110,640 V300 Q110,260 150,260 Z M220,350 H740 Q770,350 770,380 V560 Q770,590 740,590 H220 Q190,590 190,560 V380 Q190,350 220,350 Z',
      },
      {
        data: 'M300,395 H390 V485 H300 Z M430,395 H520 V485 H430 Z M300,510 H390 V575 H300 Z M430,510 H520 V575 H430 Z',
      },
      {
        data: 'M450,670 H510 V750 H720 V790 H750 V850 H650 V790 H510 V850 H450 V790 H310 V850 H210 V790 H240 V750 H450 Z',
      },
    ],
  },
  {
    file: 'ic_nav_settings.xml',
    label: 'Settings',
    paths: [
      {
        data: 'M765.6,517.6 C767.2,505.6 768,493.2 768,480 C768,467.2 767.2,454.4 765.2,442.4 L846.4,379.2 C853.6,373.6 855.6,362.8 851.2,354.8 L774.4,222 C769.6,213.2 759.6,210.4 750.8,213.2 L655.2,251.6 C635.2,236.4 614,223.6 590.4,214 L576,112.4 C574.4,102.8 566.4,96 556.8,96 H403.2 C393.6,96 386.8,102.8 385.2,112.4 L370,214 C346.4,223.6 324.8,236.8 305.2,251.6 L209.6,213.2 C200.8,210 190.8,213.2 186,222 L109.6,354.8 C104.8,363.2 106.4,373.6 114.4,379.2 L195.6,442.4 C193.6,454.4 192,467.6 192,480 C192,492.4 192.8,505.6 194.8,517.6 L113.6,580.8 C106.4,586.4 104.4,597.2 108.8,605.2 L185.6,738 C190.4,746.8 200.4,749.6 209.2,746.8 L304.8,708.4 C324.8,723.6 346,736.4 369.6,746 L384,847.6 C386,857.2 393.6,864 403.2,864 H556.8 C566.4,864 577.6,857.2 578.8,847.6 L593.2,746 C616.8,736.4 638.4,723.6 658,708.4 L753.6,746.8 C762.4,750 772.4,746.8 777.2,738 L854,605.2 C858.8,596.4 856.8,586.4 849.2,580.8 Z M480,624 C400.8,624 336,559.2 336,480 C336,400.8 400.8,336 480,336 C559.2,336 624,400.8 624,480 C624,559.2 559.2,624 480,624 Z',
      },
    ],
  },
];

function xmlFor(icon) {
  const paths = icon.paths
    .map((item) => {
      const fillType = item.evenOdd ? ' android:fillType="evenOdd"' : '';
      return `    <path android:fillColor="#FFFFFF"${fillType} android:pathData="${item.data}" />`;
    })
    .join('\n');

  return `<?xml version="1.0" encoding="utf-8"?>\n<vector xmlns:android="http://schemas.android.com/apk/res/android"\n    android:width="24dp"\n    android:height="24dp"\n    android:viewportWidth="960"\n    android:viewportHeight="960">\n${paths}\n</vector>\n`;
}

function svgFor(icon, width = SIZE, height = SIZE, fill = '#FFFFFF') {
  const paths = icon.paths
    .map((item) => `<path fill="${fill}"${item.evenOdd ? ' fill-rule="evenodd"' : ''} d="${item.data}"/>`)
    .join('');
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}" viewBox="0 0 960 960">${paths}</svg>`;
}

async function alphaBounds(icon) {
  const { data, info } = await sharp(Buffer.from(svgFor(icon)))
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  let left = info.width;
  let top = info.height;
  let right = -1;
  let bottom = -1;
  for (let y = 0; y < info.height; y += 1) {
    for (let x = 0; x < info.width; x += 1) {
      if (data[(y * info.width + x) * info.channels + 3] === 0) continue;
      left = Math.min(left, x);
      top = Math.min(top, y);
      right = Math.max(right, x);
      bottom = Math.max(bottom, y);
    }
  }
  return { left, top, right, bottom };
}

function escapeXml(value) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
}

async function renderPreview(output, iconSize, cellWidth, cellHeight, title, subtitle) {
  const width = cellWidth * 4;
  const height = 150 + cellHeight * 3;
  const overlays = [];
  icons.forEach((icon, index) => {
    const column = index % 4;
    const row = Math.floor(index / 4);
    const left = column * cellWidth + Math.round((cellWidth - iconSize) / 2);
    const top = 130 + row * cellHeight + 20;
    overlays.push({ input: Buffer.from(svgFor(icon, iconSize, iconSize)), left, top });
  });

  const labels = icons
    .map((icon, index) => {
      const column = index % 4;
      const row = Math.floor(index / 4);
      const x = column * cellWidth + cellWidth / 2;
      const y = 130 + row * cellHeight + 20 + iconSize + (iconSize <= 24 ? 34 : 48);
      return `<text x="${x}" y="${y}" text-anchor="middle" fill="#EAF7FF" font-family="Arial, sans-serif" font-size="${iconSize <= 24 ? 18 : 25}" font-weight="600">${escapeXml(icon.label)}</text>`;
    })
    .join('');

  const background = Buffer.from(`
    <svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">
      <rect width="100%" height="100%" fill="#0A0C12"/>
      <text x="${width / 2}" y="52" text-anchor="middle" fill="#FFFFFF" font-family="Arial, sans-serif" font-size="34" font-weight="700">${escapeXml(title)}</text>
      <text x="${width / 2}" y="88" text-anchor="middle" fill="#8DA2B8" font-family="Arial, sans-serif" font-size="19">${escapeXml(subtitle)}</text>
      ${labels}
    </svg>`);

  await sharp(background).composite(overlays).png().toFile(output);
}

async function main() {
  fs.mkdirSync(DRAWABLE, { recursive: true });
  const manifest = [];

  for (const icon of icons) {
    fs.writeFileSync(path.join(DRAWABLE, icon.file), xmlFor(icon), 'utf8');
    const bounds = await alphaBounds(icon);
    if (bounds.left < 70 || bounds.top < 70 || bounds.right > 890 || bounds.bottom > 890) {
      throw new Error(`${icon.file} exceeds the 85 percent safe-area tolerance: ${JSON.stringify(bounds)}`);
    }
    manifest.push({ file: icon.file, label: icon.label, pathCount: icon.paths.length, alphaBounds: bounds });
  }

  await renderPreview(
    path.join(ROOT, 'set-a-icon-kit-preview.png'),
    190,
    300,
    300,
    'WeaselFin TV Nav Icons - Set A',
    'Cinematic Solid - enlarged vector proof',
  );
  await renderPreview(
    path.join(ROOT, 'set-a-24dp-readability-proof.png'),
    24,
    250,
    105,
    'Set A - Actual 24 px Proof',
    'Icons rendered at 24 x 24 pixels before runtime tint',
  );

  fs.writeFileSync(
    path.join(ROOT, 'manifest.json'),
    `${JSON.stringify({ family: 'Set A - Cinematic Solid', viewport: [960, 960], sizeDp: [24, 24], fill: '#FFFFFF', icons: manifest }, null, 2)}\n`,
    'utf8',
  );
  process.stdout.write(`Built and validated ${icons.length} icons in ${DRAWABLE}\n`);
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
