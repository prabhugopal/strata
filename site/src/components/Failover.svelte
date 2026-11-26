<script lang="ts">
  // Entities spread over nodes by shard; stop a node and its entities move to the others,
  // loading their committed state from the store. Click a node to stop or start it.
  const NODES = ['node 1', 'node 2', 'node 3'];
  const SHARDS = 12;
  const entities = Array.from({ length: 30 }, (_, k) => ({ id: `order-${k + 1}`, shard: (k * 7 + 3) % SHARDS }));

  let up = $state([true, true, true]);
  let moved = $state(new Set<string>());

  /** Shards go round-robin over the nodes that are up (sharding keeps placement stable; this is a simplification). */
  const owner = $derived.by(() => {
    const alive = NODES.map((_, n) => n).filter(n => up[n]);
    return Array.from({ length: SHARDS }, (_, s) => (alive.length ? alive[s % alive.length] : -1));
  });

  function toggle(n: number) {
    if (up[n] && up.filter(Boolean).length === 1) return; // keep one node
    const before = entities.map(e => owner[e.shard]);
    up[n] = !up[n];
    queueMicrotask(() => { moved = new Set(entities.filter((e, k) => owner[e.shard] !== before[k]).map(e => e.id)); });
  }
</script>

<figure class="fo not-content" aria-label="Entities moving between nodes">
  <div class="nodes">
    {#each NODES as name, n}
      {@const mine = entities.filter(e => owner[e.shard] === n)}
      <button class="node" class:down={!up[n]} onclick={() => toggle(n)} aria-pressed={!up[n]}
        title={up[n] ? `Stop ${name}` : `Start ${name}`}>
        <span class="head"><b>{name}</b><small>{up[n] ? `${mine.length} entities · click to stop` : 'stopped · click to start'}</small></span>
        <span class="dots">
          {#each mine as e (e.id)}<i class:moved={moved.has(e.id)} title={e.id}></i>{/each}
        </span>
      </button>
    {/each}
  </div>
  <figcaption>
    {#if moved.size}
      <b>{moved.size} entities moved.</b> Each one loads its committed state from the store on its new node, and any command it was in the middle of resumes there.
    {:else}
      Each entity lives on exactly one node. Stop a node to see what happens to its entities.
    {/if}
  </figcaption>
</figure>

<style>
  .fo { margin: 1.5rem 0; border: 1px solid var(--sl-color-gray-5); border-radius: 10px; padding: 1rem; background: var(--sl-color-black); }
  .nodes { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .75rem; }
  @media (max-width: 700px) { .nodes { grid-template-columns: 1fr; } }
  .node { text-align: left; font: inherit; color: inherit; background: var(--sl-color-gray-6); border: 1px solid var(--sl-color-gray-5); border-radius: 8px; padding: .7rem; cursor: pointer; min-height: 8.5rem; display: flex; flex-direction: column; gap: .6rem; }
  .node:hover { border-color: var(--sl-color-accent); }
  .node:focus-visible { outline: 2px solid var(--sl-color-accent); outline-offset: 2px; }
  .node.down { opacity: .5; border-style: dashed; }
  .head { display: flex; flex-direction: column; gap: .1rem; font-size: .85rem; }
  .head small { color: var(--sl-color-gray-3); }
  .dots { display: flex; flex-wrap: wrap; gap: 5px; }
  .dots i { width: 12px; height: 12px; border-radius: 3px; background: var(--layer-call); animation: arrive .45s ease-out; }
  .dots i.moved { background: var(--layer-effect); }
  @keyframes arrive { from { transform: scale(.3); opacity: 0; } to { transform: none; opacity: 1; } }
  @media (prefers-reduced-motion: reduce) { .dots i { animation: none; } }
  figcaption { margin-top: .8rem; font-size: .9rem; }
</style>
