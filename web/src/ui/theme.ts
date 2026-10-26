/**
 * Applies the user's theme: light/dark (data-theme on <html>, which picks the neutral surfaces
 * in styles.css) and the accent colour (CSS custom properties). Follows the device's dark mode
 * while the theme is "System".
 */
import { effect } from '@preact/signals';
import { ACCENTS, isDarkTheme, type AccentName, type ThemeMode } from '../core/model';
import { store } from '../data/runtime';

const BACKGROUND = { light: '#F7F8FA', dark: '#0F1216' } as const;

export function applyTheme(mode: ThemeMode, accent: AccentName, systemDark: boolean) {
  const dark = isDarkTheme(mode, systemDark);
  const root = document.documentElement;
  root.dataset.theme = dark ? 'dark' : 'light';
  const t = ACCENTS[accent][dark ? 'dark' : 'light'];
  const vars: Record<string, string> = {
    '--primary': t.primary,
    '--on-primary': t.onPrimary,
    '--primary-container': t.primaryContainer,
    '--on-primary-container': t.onPrimaryContainer,
    '--secondary-container': t.selection,
    '--on-secondary-container': t.onSelection,
    '--heat-1': t.heat[0],
    '--heat-2': t.heat[1],
    '--heat-3': t.heat[2],
    '--heat-4': t.heat[3],
  };
  for (const [k, v] of Object.entries(vars)) root.style.setProperty(k, v);
  // The status bar / browser chrome colour follows the app's choice, not just the device's.
  document.querySelectorAll('meta[name="theme-color"]').forEach((m) => m.setAttribute('content', dark ? BACKGROUND.dark : BACKGROUND.light));
}

export function startTheme() {
  const media = window.matchMedia?.('(prefers-color-scheme: dark)');
  const apply = () => {
    const p = store.prefs.value;
    applyTheme(p.themeMode, p.accent, media?.matches ?? false);
  };
  effect(apply);
  media?.addEventListener?.('change', apply);
}
