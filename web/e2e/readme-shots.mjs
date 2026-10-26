// Renders docs/images/iphone-focus.png for the README: Settings → Appearance, the allowed apps
// in Focus protection, and a focus session with its app shortcuts, in the Orange accent.
//
//   npm run build && node e2e/readme-shots.mjs [out.png]
//
// Each screen is an iPhone-width page (393 px) cropped to the same frame as docs/images/iphone.png.
import { join } from 'node:path';
import { chromium, devices } from 'playwright';
import { preview } from 'vite';

const out = process.argv[2] ?? join(import.meta.dirname, '..', '..', 'docs', 'images', 'iphone-focus.png');
const server = await preview({ root: join(import.meta.dirname, '..'), preview: { port: 4318, strictPort: false }, logLevel: 'warn' });
const url = server.resolvedUrls.local[0];
const browser = await chromium.launch();
const device = devices['iPhone 15'];
const FRAME = { width: 393, height: 659 };

const prefs = (themeMode) => ({
  themeMode,
  accent: 'orange',
  allowedApps: [
    { name: 'Checkmate', link: 'Open Checkmate' },
    { name: 'B-Side', link: 'Open B-Side' },
  ],
});

async function screen(themeMode, colorScheme, go) {
  const context = await browser.newContext({ ...device, viewport: FRAME, colorScheme, serviceWorkers: 'block', timezoneId: 'Australia/Sydney', locale: 'en-AU' });
  await context.clock.install({ time: new Date('2026-10-26T09:59:00+11:00') });
  await context.clock.pauseAt(new Date('2026-10-26T10:00:00+11:00'));
  await context.addInitScript((p) => {
    // Opened from the Home Screen, so Settings doesn't show the "Add to Home Screen" banner.
    Object.defineProperty(navigator, 'standalone', { get: () => true });
    if (!localStorage.getItem('lockin.prefs')) localStorage.setItem('lockin.prefs', JSON.stringify(p));
  }, prefs(themeMode));
  const page = await context.newPage();
  await page.goto(url);
  await go(page, context);
  const png = await page.screenshot({ animations: 'disabled' });
  await context.close();
  return png;
}

const shots = [];
shots.push(
  await screen('dark', 'light', async (page) => {
    await page.getByRole('button', { name: 'Settings' }).click();
    await page.getByText('Appearance', { exact: true }).evaluate((el) => window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 20));
  }),
);
shots.push(
  await screen('light', 'dark', async (page) => {
    await page.getByRole('button', { name: 'Settings' }).click();
    await page.getByRole('button', { name: /Focus protection/ }).click();
    await page.getByText('Apps you can use while studying').evaluate((el) => window.scrollTo(0, el.getBoundingClientRect().top + window.scrollY - 20));
  }),
);
shots.push(
  await screen('dark', 'light', async (page, context) => {
    await page.getByRole('button', { name: 'Add' }).first().click();
    await page.getByLabel('Name').fill('Maths');
    await page.getByRole('button', { name: 'Save' }).click();
    await page.getByRole('button', { name: 'Start Maths' }).click();
    await context.clock.runFor(25 * 60_000 + 13_000);
  }),
);
await browser.close();
await new Promise((resolve) => server.httpServer.close(resolve));

// Frame them like docs/images/iphone.png: rounded screens on a light grey card, side by side.
const page = await (await chromium.launch()).newPage({ viewport: { width: 1461, height: 810 } });
const imgs = shots.map((b) => `data:image/png;base64,${b.toString('base64')}`);
await page.setContent(`<body style="margin:0;background:#E9ECF1;display:flex;gap:30px;padding:30px;box-sizing:border-box">
  ${imgs.map((src) => `<img src="${src}" style="width:447px;height:750px;border-radius:36px;display:block">`).join('')}
</body>`);
await page.screenshot({ path: out, clip: { x: 0, y: 0, width: 1461, height: 810 } });
await page.context().browser().close();
console.log(`wrote ${out}`);
