// Study groups, end to end, against an in-memory fake of Firebase (e2e/fake-groups.js).
// Checks the screens and the presence the app publishes; Firestore itself is covered by
// firestore.rules and is shared with Android.
//
//   npm run e2e:groups                   # WebKit, like CI
//   BROWSER=chromium npm run e2e:groups
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium, devices, webkit } from 'playwright';
import { build, preview } from 'vite';

const root = join(import.meta.dirname, '..');
const outDir = join(root, 'dist-e2e');
await build({ root, mode: 'e2e', logLevel: 'warn', build: { outDir, emptyOutDir: true } });

const browserType = process.env.BROWSER === 'chromium' ? chromium : webkit;
const shots = process.env.SCREENSHOTS;
if (shots) mkdirSync(shots, { recursive: true });
const server = await preview({ root, build: { outDir }, preview: { port: 4327 }, logLevel: 'warn' });
const url = server.resolvedUrls.local[0];
const browser = await browserType.launch();
let failures = 0;

function check(condition, message) {
  console.log(`  ${condition ? '✓' : '✗'} ${message}`);
  if (!condition) failures++;
}

try {
  console.log(`\n${browserType.name()} · groups`);
  const context = await browser.newContext({ ...(devices['iPhone 15'] ?? devices['iPhone 14']), timezoneId: 'Australia/Sydney', serviceWorkers: 'block' });
  await context.clock.install({ time: new Date('2026-10-24T15:59:00+11:00') });
  await context.clock.pauseAt(new Date('2026-10-24T16:00:00+11:00'));
  await context.addInitScript({ path: join(import.meta.dirname, 'fake-groups.js') });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  const main = async () => (await page.locator('main').innerText()).replace(/\s+/g, ' ');
  const snap = async (name) => shots && page.screenshot({ path: join(shots, `groups-${name}.png`), animations: 'disabled' });
  // The fake clock is paused; Preact runs effects after a frame, so let a frame pass.
  const settle = () => context.clock.runFor(100);

  await page.goto(`${url}#groups`);
  await settle();
  check((await main()).includes('Study with friends'), 'signed out: explains groups and offers sign-in');
  await page.getByRole('button', { name: 'Create account' }).first().click();
  await page.getByLabel('Name your groups will see').fill('Tymon');
  await page.getByLabel('Email').fill('tymon@example.com');
  await page.getByLabel(/Password/).fill('secret123');
  await snap('1-sign-up');
  await page.getByRole('button', { name: 'Create account' }).last().click();
  await settle();
  check((await main()).includes("You're not in a group yet"), 'signed in with no groups');

  await page.getByRole('button', { name: 'Create group' }).click();
  await page.getByLabel('Group name').fill('Year 12 Maths');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await settle();
  await page.getByText('Code K7PX2Q', { exact: true }).waitFor();
  let detail = await main();
  check(detail.includes('Mina') && detail.includes('Studying · Chemistry') && detail.includes('1:00:00'), "a studying friend shows with today's live total (40m + 20m running)");
  await context.clock.runFor(4_900);
  detail = await main();
  check(detail.includes('1:00:05'), "the friend's clock ticks on this phone");

  await page.getByLabel('Message').fill('Library at 5?');
  await page.getByRole('button', { name: 'Send' }).click();
  await settle();
  await page.getByText('Library at 5?').waitFor();
  check(true, 'sends a chat message');
  await page.getByRole('button', { name: 'This week' }).click();
  await snap('2-group');

  const fake = () => page.evaluate(() => window.__LOCKIN_FAKE_GROUPS__.state);
  await context.clock.runFor(2_000);
  check((await fake()).published.at(-1)?.state === 'IDLE', 'publishes "not studying" for me');

  // Start studying: presence flips, chat locks.
  await page.getByRole('button', { name: 'Timer' }).click();
  const startedAt = await page.evaluate(() => Date.now());
  await page.getByRole('button', { name: 'Start General' }).click();
  await context.clock.runFor(2_000);
  const mine = (await fake()).published.at(-1);
  check(mine?.state === 'STUDYING' && mine?.subject === 'General' && mine?.runningSinceMs === Math.floor(startedAt / 1000) * 1000, 'publishes "studying" with the start time, once');
  const writes = (await fake()).published.length;
  await context.clock.runFor(60_000);
  check((await fake()).published.length === writes, 'does not republish every second while studying');

  await page.getByRole('button', { name: 'Groups' }).click();
  await settle();
  check((await main()).includes('Chat opens on your break'), 'chat is hidden during focus');
  await snap('3-chat-locked');

  await page.evaluate(() => window.__LOCKIN_FAKE_GROUPS__.nudgeMe('Mina'));
  await settle();
  await page.getByText('Time to lock in.').waitFor();
  check(true, 'a nudge shows a banner');
  await snap('4-nudge');
  await page.getByRole('button', { name: 'OK' }).click();
  await settle();
  await page.getByText('Time to lock in.').waitFor({ state: 'detached' });
  check((await fake()).nudges.length === 0, 'dismissing marks the nudge seen');

  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  await context.close();
} finally {
  await browser.close();
  await new Promise((resolve) => server.httpServer.close(resolve));
}

if (failures > 0) {
  console.error(`\n${failures} check(s) failed`);
  process.exit(1);
}
console.log('\nAll checks passed');
