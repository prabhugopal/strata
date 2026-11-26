<script lang="ts">
  // A command interrupted by a crash, step by step: the code, what's recorded (as layers), the
  // nodes, and the outside world. Recorded layers are read back on the next node, so the charge
  // isn't made twice.
  type Layer = { kind: 'head' | 'call' | 'ctx'; text: string };
  type Step = {
    line: number | null;        // highlighted code line (1-based)
    node: 'a' | 'b';
    down?: boolean;             // node A has crashed
    layers: Layer[];
    replaying?: number;         // index of the layer being read back
    charges: number;
    emails: number;
    state: string;
    note: string;
  };

  const code = [
    '@Command public String checkout(long cents) {',
    '    String r = payments.charge("order", cents);',
    '    paidAt = ctx.now();',
    '    mailer.send("Thanks! Receipt " + r);',
    '    status = "paid";',
    '    return r;',
    '}',
  ];

  const head: Layer = { kind: 'head', text: 'checkout(4200) started' };
  const charge: Layer = { kind: 'call', text: 'Payments.charge → "R-4200-1"' };
  const now: Layer = { kind: 'ctx', text: 'ctx.now → 10:42:07' };
  const mail: Layer = { kind: 'call', text: 'Mailer.send → ok' };

  const steps: Step[] = [
    { line: 1, node: 'a', layers: [], charges: 0, emails: 0, state: 'new', note: 'A request arrives at node A. The order is a sealed entity: it can only reach the outside world through its injected services.' },
    { line: 1, node: 'a', layers: [head], charges: 0, emails: 0, state: 'new', note: 'Before running, Strata records that checkout(4200) is starting. That record is what makes it resumable.' },
    { line: 2, node: 'a', layers: [head, charge], charges: 1, emails: 0, state: 'new', note: 'The card is charged. Its result is stored before the code continues, so this effect is never repeated.' },
    { line: 3, node: 'a', layers: [head, charge, now], charges: 1, emails: 0, state: 'new', note: 'Even the time is recorded: a resumed command must see the same clock it saw before.' },
    { line: 4, node: 'a', down: true, layers: [head, charge, now], charges: 1, emails: 0, state: 'new', note: 'Node A dies while sending the email. The caller gets an error; nothing was committed. The record survives in the store.' },
    { line: 1, node: 'b', down: true, layers: [head, charge, now], charges: 1, emails: 0, state: 'new', note: 'Node B takes over the order. Loading it, B finds the unfinished checkout and runs it again from the top.' },
    { line: 2, node: 'b', down: true, layers: [head, charge, now], replaying: 1, charges: 1, emails: 0, state: 'new', note: 'payments.charge is answered from the record: same receipt, and the card is not charged again.' },
    { line: 3, node: 'b', down: true, layers: [head, charge, now], replaying: 2, charges: 1, emails: 0, state: 'new', note: 'ctx.now returns the recorded time, 10:42:07, not the time on node B.' },
    { line: 4, node: 'b', down: true, layers: [head, charge, now, mail], charges: 1, emails: 1, state: 'new', note: 'Past the end of the record, calls run for real: the email goes out, once, and is recorded too.' },
    { line: 5, node: 'b', down: true, layers: [], charges: 1, emails: 1, state: 'paid', note: 'One transaction commits the new state and clears the record. One charge, one email, no code written for it.' },
  ];

  // Steps can be linked: ?step=7 opens the walkthrough at step 7.
  const linked = typeof window !== 'undefined' ? Number(new URLSearchParams(window.location.search).get('step')) : 0;
  let i = $state(linked >= 1 && linked <= steps.length ? linked - 1 : 0);
  let playing = $state(false);
  const s = $derived(steps[i]);
  const reduced = typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  $effect(() => {
    if (!playing) return;
    const t = setTimeout(() => (i < steps.length - 1 ? i++ : (playing = false)), 2600);
    return () => clearTimeout(t);
  });

  function key(e: KeyboardEvent) {
    if (e.key === 'ArrowRight' && i < steps.length - 1) { i++; playing = false; }
    if (e.key === 'ArrowLeft' && i > 0) { i--; playing = false; }
  }
