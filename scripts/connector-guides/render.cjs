// Renders one connector walkthrough (index.html?app=...) frame by frame into a looping video.
//   APP=claude node render.cjs              -> claude-connector.mp4 + claude-connector.jpg (poster)
//   APP=chatgpt node render.cjs             -> chatgpt-connector.mp4 + chatgpt-connector.jpg
//   APP=claude FRAMES=5,12.5 node render.cjs -> still-claude-5.png, ... (layout checks)
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { spawn } = require('child_process');
const path = require('path');

const APP = process.env.APP === 'chatgpt' ? 'chatgpt' : 'claude';
const FPS = Number(process.env.FPS || 30);
const SCALE = 1.6; // 1000x680 logical -> 1600x1088 video
const out = ext => path.join(__dirname, `${APP}-connector.${ext}`);

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1000, height: 680 }, deviceScaleFactor: SCALE });
  await page.goto('file://' + path.join(__dirname, 'index.html') + `?capture&app=${APP}`);
  await page.waitForFunction(() => window.__ready === true, null, { timeout: 60000 });
  const seek = t => page.evaluate(x => window.seek(x), t);

  if (process.env.FRAMES) {
    for (const s of process.env.FRAMES.split(',').map(Number)) {
      await seek(s);
      await page.screenshot({ path: path.join(__dirname, `still-${APP}-${s}.png`) });
    }
    await browser.close();
    return;
  }

  // The poster is the first frame, so the page shows exactly what the loop starts on.
  await seek(0);
  await page.screenshot({ path: out('jpg'), type: 'jpeg', quality: 86 });

  const duration = await page.evaluate(() => window.DURATION);
  const frames = Math.round(duration * FPS);
  const ff = spawn('ffmpeg', ['-y', '-v', 'error', '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'png', '-i', '-',
    '-c:v', 'libx264', '-preset', 'slow', '-crf', '20', '-pix_fmt', 'yuv420p', '-an', '-movflags', '+faststart', out('mp4')],
    { stdio: ['pipe', 'inherit', 'inherit'] });
  for (let i = 0; i < frames; i++) {
    await seek(i / FPS);
    const buf = await page.screenshot({ type: 'png' });
    if (!ff.stdin.write(buf)) await new Promise(r => ff.stdin.once('drain', r));
    if (i % 150 === 0) console.log(`${APP} frame ${i}/${frames}`);
  }
  ff.stdin.end();
  await new Promise(r => ff.on('close', r));
  await browser.close();
  console.log('wrote', out('mp4'));
})();
