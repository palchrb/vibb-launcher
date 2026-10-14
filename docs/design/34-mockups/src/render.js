// Renders every src/NN-*.html to ../NN-*-after.png at 480x854 (320x569 dp at 1.5x).
// NODE_PATH=<playwright>/node_modules node render.js [chromium executable]
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');
(async () => {
  const br = await chromium.launch(process.argv[2] ? { executablePath: process.argv[2] } : {});
  const p = await br.newPage({ viewport: { width: 320, height: 569 }, deviceScaleFactor: 1.5 });
  for (const f of fs.readdirSync(__dirname).filter(f => /^\d\d[a-z]?-.*\.html$/.test(f)).sort()) {
    await p.goto('file://' + path.join(__dirname, f));
    await p.evaluate(() => document.fonts.ready.then(() => window.fit));
    const out = path.join(__dirname, '..', f.replace('.html', '-after.png'));
    await p.locator('.phone').first().screenshot({ path: out });
    console.log(out, fs.statSync(out).size);
  }
  await br.close();
})().catch(e => { console.error(e); process.exit(1); });
