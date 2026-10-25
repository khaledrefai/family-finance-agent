"""Narration and music for the story video.

Reads script.json, speaks every line with Kokoro (offline), lays the lines out scene by scene and writes:
  build/timing.json   when each scene and each line starts and ends (the page uses it for scenes and subtitles)
  build/audio.wav     narration over a quiet music bed, 48 kHz stereo

  python audio.py script.json build  (needs kokoro-v1.0.onnx and voices-v1.0.bin in KOKORO_DIR)
"""
import hashlib
import json
import os
import re
import sys

import numpy as np
import soundfile as sf

SR = 24000          # Kokoro's rate; the mix is resampled to 48 kHz by ffmpeg at the end
script_path, out = sys.argv[1], sys.argv[2]
os.makedirs(f"{out}/lines", exist_ok=True)
script = json.load(open(script_path))
voice, speed, gap = script["voice"], script["speed"], script["gap"]
# Words the voice says better spelled another way. The subtitles keep the real spelling.
pronounce = script.get("pronounce", {})


def spoken(text):
    for word, say in pronounce.items():
        text = re.sub(rf"\b{re.escape(word)}\b", say, text)
    return text

kokoro = None


def speak(text):
    """One line, cached by its words, so editing one line does not respeak the others."""
    global kokoro
    key = hashlib.sha1(f"{voice}|{speed}|{text}".encode()).hexdigest()[:16]
    path = f"{out}/lines/{key}.wav"
    if not os.path.exists(path):
        if kokoro is None:
            from kokoro_onnx import Kokoro
            d = os.environ.get("KOKORO_DIR", ".")
            kokoro = Kokoro(f"{d}/kokoro-v1.0.onnx", f"{d}/voices-v1.0.bin")
        samples, sr = kokoro.create(text, voice=voice, speed=speed, lang="en-us")
        assert sr == SR
        sf.write(path, samples, SR)
    audio, _ = sf.read(path, dtype="float32")
    # trim the model's silence at both ends, keep 40 ms
    loud = np.where(np.abs(audio) > 0.01)[0]
    if len(loud):
        audio = audio[max(0, loud[0] - 960): loud[-1] + 960]
    return audio


timing = {"scenes": []}
voice_track = []
t = 0.0
for scene in script["scenes"]:
    start = t
    t += scene.get("lead", 0.4)
    lines = []
    for i, text in enumerate(scene["lines"]):
        a = speak(spoken(text))
        lines.append({"text": text, "start": round(t, 3), "end": round(t + len(a) / SR, 3)})
        voice_track.append((t, a))
        t += len(a) / SR + (gap if i < len(scene["lines"]) - 1 else 0)
    t += scene.get("tail", 0.8)
    timing["scenes"].append({"id": scene["id"], "start": round(start, 3), "end": round(t, 3), "lines": lines})
    print(f'{scene["id"]:10s} {start:7.2f} -> {t:7.2f}  ({t - start:5.2f}s)')

total = t + 0.5
timing["duration"] = round(total, 3)
n = int(total * SR)
narration = np.zeros(n, dtype=np.float32)
for at, a in voice_track:
    i = int(at * SR)
    narration[i:i + len(a)] += a[: n - i]

# ---------------------------------------------------------------- music bed
# A soft pad, a low note and a slow plucked arpeggio. The mood follows the story: calm for the two lives,
# minor while the three years run out, warm and major from the app onwards.
rng = np.random.default_rng(7)


