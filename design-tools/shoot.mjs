// node shoot.mjs <name> <w> <h> <query>
import puppeteer from 'puppeteer-core';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import fs from 'node:fs';
const [name, w, h, query] = process.argv.slice(2);
const dir = path.dirname(new URL(import.meta.url).pathname.replace(/^\/(\w:)/, '$1'));
const browser = await puppeteer.launch({
  executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
  headless: true,
  args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--hide-scrollbars'],
});
const page = await browser.newPage();
await page.setViewport({ width: +w, height: +h, deviceScaleFactor: 2 });
page.on('console', (m) => { if (m.type() === 'error') console.error('console:', m.text()); });
await page.goto(pathToFileURL(path.join(dir, 'render.html')).href + '?' + query, { waitUntil: 'load' });
await page.waitForFunction('window.__done === true', { timeout: 90000 });
await page.screenshot({ path: path.join(dir, name + '.png') });
const out = await page.evaluate('window.__out');
fs.writeFileSync(path.join(dir, name + '.json'), JSON.stringify(out));
console.log(name, JSON.stringify({ pts: out.pts, proj: out.proj, err: out.err }));
await browser.close();
