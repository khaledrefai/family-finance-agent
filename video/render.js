// Renders story.html into the video, frame by frame, and adds the audio from audio.py.
//   node render.js out.mp4            the whole video (30 fps, 1920x1080)
//   node render.js --stills 12,95.5   PNG stills at those seconds, into build/stills/
// The frames go out in 30-second parts (build/parts/). A part that is already there is kept, so a render that
// stopped half way picks up where it was. Delete build/parts/ after changing story.html, shots or timing.
const { chromium } = require('playwright');
const { spawn, execFileSync } = require('child_process');
const fs = require('fs');
const path = require('path');

const here = __dirname;
const FPS = 30;
const PART = 30 * FPS;
const args = process.argv.slice(2);

function encode(file, frames, draw) {
  return new Promise(async (resolve, reject) => {
    const ff = spawn('ffmpeg', ['-y', '-loglevel', 'error',
      '-f', 'image2pipe', '-framerate', String(FPS), '-c:v', 'mjpeg', '-i', '-',
      '-c:v', 'libx264', '-preset', 'medium', '-crf', '20', '-pix_fmt', 'yuv420p', file], { stdio: ['pipe', 'inherit', 'inherit'] });
    ff.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg exited ${code}`))));
    for (const f of frames) {
      const jpg = await draw(f);
      if (!ff.stdin.write(jpg)) await new Promise((r) => ff.stdin.once('drain', r));
    }
    ff.stdin.end();
  });
}

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

  const out = path.resolve(args[0] || path.join(here, 'build/story.mp4'));
  const total = Math.ceil(timing.duration * FPS);
  const partsDir = path.join(here, 'build/parts');
  fs.mkdirSync(partsDir, { recursive: true });
  const parts = [];
  const started = Date.now();
  for (let p = 0; p * PART < total; p++) {
    const file = path.join(partsDir, `part${String(p).padStart(3, '0')}.mp4`);
    parts.push(file);
    if (fs.existsSync(file)) continue;
    const frames = [];
    for (let f = p * PART; f < Math.min(total, (p + 1) * PART); f++) frames.push(f);
    await encode(file + '.tmp.mp4', frames, async (f) => {
      await page.evaluate((t) => window.render(t), f / FPS);
      return stage.screenshot({ type: 'jpeg', quality: 93 });
    });
    fs.renameSync(file + '.tmp.mp4', file);
    console.log(`part ${p + 1} of ${Math.ceil(total / PART)}  ${((Date.now() - started) / 1000).toFixed(0)}s`);
  }
  await browser.close();

  const list = path.join(partsDir, 'list.txt');
  fs.writeFileSync(list, parts.map((f) => `file '${f}'`).join('\n') + '\n');
  execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-f', 'concat', '-safe', '0', '-i', list,
    '-i', path.join(here, 'build/audio_24k.wav'),
    '-map', '0:v', '-map', '1:a', '-c:v', 'copy', '-c:a', 'aac', '-b:a', '160k', '-ar', '48000',
    '-movflags', '+faststart', '-shortest', out], { stdio: 'inherit' });
  console.log('wrote', out);
})();
