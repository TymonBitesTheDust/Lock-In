// End-to-end smoke test of the built web app on an iPhone-sized screen.
//
//   npm run build && npm run e2e                 # WebKit (Safari's engine), like CI
//   BROWSER=chromium npm run e2e                 # if WebKit isn't installed
//   SCREENSHOTS=shots npm run e2e                # also save screenshots, light and dark
//
// It walks every tab, and checks the things that matter most on iPhone: time keeps counting
// while Lock In is closed, Pomodoro boundaries are crossed at the right moment, data survives a
// reload, and the app opens offline once installed.
import { mkdirSync } from 'node:fs';
import { join } from 'node:path';
import { chromium, devices, webkit } from 'playwright';
import { preview } from 'vite';

const browserType = process.env.BROWSER === 'chromium' ? chromium : webkit;
const shots = process.env.SCREENSHOTS;
if (shots) mkdirSync(shots, { recursive: true });

const server = await preview({ root: join(import.meta.dirname, '..'), preview: { port: 4317, strictPort: false }, logLevel: 'warn' });
const url = server.resolvedUrls.local[0];
const browser = await browserType.launch();
const device = devices['iPhone 15'] ?? devices['iPhone 14'];
let failures = 0;

function check(condition, message) {
  if (condition) {
    console.log(`  ✓ ${message}`);
  } else {
    failures++;
    console.error(`  ✗ ${message}`);
  }
}

async function text(page) {
  return (await page.locator('main').innerText()).replace(/\s+/g, ' ');
}

async function snap(page, name) {
  if (shots) await page.screenshot({ path: join(shots, `${name}.png`), animations: 'disabled' });
}

async function run(colorScheme) {
  console.log(`\n${browserType.name()} · ${colorScheme}`);
  const context = await browser.newContext({ ...device, colorScheme, timezoneId: 'Australia/Sydney', locale: 'en-AU', serviceWorkers: 'block' });
  // 10:00 on a Saturday, so the study day (05:00 to 05:00) is well under way.
  await context.clock.install({ time: new Date('2026-10-24T09:59:00+11:00') });
  // Paused: time only moves when the test says so, so page loads don't add stray seconds.
  await context.clock.pauseAt(new Date('2026-10-24T10:00:00+11:00'));
  let page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url);

  check((await text(page)).includes('Today 0:00:00'), 'opens on the timer with nothing studied');
  await snap(page, `${colorScheme}-1-timer-empty`);

  for (const name of ['Maths', 'Physics']) {
    await page.getByRole('button', { name: 'Add' }).first().click();
    await page.getByLabel('Name').fill(name);
    await page.getByRole('button', { name: 'Save' }).click();
  }
  check((await text(page)).includes('Maths') && (await text(page)).includes('Physics'), 'adds two subjects');

  await page.getByRole('button', { name: 'Start Maths' }).click();
  await context.clock.runFor(7 * 60_000);
  check((await page.getByRole('timer').innerText()) === '07:00', 'stopwatch shows 07:00 after seven minutes');
  await snap(page, `${colorScheme}-2-session`);

  // Close Lock In for half an hour, like locking the iPhone or swiping the app away.
  await page.close();
  await context.clock.fastForward(30 * 60_000);
  page = await context.newPage();
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url);
  const reopened = await page.getByRole('timer').innerText();
  check(reopened === '37:00', `time kept counting while the app was closed (${reopened})`);

  await page.getByRole('button', { name: 'Pause' }).click();
  await context.clock.runFor(10 * 60_000);
  const paused = await page.getByRole('timer').innerText();
  check(paused === '37:00', `paused time does not count (${paused})`);
  await page.getByRole('button', { name: 'Stop' }).click();
  const stopped = await text(page);
  check(stopped.includes('Today 0:37:00'), `today's total is 0:37:00 after stopping (${stopped.slice(0, 40)})`);

  // Pomodoro: a round ends at exactly 25 minutes, even with the page open the whole time.
  await page.getByRole('button', { name: /Pomodoro/ }).click();
  await page.getByRole('button', { name: 'Start Physics' }).click();
  check((await page.getByRole('timer').innerText()) === '25:00', 'Pomodoro starts at 25:00');
  await context.clock.runFor(25 * 60_000 + 2_000);
  const afterRound = await text(page);
  check(afterRound.includes('Short break'), 'the break starts when the focus round ends');
  check((await page.locator('.toast-host').innerText()).includes('Round done'), 'a toast says the round is done');
  await snap(page, `${colorScheme}-3-pomodoro-break`);
  await page.getByRole('button', { name: 'Stop' }).click();
  await context.clock.runFor(4_000); // let the toast go, for clean screenshots
  await page.getByRole('button', { name: 'Stopwatch' }).click();

  // Planner
  await page.getByRole('button', { name: 'Planner' }).click();
  await page.getByLabel('Add a task').fill('Chapter 3 problems');
  await page.getByRole('button', { name: 'Add task' }).click();
  await page.getByRole('button', { name: 'Add' }).first().click();
  await page.getByLabel("What's coming up?").fill('Final exams');
  await page.getByRole('button', { name: 'Add', exact: true }).last().click();
  const planner = await text(page);
  check(planner.includes('Chapter 3 problems') && planner.includes('D-30') && planner.includes('Studied 1h 02m'), 'planner shows the task, the D-Day and 1h 02m studied');
  await snap(page, `${colorScheme}-4-planner`);

  // Stats
  await page.getByRole('button', { name: 'Stats' }).click();
  const stats = await text(page);
  check(stats.includes('Total 1h 02m') && stats.includes('Maths') && stats.includes('Current streak 1 day'), 'stats total 1h 02m, Maths listed, 1-day streak');
  await snap(page, `${colorScheme}-5-stats`);
  await page.getByRole('button', { name: 'Day', exact: true }).click();
  await snap(page, `${colorScheme}-6-stats-day`);

  // Groups (off without a Firebase config) and Settings
  await page.getByRole('button', { name: 'Groups' }).click();
  await snap(page, `${colorScheme}-7-groups`);
  await page.getByRole('button', { name: 'Settings' }).click();
  check((await text(page)).includes('Pomodoro') && (await text(page)).includes('Your data'), 'settings open');
  await snap(page, `${colorScheme}-8-settings`);
  await page.getByRole('button', { name: /Focus protection/ }).click();
  await snap(page, `${colorScheme}-9-focus`);
  await page.getByRole('button', { name: 'Back' }).click();

  // Everything survives a reload.
  await page.reload();
  await page.getByRole('button', { name: 'Timer' }).click();
  const home = await text(page);
  check(home.includes('Today 1:02:00') && home.includes('Final exams · D-30'), `data survives a reload (${home.slice(0, 60)})`);
  await snap(page, `${colorScheme}-10-timer-home`);

  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  await context.close();
}

