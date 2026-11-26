// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import svelte from '@astrojs/svelte';

export default defineConfig({
  integrations: [
    starlight({
      title: 'Strata',
      description: 'Durable, distributed entities for the JVM, on Apache Pekko.',
      customCss: ['@fontsource/ibm-plex-sans/400.css', '@fontsource/ibm-plex-sans/600.css', '@fontsource/ibm-plex-mono/400.css', './src/styles/theme.css'],
      social: [{ icon: 'github', label: 'GitHub', href: 'https://github.com/prabhugopal/strata' }],
      sidebar: [
        { label: 'Start', items: ['start/introduction', 'start/quick-start', 'start/shop'] },
        {
          label: 'Concepts',
          items: ['concepts/entities', 'concepts/the-seal', 'concepts/durable-invocations', 'concepts/other-entities',
            'concepts/timers', 'concepts/upgrades', 'concepts/clusters'],
        },
        { label: 'Guides', items: ['guides/services', 'guides/languages', 'guides/spring', 'guides/kubernetes', 'guides/stratum'] },
        { label: 'Reference', items: ['reference/configuration', 'reference/management-api', 'reference/cli', 'reference/annotations'] },
      ],
    }),
    svelte(),
  ],
});
