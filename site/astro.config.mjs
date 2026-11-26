// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import svelte from '@astrojs/svelte';
import starlightSidebarTopics from 'starlight-sidebar-topics';

export default defineConfig({
  // Where the site is published, e.g. DOCS_SITE=https://strata-jvm.github.io DOCS_BASE=/strata for GitHub Pages.
  site: process.env.DOCS_SITE,
  base: process.env.DOCS_BASE ?? '/',
  integrations: [
    starlight({
      title: 'Strata',
      description: 'Durable, distributed entities for the JVM, on Apache Pekko.',
      customCss: ['@fontsource/ibm-plex-sans/400.css', '@fontsource/ibm-plex-sans/600.css', '@fontsource/ibm-plex-mono/400.css', './src/styles/theme.css'],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/strata-jvm/strata' }],
      // Three topics, each with its own short sidebar, and a switcher above it.
      plugins: [
        starlightSidebarTopics([
          {
            label: 'Learn',
            link: 'start/introduction/',
            icon: 'open-book',
            items: [
              { label: 'Start here', items: ['start/introduction', 'start/quick-start', 'start/shop'] },
              {
                label: 'How Strata works',
                items: ['concepts/entities', 'concepts/the-seal', 'concepts/durable-invocations', 'concepts/other-entities',
                  'concepts/timers', 'concepts/upgrades', 'concepts/clusters'],
              },
            ],
          },
          {
            label: 'Guides',
            link: 'guides/services/',
            icon: 'puzzle',
            items: [
              { label: 'Building', items: ['guides/services', 'guides/languages', 'guides/spring'] },
              { label: 'Running', items: ['guides/kubernetes', 'guides/stratum'] },
            ],
          },
          {
            label: 'Reference',
            link: 'reference/configuration/',
            icon: 'information',
            items: ['reference/configuration', 'reference/annotations', 'reference/management-api', 'reference/cli'],
          },
        ]),
      ],
    }),
    svelte(),
  ],
});
