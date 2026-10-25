// Captures the real app's screens for the story video, at phone size, from a running stack
// (./deploy.sh reseed, then story-overlay.sql). Writes shots/<name>.png (the whole page, tab bar hidden),
// shots/<name>-tab.png (the tab bar) and shots/meta.json (page sizes and where the marked parts are).
//   node capture.js [base-url] [out-dir]
const { chromium } = require('playwright');
const fs = require('fs');
const B = process.argv[2] || 'http://localhost:3000';
const OUT = process.argv[3] || 'shots';
const meta = {};

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 390, height: 800 }, deviceScaleFactor: 2, colorScheme: 'light' });
  const p = await ctx.newPage();
  const wait = (ms) => p.waitForTimeout(ms);

  const login = async (u) => {
    await p.goto(B + '/login'); await p.evaluate(() => sessionStorage.clear());
    await p.goto(B + '/login');
    await p.fill('input[autocomplete=username]', u); await p.fill('input[type=password]', 'demo1234');
    await p.click('button[type=submit]'); await wait(2500);
  };
  const go = async (path) => { await p.goto(B + path); await wait(2500); };

  // marks: { key: [text, 'card' | 'text'] }. 'card' climbs from the text to the card it sits in.
  const snap = async (name, marks = {}) => {
    const hideDev = await p.addStyleTag({ content: 'nextjs-portal{display:none!important}' });
    await p.evaluate(() => window.scrollTo(0, 0)); await wait(300);
    const tab = await p.$('.tabbar');
    let tabH = 0;
    if (tab) {
      // the tab bar alone, over the plain page colour (the video lays it over its own scrolled page)
      const only = await p.addStyleTag({ content: 'body *{visibility:hidden!important} .tabbar,.tabbar *{visibility:visible!important}' });
      await tab.screenshot({ path: `${OUT}/${name}-tab.png` }); tabH = (await tab.boundingBox()).height;
      await only.evaluate((e) => e.remove());
    }
    const hideTab = await p.addStyleTag({ content: '.tabbar{display:none!important}' });
    await wait(200);
    await p.screenshot({ path: `${OUT}/${name}.png`, fullPage: true });
    const size = await p.evaluate(() => ({ w: document.documentElement.scrollWidth, h: document.documentElement.scrollHeight,
      bg: getComputedStyle(document.querySelector('main')?.parentElement || document.body).backgroundColor }));
    const boxes = {};
    for (const [key, [text, how]] of Object.entries(marks)) {
      boxes[key] = await p.evaluate(([text, how]) => {
        const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
        let el = null;
        while (walker.nextNode()) {
          if (walker.currentNode.textContent.includes(text)) { el = walker.currentNode.parentElement; break; }
        }
        if (!el) return null;
        if (how === 'card') {
          let c = el;
          while (c && c !== document.body) {
            const s = getComputedStyle(c);
            const filled = s.backgroundColor !== 'rgba(0, 0, 0, 0)' || s.borderTopWidth !== '0px' || s.boxShadow !== 'none';
            if (c.getBoundingClientRect().width >= 300 && parseFloat(s.borderTopLeftRadius) >= 8 && filled) break;
            c = c.parentElement;
          }
          if (c && c !== document.body) el = c;
        }
        const r = el.getBoundingClientRect();
        return { x: r.x, y: r.y + window.scrollY, w: r.width, h: r.height };
      }, [text, how]);
      if (!boxes[key]) console.warn(`  ${name}: no "${text}"`);
    }
    meta[name] = { ...size, tabH, marks: boxes };
    await hideTab.evaluate((e) => e.remove()); await hideDev.evaluate((e) => e.remove());
    console.log(name, size.h, Object.keys(boxes).join(' '));
  };

  // ---- Omar, the first man
  await login('omar');
  await go('/parent');
  await snap('today', { hero: ['Free this month', 'card'], payday: ['free until pay day', 'text'], alert: ['Entertainment is at', 'card'], coming: ['Coming up', 'card'], save: ['Ways to save', 'card'] });
  await go('/parent/plan');
  await snap('plan', { steps: ['Protect the commitments', 'card'], card: ['Clear the card', 'text'], buffer: ['Build a buffer', 'text'], buckets: ['Money buckets', 'card'] });
  await go('/parent/plan/buckets');
  await snap('buckets', { flow: ['How the money flows', 'card'] });
  await go('/parent/money/budgets');
  await snap('budgets', { budgets: ['Spent so far against each budget', 'card'], alert: ['Entertainment is at', 'text'], advice: ['cut entertainment to', 'card'] });
  await go('/parent/money/accounts');
  await snap('accounts', { visa: ["Omar's Visa", 'card'] });
  await go('/parent/money/gold');
  await snap('gold', { worth: ['Worth today', 'card'], never: ['never tells you to buy', 'text'] });
  await go('/parent/money/wealth');
  await snap('wealth', { total: ['An estimate from the balances', 'card'], adds: ['How it adds up', 'card'], loan: ['Loan for the old card debt', 'text'] });
  await go('/parent/summary');
  await snap('summary', { went: ['Went well', 'card'], change: ['To change in', 'card'] });
  await go('/parent/family');
  await snap('family', { rules: ['Family rules', 'text'], peanut: ['No peanuts for Adam', 'card'] });

  // A morning coffee, typed in
  await go('/parent/money/add');
  await p.fill('input[placeholder="186.00"]', '24');
  await p.getByRole('button', { name: 'Restaurant', exact: true }).click();
  await p.fill('input[placeholder^="Family dinner"]', 'Morning coffee, Starbucks');
  await p.getByRole('button', { name: /Omar's Visa/ }).click();
  await p.getByRole('button', { name: 'Omar', exact: true }).click();
  await wait(400);
  await snap('add', { form: ['Amount in AED', 'card'], addit: ['Add it', 'text'], where: ['Where', 'text'] });

  // Compare before a big buy, paying with the Visa
  await go('/parent/money/compare');
  await p.fill('input[placeholder^="An air fryer"]', 'Sony WH-CH720N headphones');
  await p.getByRole('button', { name: /Omar's Visa/ }).click();
  await p.getByRole('button', { name: 'Compare prices' }).click(); await wait(5000);
  await snap('compare', { best: ['Best price', 'card'], verdict: ['Best total', 'card'], cardcheck: ['Card check', 'card'] });

  // ---- Layla, 13
  await login('layla');
  await go('/child');
  await snap('kid', { goal: ['My goal', 'card'], wallet: ['My wallet', 'card'] });
  await   await go('/child/ask');
  await p.fill('input[aria-label="What do you want?"]', 'Sony WH-CH720N headphones');
  await p.getByRole('button', { name: 'Find it' }).click(); await wait(5000);
  await snap('kidfind', { found: ['The best price I found', 'card'], best: ['Best price', 'card'] });
  await p.getByRole('button', { name: 'Ask for this one' }).first().click(); await wait(2500);

  // ---- Omar says yes
  await login('omar');
  await go('/parent');
  await snap('waiting', { chit: ['Layla asks', 'card'], approve: ['Approve', 'text'] });
  await p.getByRole('button', { name: 'Approve', exact: true }).first().click(); await wait(3000);
  await snap('approved', { chit: ['Layla asked', 'card'] });

  fs.writeFileSync(`${OUT}/meta.json`, JSON.stringify(meta, null, 1));
  await browser.close();
})();
