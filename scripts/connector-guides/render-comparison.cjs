// Renders comparison.html to a 2x PNG for the updates page.
//   OUT_DIR=/some/dir node render-comparison.cjs   -> /some/dir/comparison.png
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const path = require('path');

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 800, height: 800 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(__dirname, 'comparison.html'));
  await page.evaluate(() => document.fonts.ready);
  const out = path.join(process.env.OUT_DIR || __dirname, 'comparison.png');
  await (await page.$('#card')).screenshot({ path: out });
  await browser.close();
  console.log('wrote', out);
})();
