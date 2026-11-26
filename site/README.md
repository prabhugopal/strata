# Strata documentation site

Astro + Starlight, with interactive explanations as Svelte components (`src/components/`):
`CrashResume` (a command resumed after a crash, step by step; `?step=N` links to a step) and
`Failover` (entities moving between nodes).

The configuration and CLI reference pages are generated from the source by
`scripts/reference.mjs` (each module's `reference.conf`, and `strata --help` when the CLI is
built), before every `dev` and `build`. Don't edit them by hand.

```sh
npm install
npm run dev        # http://localhost:4321
npm run build      # static site in dist/
```
