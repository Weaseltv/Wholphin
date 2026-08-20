const fs = require('fs');
const path = require('path');

const drawable = path.join(__dirname, 'drawable');
const expected = [
  'ic_nav_search.xml',
  'ic_nav_home.xml',
  'ic_nav_favorites.xml',
  'ic_nav_requests.xml',
  'ic_nav_movies.xml',
  'ic_nav_tvshows.xml',
  'ic_nav_standup.xml',
  'ic_nav_ufc.xml',
  'ic_nav_boxing.xml',
  'ic_nav_4k_movies.xml',
  'ic_nav_4k_tv.xml',
  'ic_nav_settings.xml',
];

const actual = fs.readdirSync(drawable).filter((file) => file.endsWith('.xml')).sort();
const wanted = [...expected].sort();
if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
  throw new Error(`Drawable inventory mismatch. Expected ${wanted.join(', ')}, found ${actual.join(', ')}`);
}

const forbidden = [
  /<group\b/i,
  /<clip-path\b/i,
  /<gradient\b/i,
  /android:stroke/i,
  /android:alpha/i,
  /android:fillAlpha/i,
  /android:strokeAlpha/i,
  /android:tint/i,
  /android:rotation/i,
  /android:translate/i,
  /android:scale/i,
];

for (const file of expected) {
  const xml = fs.readFileSync(path.join(drawable, file), 'utf8');
  const required = [
    'android:width="24dp"',
    'android:height="24dp"',
    'android:viewportWidth="960"',
    'android:viewportHeight="960"',
  ];
  for (const token of required) {
    if (!xml.includes(token)) throw new Error(`${file} is missing ${token}`);
  }
  for (const pattern of forbidden) {
    if (pattern.test(xml)) throw new Error(`${file} contains forbidden markup matching ${pattern}`);
  }

  const tags = [...xml.matchAll(/<([a-zA-Z-]+)\b/g)].map((match) => match[1]);
  if (tags.some((tag) => tag !== 'vector' && tag !== 'path')) {
    throw new Error(`${file} contains a tag other than vector/path: ${tags.join(', ')}`);
  }

  const paths = [...xml.matchAll(/<path\b[^>]*\/>/g)].map((match) => match[0]);
  if (paths.length === 0) throw new Error(`${file} has no filled path`);
  for (const pathTag of paths) {
    if (!pathTag.includes('android:fillColor="#FFFFFF"')) {
      throw new Error(`${file} has a path without opaque #FFFFFF fill`);
    }
    if (!pathTag.includes('android:pathData="')) {
      throw new Error(`${file} has a path without pathData`);
    }
  }
}

process.stdout.write(`Validated ${expected.length} Android vector drawables with the Set A production contract.\n`);
