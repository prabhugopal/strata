// Every internal link in the built site must lead to a page that exists. Run after a build;
// DOCS_BASE must match the build's base path.
import { readdirSync, readFileSync, existsSync, statSync } from 'node:fs';
import { join, posix } from 'node:path';

const dist = new URL('../dist', import.meta.url).pathname;
const base = (process.env.DOCS_BASE ?? '/').replace(/\/?$/, '/');
const pages = [];
(function walk(d) { for (const f of readdirSync(d)) { const p = join(d, f); statSync(p).isDirectory() ? walk(p) : f.endsWith('.html') && pages.push(p); } })(dist);

let bad = 0, checked = 0;
for (const file of pages) {
  const url = base + posix.relative(dist, file).replace(/index\.html$/, '');
  for (const [, href] of readFileSync(file, 'utf8').matchAll(/href="([^"#?]+)[^"]*"/g)) {
    if (/^(https?:|mailto:|data:)/.test(href) || href.startsWith('//')) continue;
    const abs = posix.resolve(posix.dirname(url + 'x'), href) + (href.endsWith('/') ? '/' : '');
    if (!(abs + '/').startsWith(base)) { console.log(`${url}: ${href} is outside ${base}`); bad++; continue; }
    const target = join(dist, abs.slice(Math.min(abs.length, base.length)));
    checked++;
    if (!existsSync(target) && !existsSync(join(target, 'index.html'))) { console.log(`${url}: broken ${href}`); bad++; }
  }
}
console.log(`${pages.length} pages, ${checked} internal links, ${bad} broken`);
process.exit(bad ? 1 : 0);
