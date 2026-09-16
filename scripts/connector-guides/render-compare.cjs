// Renders compare.html (the side-by-side) frame by frame into a looping video.
//   OUT_DIR=/some/dir node render-compare.cjs            -> compare.mp4 + compare.jpg (poster)
//   OUT_DIR=/some/dir FRAMES=9,12 node render-compare.cjs -> still-compare-9.png, ... (layout checks)
// The poster is a frame from the middle beat rather than the first frame: a reader who never
// plays the loop (reduced motion, autoplay refused) should still see the point it makes.
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { spawn } = require('child_process');
const path = require('path');

const FPS = Number(process.env.FPS || 30);
const POSTER_AT = Number(process.env.POSTER_AT || 12.3);
const OUT_DIR = process.env.OUT_DIR || __dirname;

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 800, height: 540 }, deviceScaleFactor: 2 });
  await page.goto('file://' + path.join(__dirname, 'compare.html') + '?capture');
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 60000 });
  const seek = t => page.evaluate(x => window.seek(x), t);

  if (process.env.FRAMES) {
    for (const s of process.env.FRAMES.split(',').map(Number)) {
      await seek(s);
      await page.screenshot({ path: path.join(OUT_DIR, `still-compare-${s}.png`) });
    }
    await browser.close();
    return;
  }

  await seek(POSTER_AT);
  await page.screenshot({ path: path.join(OUT_DIR, 'compare.jpg'), type: 'jpeg', quality: 88 });

  const duration = await page.evaluate(() => window.DURATION);
  const frames = Math.round(duration * FPS);
  const out = path.join(OUT_DIR, 'compare.mp4');
  const ff = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-pix_fmt', 'yuv420p', '-an', '-movflags', '+faststart', out],
    { stdio: ['pipe', 'inherit', 'inherit'] });
  for (let i = 0; i < frames; i++) {
    await seek(i / FPS);
    const buf = await page.screenshot({ type: 'png' });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 150 === 0) console.log(`compare frame ${i}/${frames}`);
  }
  ff.stdin.end();
  await new Promise(r => ff.on('close', r));
  await browser.close();
  console.log('wrote', out);
})();
