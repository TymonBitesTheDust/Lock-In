/// <reference types="vitest/config" />
import { createHash } from 'node:crypto';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';
import preact from '@preact/preset-vite';
import { defineConfig, type Plugin } from 'vite';

/**
 * Emits `sw.js` with the list of every built file, so the app opens offline after the first visit.
 * The cache name is a hash of that list, so each deploy replaces the previous cache.
 */
function serviceWorker(): Plugin {
  return {
    name: 'lockin-service-worker',
    apply: 'build',
    generateBundle(_options, bundle) {
      const publicDir = join(import.meta.dirname, 'public');
      const walk = (dir: string): string[] =>
        readdirSync(dir).flatMap((name) => {
          const path = join(dir, name);
          return statSync(path).isDirectory() ? walk(path) : [relative(publicDir, path).split('\\').join('/')];
        });
      // The app shell: the entry chunk, everything it imports statically, CSS and public files.
      // Lazily loaded code (the Firebase SDK for groups) is cached the first time it's used instead.
      const shell = new Set<string>();
      const visit = (name: string) => {
        const chunk = bundle[name];
        if (!chunk || shell.has(name)) return;
        shell.add(name);
        if (chunk.type === 'chunk') {
          chunk.imports.forEach(visit);
          chunk.viteMetadata?.importedCss.forEach((css) => shell.add(css));
          chunk.viteMetadata?.importedAssets.forEach((asset) => shell.add(asset));
        }
      };
      for (const [name, chunk] of Object.entries(bundle)) if (chunk.type === 'chunk' && chunk.isEntry) visit(name);
      for (const [name, item] of Object.entries(bundle)) if (item.type === 'asset' && (name.endsWith('.css') || name.endsWith('.html'))) shell.add(name);
      const files = ['./', ...shell, ...walk(publicDir)]
        .filter((f) => !f.endsWith('.map') && f !== 'sw.js')
        .map((f) => (f === './' ? f : `./${f}`))
        .sort();
      const hash = createHash('sha256').update(files.join('\n'));
      for (const item of Object.values(bundle)) hash.update(item.type === 'chunk' ? item.code : typeof item.source === 'string' ? item.source : Buffer.from(item.source));
      // index.html and public/ may be emitted after this hook runs, so hash their sources too.
      hash.update(readFileSync(join(import.meta.dirname, 'index.html')));
      for (const file of walk(publicDir)) hash.update(readFileSync(join(publicDir, file)));
      const version = hash.digest('hex').slice(0, 12);
      const template = readFileSync(join(import.meta.dirname, 'src/sw-template.js'), 'utf8');
      this.emitFile({
        type: 'asset',
        fileName: 'sw.js',
        source: template.replace('__PRECACHE__', JSON.stringify(files)).replace('__VERSION__', version),
      });
    },
  };
}

const pkg = JSON.parse(readFileSync(join(import.meta.dirname, 'package.json'), 'utf8')) as { version: string };

export default defineConfig({
  define: {
    __APP_VERSION__: JSON.stringify(`${pkg.version}${process.env.GITHUB_SHA ? ` (${process.env.GITHUB_SHA.slice(0, 7)})` : ''}`),
  },
  // Relative paths: works at https://<user>.github.io/Lock-In/ and at any other path.
  base: './',
  plugins: [preact(), serviceWorker()],
  build: {
    target: ['safari16', 'chrome110', 'firefox110'],
    sourcemap: false,
    // The Firebase SDK (groups only) is one big lazily loaded chunk; the app itself is ~35 kB gzipped.
    chunkSizeWarningLimit: 700,
  },
  test: {
    environment: 'node',
    env: { TZ: 'Australia/Sydney' },
    include: ['test/**/*.test.ts'],
  },
});