</script>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<figure class="cr not-content" class:reduced tabindex="0" onkeydown={key} aria-label="A command resumed after a crash, step by step">
  <div class="grid">
    <section class="code" aria-label="Entity code">
      <h4>Order.java</h4>
      <pre>{#each code as line, n}<span class:hl={s.line === n + 1} class:replay={s.replaying != null && s.line === n + 1}>{line}
</span>{/each}</pre>
    </section>

    <section class="nodes" aria-label="Nodes">
      <h4>Cluster</h4>
      <div class="node" class:active={s.node === 'a' && !s.down} class:dead={s.down}>
        <b>node A</b><span>{s.down ? 'stopped' : s.node === 'a' ? 'running checkout' : 'idle'}</span>
      </div>
      <div class="node" class:active={s.node === 'b'}>
        <b>node B</b><span>{s.node === 'b' ? (i === steps.length - 1 ? 'committed' : 'resuming checkout') : 'idle'}</span>
      </div>
      <dl class="world" aria-label="The outside world">
        <dt>Cards charged</dt><dd class:warn={s.charges > 1}>{s.charges}</dd>
        <dt>Emails sent</dt><dd>{s.emails}</dd>
        <dt>Order status</dt><dd>{s.state}</dd>
      </dl>
    </section>

    <section class="record" aria-label="The record">
      <h4>Recorded <small>(in the store)</small></h4>
      <ol class="layers">
        {#each [...s.layers].reverse() as layer, k (layer.text)}
          {@const idx = s.layers.length - 1 - k}
          <li class="layer {layer.kind}" class:reading={s.replaying === idx}>{layer.text}</li>
        {:else}
          <li class="empty">{i === steps.length - 1 ? 'cleared by the commit' : 'nothing yet'}</li>
        {/each}
      </ol>
    </section>
  </div>

  <figcaption>
    <p class="note" aria-live="polite"><span class="count">{i + 1}/{steps.length}</span> {s.note}</p>
    <div class="controls">
      <button onclick={() => { i = Math.max(0, i - 1); playing = false; }} disabled={i === 0}>Back</button>
      <button onclick={() => { if (i === steps.length - 1) i = 0; playing = !playing; }}>{playing ? 'Pause' : i === steps.length - 1 ? 'Replay' : 'Play'}</button>
      <button onclick={() => { i = Math.min(steps.length - 1, i + 1); playing = false; }} disabled={i === steps.length - 1}>Next</button>
    </div>
  </figcaption>
</figure>

<style>
  .cr { container-type: inline-size; margin: 1.5rem 0; border: 1px solid var(--sl-color-gray-5); border-radius: 10px; padding: 1rem; background: var(--sl-color-black); outline-offset: 3px; }
  /* Sized by the space it's given, not the window: three columns when wide, code on top when not. */
  .grid { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); grid-template-areas: "code code" "nodes record"; gap: 1rem; }
  .code { grid-area: code; } .nodes { grid-area: nodes; } .record { grid-area: record; }
  @container (min-width: 980px) { .grid { grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr) minmax(0, 1.2fr); grid-template-areas: "code nodes record"; } }
  @container (max-width: 520px) { .grid { grid-template-columns: 1fr; grid-template-areas: "code" "nodes" "record"; } }
  h4 { margin: 0 0 .5rem; font-size: .8rem; font-weight: 600; color: var(--sl-color-gray-3); }
  h4 small { font-weight: 400; }
  pre { margin: 0; padding: .6rem; font-size: .78rem; line-height: 1.6; background: var(--sl-color-gray-6); border-radius: 6px; overflow-x: auto; }
  pre span { display: block; padding: 0 .3rem; border-radius: 3px; transition: background .3s; }
  pre span.hl { background: color-mix(in srgb, var(--sl-color-accent) 28%, transparent); }
  pre span.replay { background: color-mix(in srgb, var(--layer-ctx) 30%, transparent); }
  .node b { white-space: nowrap; }
  .node { display: flex; justify-content: space-between; gap: .5rem; padding: .5rem .7rem; margin-bottom: .5rem; border: 1px solid var(--sl-color-gray-5); border-radius: 6px; font-size: .82rem; transition: border-color .3s, opacity .3s; }
  .node span { color: var(--sl-color-gray-3); }
  .node.active { border-color: var(--sl-color-accent); }
  .node.dead { opacity: .45; border-style: dashed; border-color: var(--layer-fail); }
  .world { display: grid; grid-template-columns: 1fr auto; gap: .25rem .75rem; margin: 1rem 0 0; font-size: .82rem; }
  .world dt { color: var(--sl-color-gray-3); margin: 0; }
  .world dd { margin: 0; font-weight: 600; font-variant-numeric: tabular-nums; }
  .world dd.warn { color: var(--layer-fail); }
  .layers { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 4px; min-height: 9rem; justify-content: flex-end; }
  .layer { padding: .35rem .6rem; border-radius: 4px; font-family: var(--sl-font-mono); font-size: .74rem; color: #0b1512;
           animation: settle .5s ease-out; transition: box-shadow .3s, transform .3s; margin: 0; }
  .layer.head { background: var(--layer-head); }
  .layer.call { background: var(--layer-call); }
  .layer.ctx { background: var(--layer-ctx); color: #fff; }
  .layer.reading { box-shadow: 0 0 0 2px var(--sl-color-white); transform: translateX(6px); }
  .empty { color: var(--sl-color-gray-3); font-size: .8rem; padding: .35rem 0; }
  @keyframes settle { from { transform: translateY(-14px); opacity: 0; } to { transform: none; opacity: 1; } }
  .reduced .layer, .reduced pre span, .reduced .node { animation: none; transition: none; }
  figcaption { display: flex; gap: 1rem; align-items: flex-start; justify-content: space-between; margin-top: 1rem; flex-wrap: wrap; }
  .note { margin: 0; flex: 1 1 24rem; font-size: .9rem; min-height: 3em; }
  .count { color: var(--sl-color-gray-3); font-variant-numeric: tabular-nums; margin-right: .4rem; }
  .controls { display: flex; gap: .4rem; align-items: center; }
  .controls > button { margin: 0; }
  button { font: inherit; font-size: .85rem; padding: .35rem .8rem; border-radius: 6px; border: 1px solid var(--sl-color-gray-4); background: var(--sl-color-gray-6); color: var(--sl-color-white); cursor: pointer; }
  button:hover:not(:disabled) { border-color: var(--sl-color-accent); }
  button:disabled { opacity: .4; cursor: default; }
  button:focus-visible { outline: 2px solid var(--sl-color-accent); outline-offset: 2px; }
</style>
