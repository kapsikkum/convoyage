// node ps1shoot.mjs <outdir> — renders every paint × wheel combo, plus a home thumbnail
import puppeteer from 'puppeteer-core';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
const dir = path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1'));
const out = process.argv[2];
fs.mkdirSync(out, { recursive: true });
const paints = ['#e9edf2', '#c8102e', '#17191c', '#9aa3ad', '#2a5bd7', '#b7e33b'];
const wheels = ['#c08a3e', '#6b7682', '#2de2e6'];
const browser = await puppeteer.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
const page = await browser.newPage();
page.on('console', (m) => { if (m.type() === 'error') console.error('console:', m.text()); });
page.on('pageerror', (e) => console.error('pageerror:', e.message));
async function shot(name, params, w, h, s) {
  await page.setViewport({ width: w * s, height: h * s, deviceScaleFactor: 1 });
  const qs = new URLSearchParams({ ...params, w, h, s }).toString();
  await page.goto(pathToFileURL(path.join(dir, 'ps1.html')).href + '?' + qs, { waitUntil: 'load' });
  await page.waitForFunction('window.__done === true', { timeout: 60000 });
  await page.screenshot({ path: path.join(out, name + '.png'), omitBackground: true, clip: { x: 0, y: 0, width: w * s, height: h * s } });
}
// hero: 358x250 css shown, rendered at 179x125 internal, saved at 4x css-pixel density (x2 per internal px * 2)
for (let p = 0; p < paints.length; p++) for (let wv = 0; wv < wheels.length; wv++) await shot(`car-${p}-${wv}`, { paint: paints[p], wheel: wheels[wv] }, 179, 125, 4);
await shot('car-thumb', { paint: paints[0], wheel: wheels[0], yaw: -20, dist: 12.5, elev: 0.25 }, 90, 40, 4);
console.log('ok');
await browser.close();