def midi(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def pad(freqs, dur):
    tt = np.arange(int(dur * SR)) / SR
    s = np.zeros_like(tt)
    for f in freqs:
        for det in (-0.12, 0.0, 0.12):   # three slightly detuned voices per note: a gentle chorus
            ph = rng.uniform(0, 2 * np.pi)
            s += np.sin(2 * np.pi * f * (1 + det / 100) * tt + ph) + 0.18 * np.sin(4 * np.pi * f * tt + ph)
    env = np.minimum(1, tt / 1.2) * np.minimum(1, (dur - tt) / 1.0).clip(0)
    return s * env / (3 * len(freqs))


_plucks = {}


def pluck(f, dur):
    # Karplus-Strong string, one per note (the arpeggio repeats the same few)
    if (f, dur) not in _plucks:
        _plucks[(f, dur)] = _pluck(f, dur)
    return _plucks[(f, dur)]


def _pluck(f, dur):
    N = int(SR / f)
    buf = rng.uniform(-1, 1, N)
    outp = np.zeros(int(dur * SR))
    for i in range(len(outp)):
        outp[i] = buf[i % N]
        buf[i % N] = 0.5 * (buf[i % N] + buf[(i + 1) % N]) * 0.996
    return outp * np.exp(-np.arange(len(outp)) / SR * 2.2)


# chords as MIDI notes (root position, around middle C)
CALM = [[60, 64, 67, 71], [57, 60, 64, 67], [53, 57, 60, 64], [55, 59, 62, 65]]      # Cmaj7 Am7 Fmaj7 G7
MINOR = [[57, 60, 64], [53, 57, 60], [50, 53, 57], [52, 56, 59]]                    # Am F Dm E
WARM = [[53, 57, 60, 64], [55, 59, 62], [52, 55, 59], [57, 60, 64], [53, 57, 60], [55, 59, 62], [60, 64, 67], [60, 64, 67]]  # F G Em Am F G C C

scene_at = {s["id"]: s["start"] for s in timing["scenes"]}
sections = [(0.0, CALM), (scene_at["years"], MINOR), (scene_at["yousefEnd"], CALM), (scene_at["bridge"], WARM)]
bar = 60 / 76 * 4   # 76 bpm, 4/4
music = np.zeros(n + SR * 4)
for k, (s0, prog) in enumerate(sections):
    s1 = sections[k + 1][0] if k + 1 < len(sections) else total
    tt, ci = s0, 0
    while tt < s1 - 0.2:
        chord = prog[ci % len(prog)]
        d = min(bar * 2, s1 - tt) if prog is not WARM else min(bar, s1 - tt)
        i = int(tt * SR)
        p = pad([midi(m) for m in chord], d + 1.0)
        music[i:i + len(p)] += 0.55 * p[: len(music) - i]
        b = pad([midi(chord[0] - 24)], d + 0.8)
        music[i:i + len(b)] += 0.35 * b[: len(music) - i]
        # arpeggio in eighths, only from the second section on: the story picks up pace
        if k > 0:
            notes = chord + [chord[1] + 12]
            step = bar / 8
            for j in range(int(d / step)):
                f = midi(notes[[0, 1, 2, 3, 2, 1, 3, 2][j % 8]] + 12)
                pl = pluck(f, 1.6)
                q = int((tt + j * step) * SR)
                music[q:q + len(pl)] += 0.10 * pl[: len(music) - q]
        tt += d
        ci += 1
music = music[:n]

# small room reverb: convolve with a decaying noise tail
ir_len = int(2.2 * SR)
ir = rng.normal(0, 1, ir_len) * np.exp(-np.arange(ir_len) / SR * 3.2)
ir[0] = 6.0
music = np.convolve(music, ir / np.abs(ir).sum() * 8, mode="full")[:n]
music /= np.abs(music).max() + 1e-9

# duck the music under the voice
env = np.abs(narration)
win = int(0.25 * SR)
env = np.convolve(env, np.ones(win) / win, mode="same")
active = (env > 0.004).astype(float)
active = np.convolve(active, np.ones(int(0.6 * SR)) / int(0.6 * SR), mode="same").clip(0, 1)
gain = 0.16 - 0.09 * active
fade = np.minimum(1, np.arange(n) / (SR * 2)) * np.minimum(1, (n - np.arange(n)) / (SR * 3))
mix_m = music * gain * fade

narr = narration / (np.abs(narration).max() + 1e-9) * 0.9
left = narr + mix_m
right = narr + mix_m
stereo = np.stack([left, right], axis=1)
stereo /= max(1.0, np.abs(stereo).max() / 0.97)
sf.write(f"{out}/audio_24k.wav", stereo.astype(np.float32), SR)
json.dump(timing, open(f"{out}/timing.json", "w"), indent=1)
print(f"total {total:.2f}s")
