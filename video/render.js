// Renders story.html into the video, frame by frame, and adds the audio from audio.py.
//   node render.js out.mp4            the whole video (30 fps, 1920x1080)
//   node render.js --stills 12,95.5   PNG stills at those seconds, into build/stills/
const { chromium } = require('playwright');
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const here = __dirname;
const FPS = 30;
const args = process.argv.slice(2);

(async () => {
  const timing = JSON.parse(fs.readFileSync(path.join(here, 'build/timing.json')));
  const meta = JSON.parse(fs.readFileSync(path.join(here, 'build/shots/meta.json')));
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1920, height: 1080 }, deviceScaleFactor: 1 });
  await page.goto('file://' + path.join(here, 'story.html'));
  await page.evaluate(([t, m]) => window.setup(t, m), [timing, meta]);
  await page.evaluate(() => window.ready());
  const stage = await page.$('#stage');

  if (args[0] === '--stills') {
    fs.mkdirSync(path.join(here, 'build/stills'), { recursive: true });
    for (const s of args[1].split(',')) {
      await page.evaluate((t) => window.render(t), Number(s));
      await stage.screenshot({ path: path.join(here, `build/stills/${s}.png`) });
      console.log('still', s);
    }
    await browser.close();
    return;
  }

  const out = args[0] || path.join(here, 'build/story.mp4');
  const frames = Math.ceil(timing.duration * FPS);
  const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error',
    '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
    '-i', path.join(here, 'build/audio_24k.wav'),
    '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
    '-c:a', 'aac', '-b:a', '160k', '-ar', '48000', '-shortest', out], { stdio: ['pipe', 'inherit', 'inherit'] });
  const started = Date.now();
  for (let f = 0; f < frames; f++) {
    await page.evaluate((t) => window.render(t), f / FPS);
    const jpg = await stage.screenshot({ type: 'jpeg', quality: 93 });
    if (!ff.stdin.write(jpg)) await new Promise((r) => ff.stdin.once('drain', r));
    if (f % 300 === 0) console.log(`frame ${f}/${frames}  ${((Date.now() - started) / 1000).toFixed(0)}s`);
  }
  ff.stdin.end();
  await new Promise((r) => ff.on('close', r));
  await browser.close();
  console.log('wrote', out);
})();