// Colour theme and allowed apps: Orange + forced Dark on a light-mode phone, kept after a reload;
// apps added in Focus protection show under the clock during focus.
async function appearance() {
  console.log(`\n${browserType.name()} · theme and allowed apps`);
  const context = await browser.newContext({ ...device, colorScheme: 'light', serviceWorkers: 'block' });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (e) => errors.push(String(e)));
  await page.goto(url);
  const vars = () =>
    page.evaluate(() => {
      const css = getComputedStyle(document.documentElement);
      return { theme: document.documentElement.dataset.theme, primary: css.getPropertyValue('--primary').trim().toUpperCase(), bg: getComputedStyle(document.body).backgroundColor };
    });

  check((await vars()).theme === 'light', 'follows the light device by default');
  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'Orange' }).click();
  check((await vars()).primary === '#964800', `Orange accent in light (${(await vars()).primary})`);
  await page.getByRole('button', { name: 'Dark', exact: true }).click();
  let v = await vars();
  check(v.theme === 'dark' && v.primary === '#FFAF7B' && v.bg === 'rgb(15, 18, 22)', `forced Dark with the Orange accent (${JSON.stringify(v)})`);
  await snap(page, 'theme-1-settings-orange-dark');

  await page.reload();
  v = await vars();
  check(v.theme === 'dark' && v.primary === '#FFAF7B', 'theme and accent survive a reload');

  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: /Focus protection/ }).click();
  await page.getByRole('button', { name: 'Add an app' }).click();
  await page.getByRole('button', { name: 'Spotify', exact: true }).click();
  await page.getByRole('button', { name: 'Add an app' }).click();
  await page.getByLabel('Name', { exact: true }).fill('Checkmate');
  await page.getByLabel('Link or Shortcut name').fill('Open Checkmate');
  await page.getByRole('button', { name: 'Add', exact: true }).click();
  const focus = await text(page);
  check(focus.includes('Spotify') && focus.includes('Shortcut: Open Checkmate'), 'adds a preset app and a Shortcut app');
  await snap(page, 'theme-2-allowed-apps');
  await page.getByRole('button', { name: 'Back' }).click();

  await page.getByRole('button', { name: 'Timer' }).click();
  await page.getByRole('button', { name: 'Start General' }).click();
  await page.getByRole('button', { name: 'Open Spotify' }).waitFor({ timeout: 5_000 }).catch(() => {});
  check(
    (await page.getByRole('button', { name: 'Open Spotify' }).count()) === 1 && (await page.getByRole('button', { name: 'Open Checkmate' }).count()) === 1,
    'the allowed apps show under the clock during focus',
  );
  await snap(page, 'theme-3-session-allowed-apps');
  await page.getByRole('button', { name: 'Pause' }).click();
  check((await page.getByRole('button', { name: 'Open Spotify' }).count()) === 0, 'and hide while paused');
  await page.getByRole('button', { name: 'Stop' }).click();

  await page.getByRole('button', { name: 'Settings' }).click();
  await page.getByRole('button', { name: 'System' }).click();
  check((await vars()).theme === 'light', 'System follows the device again');
  check(errors.length === 0, `no page errors${errors.length ? `: ${errors.join(' | ')}` : ''}`);
  await context.close();
}

async function offline() {
  // Service workers are reliable in Playwright's Chromium; this check always uses it.
  const sw = browserType === chromium ? browser : await chromium.launch().catch(() => null);
  if (!sw) {
    console.log('\noffline check skipped: Chromium is not installed');
    return;
  }
  console.log('\nchromium · offline');
  const context = await sw.newContext({ ...devices['Pixel 7'] });
  const page = await context.newPage();
  await page.goto(url);
  const ok = await page.evaluate(async () => {
    const reg = await navigator.serviceWorker.ready;
    return Boolean(reg.active);
  });
  check(ok, 'the service worker installs');
  await page.reload();
  await context.setOffline(true);
  await page.reload();
  check((await page.locator('main').innerText()).includes('Today'), 'opens without a connection');
  await context.close();
  if (sw !== browser) await sw.close();
}

try {
  await run('light');
  await run('dark');
  await appearance();
  await offline();
} finally {
  await browser.close();
  await new Promise((resolve) => server.httpServer.close(resolve));
}

if (failures > 0) {
  console.error(`\n${failures} check(s) failed`);
  process.exit(1);
}
console.log('\nAll checks passed');
