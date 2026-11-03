// node site.mjs <url> <outprefix> — full-page screenshot (desktop + phone), visible text, image alts, headings
import puppeteer from 'puppeteer-core';
import fs from 'node:fs';
const [url, out] = process.argv.slice(2);
const browser = await puppeteer.launch({ executablePath: 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true, args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage();
await page.setUserAgent('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36 Edg/130.0');
for (const [tag, vp] of [['desktop', { width: 1440, height: 900, deviceScaleFactor: 1 }], ['phone', { width: 390, height: 844, deviceScaleFactor: 2, isMobile: true }]]) {
  await page.setViewport(vp);
  await page.goto(url, { waitUntil: 'networkidle2', timeout: 60000 });
  // scroll through to trigger lazy content / animations
  await page.evaluate(async () => { for (let y = 0; y < document.body.scrollHeight; y += 600) { window.scrollTo(0, y); await new Promise((r) => setTimeout(r, 150)); } window.scrollTo(0, 0); });
  await new Promise((r) => setTimeout(r, 1200));
  const h = await page.evaluate(() => document.body.scrollHeight);
  if (tag === 'desktop') {
    // slice the page into viewport-height shots so each is readable
    for (let i = 0, y = 0; y < h && i < 14; i++, y += 900) {
      await page.evaluate((yy) => window.scrollTo(0, yy), y); await new Promise((r) => setTimeout(r, 400));
      await page.screenshot({ path: `${out}-desktop-${String(i).padStart(2, '0')}.png` });
    }
  } else {
    await page.screenshot({ path: `${out}-phone-full.jpg`, fullPage: true, type: 'jpeg', quality: 70 });
  }
  if (tag === 'desktop') {
    const info = await page.evaluate(() => ({
      title: document.title,
      meta: [...document.querySelectorAll('meta[name],meta[property]')].map((m) => `${m.getAttribute('name') || m.getAttribute('property')}: ${m.content}`).filter((s) => !/viewport|theme-color/.test(s)),
      headings: [...document.querySelectorAll('h1,h2,h3,h4')].map((e) => `${e.tagName}: ${e.innerText.trim()}`).filter((s) => s.length > 4),
      alts: [...document.querySelectorAll('img[alt]')].map((i) => i.alt).filter(Boolean),
      links: [...document.querySelectorAll('a[href]')].map((a) => `${a.innerText.trim().slice(0, 40)} -> ${a.getAttribute('href')}`).filter((s) => !s.startsWith(' ->')),
      text: document.body.innerText,
      height: document.body.scrollHeight,
    }));
    fs.writeFileSync(`${out}.json`, JSON.stringify(info, null, 2));
  }
}
await browser.close();
console.log('ok');
