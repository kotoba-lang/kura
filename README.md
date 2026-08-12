# kura

蔵 — the decision layer of an erasure-coded storage network: where shards go,
when to rebuild them, how to prove a node still holds them, and what a node is
owed for holding them.

Design: [ADR-2607299200](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2607299200-kura-erasure-coded-storage-network.edn).
The code itself is [`kotoba-lang/erasure`](https://github.com/kotoba-lang/erasure);
this library never re-derives what is recoverable or what to read, it asks.

**This library moves no bytes.** Every function takes small values and returns
small values — indices, node sets, plans, verdicts. Disks, sockets and keys
belong to `kura-node`; the coordinator loop belongs to `kura-bugyo`. Keeping
the boundary sharp is what lets the placement arithmetic have a `.kotoba` port
held to equality rather than to plausibility.

## The five things it owns

### `kura.placement` — two levels, not one

```
object-id --(hash)--> placement group    ; a function, never stored
placement group --(table)--> node set    ; ~10^5 rows for a petabyte
```

Storing shard→node directly costs `objects × n` rows: 420 million for one
petabyte at 64 MiB objects and n=26. That does not fit the single ref the query
plane gives us, and splitting the ref is exactly what makes the repair
scheduler's central question — *node N died, which groups are below threshold?*
— unanswerable. Through groups it is a scan of ~10^5 rows.

Group → node set is rendezvous (highest-random-weight) hashing, so adding or
removing a node moves only the placements that node wins or loses. Weight is
virtual ids rather than a score multiplier: the usual weighted-HRW formula
needs a logarithm, and this has to stay integer arithmetic a `.kotoba` guest
reproduces exactly.

**Failure domains beat score.** Selection walks nodes by score and skips any
that would exceed a per-domain cap. This is the whole durability argument of
the ADR's section 1 — the independent-failure model that gets 1.625× to ten
nines is worth nothing if one rack or one operator can take out more shards
than the code tolerates. A group that cannot be filled under the caps is
reported **underfilled**, never quietly filled anyway.

`kura.repair/policy-headroom` is the honest check the caps themselves cannot
make: a cap above the code's tolerance means a single domain can destroy the
object.

### `kura.manifest` — and the range claim, as a test

The ADR's section 7 claims ~1.0× read amplification on a small range where a
whole-segment scheme pays ~64×, and attributes it to the code being
*systematic with directly readable data shards* — not to it being an LRC.
`range-reads` is that claim as code, and `manifest-test/amplification-is-one`
asserts it. Reads also name a `:hedge-group`, because a direct shard read
depends on one node and the tail latency has to be paid for somehow.

### `kura.repair` — grace, margin, order

A node that stopped answering thirty seconds ago has usually not lost anything.
Repairing on first miss turns a rolling restart into a network-wide rebuild —
the most expensive possible mistake, since rebuild traffic is the cost the
whole local-parity design exists to minimise. So a missing shard becomes a
repairable shard only after `:grace-seconds`; before that the group is
*degraded*, which is a different state with a different response.

`margin` is the **guaranteed** further-loss tolerance (`tolerated - |erased|`),
not the optimistic per-pattern figure. `erasure` measured that only 1,464 of
1,562,275 eight-shard patterns are fatal, so most losses beyond the guarantee
survive — scheduling on that would be betting a group's durability on which
specific shards die next. `exact-margin` computes the real number when a
diagnostic wants it.

### `kura.audit` — sampling, not local testability

The ADR discarded the high-dimensional-expander scheme for two reasons. Local
testability is a property of a word *you* hold; here the node holds it, and a
node that deleted data can answer a few queries by fetching from a peer. And a
proximity test does not localise the damage, so the Merkle layer it was meant
to replace is still needed.

What replaces it is boring and works:

```
q = ceil( ln(delta) / ln(1-f) )      f=1%, delta=1e-3  ->  688 challenges
```

688 × 64 KiB ≈ 44 MiB per round. A 10 TB node audited monthly reads 528 MiB a
year — 0.005% of capacity, against 1200% for a monthly full scrub. The scheme
that was discarded aimed at 90–99.9%; sampling gets **99.9996%** and, unlike
local testability, means something against a node that is trying to cheat.

The tree is a Merkle **sum** tree (`merkle-sum`, already in this workspace):
each leaf carries its shard's byte length, so the root is the volume the node
claims to hold. It cannot overstate holdings for payment, or understate them to
dodge an audit, without a leaf proof contradicting it.

Challenges are derived from an epoch seed published when the epoch opens — so a
node cannot know in advance which leaves to keep — by a pure function of that
seed, so any third party can recompute the set and check the coordinator did
not target a node unfairly.

### `kura.order` — capability and invoice in one

An order says *this* node may do *this* action on *this* shard, up to *this*
many bytes, until *this* time. The shape is lifted from
`storj.node.orders/admit`, already in this workspace, including the two
judgement calls that make it good:

- **Every reason, not the first** — `:ok?` for yes/no, `:reasons` for anyone
  debugging a coordinator integration.
- **Signature last, and skipped when the content is already bad** — not an
  optimisation: a node under a flood of malformed orders does no asymmetric
  crypto on any of them.

Orders are also the unit of payment. An admitted order is a claim on money, so
`settlement-leaf` folds honoured orders into a `merkle-sum` tree whose root
goes on chain: the coordinator cannot understate the epoch total without
contradicting some node's proof, or inflate one node's share without
contradicting the published root.

## The `.kotoba` port

Three modules under `kotoba/`, all `kotoba/pure` (no capabilities). **One of
them is what runs; two are second opinions.** That distinction is the whole
point of [ADR-2608112100](https://github.com/com-junkawasaki/root/blob/main/90-docs/adr/2608112100-a-kotoba-core-with-a-parity-test-is-not-done.edn):
a port bound to the `.cljc` by a parity test is two implementations, and the
measure of a port is whether the authority moved, not how many host lines went
away.

| module | what it holds | status |
|---|---|---|
| `order_core.kotoba` | the order **contract and admission** — `signing-bytes`, the settlement leaf id, the closed action set, the clock and limit checks, and `admit`'s rule that the signature check comes last and is skipped when the order is already rejected | **its judgements run.** Shipped as `resources/kura/oracle/order.kir.edn`, executed by `kura.kotoba-oracle`; gated by `kura.kotoba-oracle-test` (drift + delegation) and still by `kura.kotoba-decision-parity-test`. `signing-bytes` is the exception — see below |
| `kura_core.kotoba` | the placement **arithmetic** — mix32, FNV-1a, rendezvous scoring, `group-of`, the audit sample bound | second opinion, `kura.kotoba-parity-test` |
| `placement_core.kotoba` | the placement **decisions** — `group-name`, the domain and availability caps, and what a finished selection reports | second opinion, `kura.kotoba-decision-parity-test` |

Regenerate the shipped artifact with `clojure -M:test:gen`. The compiler stays
test-only — it produced the artifact and never reaches a consumer; the KIR
interpreter is the one runtime dependency the delegation added.

Two consequences of `order_core.kotoba`'s judgements running, rather than
being compared against:

- **A ClojureScript host must `kura.kotoba-oracle/register-kir!`** before
  `admit`, `within-limit?` or `settlement-leaf` will answer; there is no
  classpath to read the artifact from. `kura.cljs-runner` shows the one line.
- **`signing-bytes` stayed on the host, and the reason is measured rather than
  a preference.** It is the only export that formats an integer, so it is the
  only one that reaches the compiler-synthesised `__kotoba_string_from_i64`,
  whose body is a `string-substring`. At the pinned interpreter that guard is
  `(integer? start)` — false for the `js/BigInt` an `:i64` is under
  ClojureScript — so a delegated `signing-bytes` throws there and works on the
  JVM. The interpreter that fixes it comes only with a compiler that rejects
  this repo's own source (`string=?` returns `:bool` there, `:i64` here), so
  the fix is a language migration of all three cores and their parity tests,
  and it is a slice of its own. Delegating on one runtime only would put the
  rule back in two places on purpose.

  The divergence that leaves in place is pinned by
  `kura.kotoba-oracle-test/the-host-and-the-port-frame-non-ascii-differently`:
  the host counts UTF-16 units, the port counts UTF-8 bytes, and they disagree
  for a non-ASCII node-id, shard-id or nonce. ASCII — every id `kura.hash` will
  key on — agrees.

What stayed on the `.cljc` side is written down in each module's header: folds
over collections (`ranked`, `select`'s walk, `admit`'s reason vector), the
`assert`s (this profile has no `throw`, deliberately), and the nil guards
(there is no nil). Each port function takes already-resolved scalars and
returns one decision.

`placement_core.kotoba` clears `kotoba.kir/only-native-word-typed-features?`,
the admission gate behind the x86-64 and aarch64 AOT backends;
`order_core.kotoba` does not, because `signing-bytes` needs a record with
`:string` fields and native records are restricted to `#{:i64 :bool}`. Both
facts are asserted by running the gate, not claimed.

Placement is the one thing here that cannot take its arithmetic by injection: a
client, a coordinator and an auditor must independently compute the same node
set, or the network has no shared notion of where anything lives. A pluggable
hash would make placement a matter of configuration agreement, and disagreement
would surface as data that is simultaneously present and missing.

32-bit and not 64-bit because the compile path has `i32-wrapping-mul` but no
wrapping 64-bit multiply. Ample here — it is a dispersion function, not a
security primitive.

`kura.hash` is also the one namespace where Clojure and ClojureScript genuinely
disagree (`bit-and` returns a signed int in cljs; longs do not overflow at 32
bits in clj, and `*` throws when they would), so every operation normalises
through `u32`. All three implementations agree: `key32 "pg-0" = 3505442236`.

## Tests

```bash
clojure -M:test                                        # JVM, includes the parity gate
clojure -M:cljs -m cljs.main --target node -m kura.cljs-runner
clojure -M:lint
```

## Dependencies

`erasure`, `merkle-sum` and `kotoba-kir` at runtime — the first two reuse
rather than reinvention, the third because `kotoba/order_core.kotoba` is what
`kura.order` executes. `kotoba-lang/compiler` is test-only: it produces the
shipped KIR (`clojure -M:test:gen`) and drives the parity gates, and never
reaches a consumer.

## License

MIT.
