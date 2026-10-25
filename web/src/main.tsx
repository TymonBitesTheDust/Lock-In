import { render } from 'preact';
import { startRuntime } from './data/runtime';
import { App } from './ui/App';
import './styles.css';

startRuntime();
render(<App />, document.getElementById('app')!);

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => {
    navigator.serviceWorker
      .register('./sw.js')
      .then((reg) => {
        const announce = (worker: ServiceWorker | null) => {
          // Only an update: the first install has no controller to replace.
          if (worker && navigator.serviceWorker.controller) window.dispatchEvent(new CustomEvent('lockin:update', { detail: worker }));
        };
        if (reg.waiting) announce(reg.waiting);
        reg.addEventListener('updatefound', () => {
          const worker = reg.installing;
          worker?.addEventListener('statechange', () => {
            if (worker.state === 'installed') announce(worker);
          });
        });
        // Check for a new version whenever Lock In comes back to the foreground.
        document.addEventListener('visibilitychange', () => {
          if (document.visibilityState === 'visible') void reg.update().catch(() => {});
        });
      })
      .catch(() => {});
  });
}
