# The story video: two men, one salary

`family-finance-coach-story.mp4`: 1920x1080, about 3 min 27 s, narrated, with subtitles.

Inspired by a true story. Two men in the UAE earn the same salary (AED 28,000 a month) and both have a wife and two
children. Omar spends without a plan: Starbucks every morning, a restaurant every week, delivery, the mall with the
kids, hotel weekends, and the credit card pays the rest. Yousef lives the same life with a plan: he saves first,
invests a little, buys gold every time his invest money reaches AED 3,000, eats out every two or three weeks when the
budget allows, compares prices, never misses a card payment, and his kids learn the value of money.

Three years later Omar owes AED 60,000 on his cards and turns it into a bank loan (AED 2,100 a month for 36 months).
Yousef has AED 40,000 saved and 70 g of gold, enough for his wife to start a small business.

Then the full demo of the app, built to help the first man. Every app screen in the video is the real app (`web/`,
`core/`, `agents/`) running on the demo family, with Omar as the first man:

| Scene | Screen | What it shows |
|---|---|---|
| Today | `/parent` | What is free until pay day, and Adam's request waiting |
| The plan | `/parent/plan`, `/parent/plan/buckets`, `/parent/plan/calculator` | Bills first, the card paid in full, a buffer; every dirham gets a job; the investing bucket in 1, 5 and 10 years |
| Weekly dinners | `/parent/plan/meals` | "The kids want pasta": a week of dinners that suits everyone, the shopping list inside the grocery budget, busy nights |
| Family outings | `/parent/plan/outings` | "The kids love animals": 3 outings in December for AED 170, inside the fun budget |
| Budgets | `/parent/money/budgets` | Entertainment over budget the same month, and the advice |
| Every dirham | `/parent/money/add` | A morning coffee typed in |
| Before a big buy | `/parent/money/compare` | The best total across shops, and the card check |
| Gold and wealth | `/parent/money/gold`, `/parent/money/wealth` | Gold in grams; wealth minus the cards and the old card-debt loan |
| The kids | `/child`, `/child/ask`, `/child/month`, `/parent` | Layla's goal, the family's dinners and outings on her screen, the best price, Omar approves, her month with the camel |
| Every month | `/parent/summary` | What went well and what to watch |

The story's figures are illustrative (coffee AED 25 a day, a restaurant about AED 420 a week, and so on). The video
says that the app records gold and never tells you to buy it, that outing prices come from the app's saved list, that
the app is a prototype with demo money only, and that it is information, not financial advice.

## Files

| File | What |
|---|---|
| `script.json` | The narration, scene by scene, and how the voice should say a name ("Yousef" is spelled "Yusef" for the voice; "UAE" and "AI" are left plain so each is said as one word) |
| `audio.py` | Speaks each line with Kokoro (offline), lays out the timing, adds a generated music bed: `build/timing.json`, `build/audio_24k.wav` |
| `story-overlay.sql` | One change to the demo family for the story: the car loan becomes "Loan for the old card debt" |
| `capture.sh`, `capture.js` | Reset the demo family, apply the overlay, and screenshot the real app at phone size: `build/shots/`. It also plans the week's dinners (pasta) and December's outings (animals) and saves both, so Layla's home shows them |
| `story.html` | Every scene. `render(t)` draws any moment, so each frame is exact |
| `render.js` | Draws each frame with Playwright and pipes it to ffmpeg with the audio |

## Make it again

1. Run the stack (`./deploy.sh`, or the parts on their own as in the main README) with `SEARCH_LIVE=off` (or `cache`
   after a pre-warm) and `AGENTS_LLM=off`, so the screens show the saved price list and the rule-based answers.
2. `cd video && npm install` (Playwright and the Readex Pro font). Playwright needs Chromium:
   `npx playwright install chromium`. ffmpeg must be on the path.
3. Screens: `PGHOST=localhost ./capture.sh http://localhost:3000`, or with the Docker stack
   `PSQL="docker compose exec -T db psql -U ffa -d ffa" ./capture.sh`. This resets the demo family like
   `./deploy.sh reseed` does, so run `./deploy.sh reseed` again after recording.
4. Voice and music: a Python venv with `pip install kokoro-onnx soundfile`, and `kokoro-v1.0.onnx` and
   `voices-v1.0.bin` from the kokoro-onnx GitHub release `model-files-v1.0` in one folder. Then
   `KOKORO_DIR=<that folder> python audio.py script.json build`. Lines are cached in `build/lines/`, so editing one line
   only respeaks that line.
5. Check a few moments: `node render.js --stills 12,95.5,150` writes PNGs to `build/stills/`.
6. The video: `npm run render` (about 9 minutes for 6,200 frames). It renders 30-second parts into
   `build/parts/` and keeps the ones already done, so a stopped render picks up where it was; delete that folder after
   changing the scenes, the screens or the narration.
