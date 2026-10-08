# CGSE regression suites

Production sources live in `src/main/java`. Everything in this directory is test code,
test data, or test provenance. The stable suite needs only Python 3 and JDK 17:

```text
python tests/run.py --java-home <jdk17> --suite standard
```

The runner writes classes, logs and machine-readable results below `build/tests`.
Exit status **0** means every selected check passed, **1** means a failed assertion,
compilation/setup failure or malformed fixture, and **2** means an explicit unresolved
budget limit. A known SAT case remains SAT in the manifest when the current search
budget runs out. An unresolved result is never accepted as UNSAT or reported as PASS.

| Suite | Command / location | Coverage |
| --- | --- | --- |
| Stable core | `--suite core` | Compiler, planning, exact amounts, cancellation, retained requests, runtime, scheduling, pipeline, recovery, geometry and extracted services |
| Stable oracles | `--suite oracles` | 21 independent exhaustive/integer/BFS/seed/proof oracle programs |
| View | `--suite view` | Exact summary/ring data plus optional headless SVG/PNG exporter |
| Fixed counterexamples | `--suite fixtures --keep-going` | All 239 curated fixtures, three deterministic order permutations by default |
| Full graph corpus | `--suite corpus --keep-going` | All graph and count-model records listed in `cases/manifest.json` |
| Count models | `--suite models --keep-going` | 274 exact integer models, independent bounds/constraint witness verification |
| Planner/runtime benchmark | `--suite benchmarks --timeout 600` | Original `GraphBenchmark`: chain, shared diamond, coproducts, growth/cycles and runtime batch dispatch |
| Portable probes | `--suite probes --keep-going` | Self-contained historical programs with compatible current APIs; test-only external dependencies require `--classpath` |
| Complete captured datasets | `python tests/datasets/run.py --java-home <jdk17>` | Full AE inventory/manual patterns, original JEI exports, complete converted catalogs, and recorded request scenarios |
| AE/GTL adapter | `adapter/cgse-adapter.init.gradle` | Real AE keys, Minecraft NBT, routing, capture, persistence and machine adapter contracts |
| AEDUMP | `python tests/ae-dump/check_migration.py --java-home <jdk17>` | Archive format/validation plus extracted request-service handoff |
| Live adapter | `adapter/live/` | Forge/server probes requiring the actual host installation |

`standard` runs core, view and the 21 stable oracles. `all` also selects every fixed
fixture, portable probe and corpus case; it can take substantially longer and includes
known hard inputs. Benchmark/dataset/AE/live suites are explicit separate entries because their
resource requirements differ. Use `--case <regex>`, `--shard 0/4`, `--milliseconds`,
`--work`, and `--timeout` to control a repeatable selection and its budgets.

The full corpus preserves recipes in their input order, stock, exact quantities,
bindings, physical input slots, configuration/reusable flags, external supply and
request flags. Each canonical record has a content-derived ID, origin file/JSON
pointer, original names, oracle truth, seed and budget. Byte-identical source aliases
are recorded in `provenance/duplicates.json`; original archive members are listed in
`provenance/archive-entries.json`. Complete captures are gzip compressed with original
and compressed SHA-256 checksums in `datasets/index.json`.

## Controlled planning comparison

```text
python tests/benchmarks/compare_planning.py --java-home <jdk17> --baseline <commit> --case <regex> --work 20000000 --milliseconds 3000
```

The comparison archives baseline Java sources without modifying either checkout and
runs the same fixtures/permutations serially with one worker and identical memory
and time limits. `accounts` grants `--work` separately to search and compilation;
`total` additionally stops at their combined work limit. `wall` uses only the time
and memory limits. The aggregate cutoff is imposed between planner steps: any atomic
overshoot is recorded and cannot count as a completed witness. It is never an UNSAT
conclusion. Cold, warm and equivalent newly decoded recipe snapshots are separate
rows. A new snapshot is a new compiler; this does not simulate an adapter failing to
notice an in-place pattern edit.

`rows.json` records results/limit details, both work accounts, preparation/solve times,
request reservation peaks and outer candidate-validation observations when available.
`summary.json` includes p50/p95/p99 solve times. Older revisions without candidate
telemetry use `-1`, not an invented first-witness measurement. Memory figures exclude
persistent compiler caches and JVM heap/RSS. Benchmark completion means that returned
conclusions and executable prefixes passed their checks; inspect result distributions
for budget limits. It does not mean that all inputs were solved or that a speedup was
established.

## Known limits and historical material

The standard suite includes `LargeSourceSelectionTest` (9,002-source catalogs,
reopened stock boundaries, shuffled priority ties and interrupted selection),
`InterfaceSearchTest` (1,200 independent finite-domain oracle/order cases,
unbounded and 40-digit internal domains, scoped component memoization, proof
checks, cancellation and quota decline), and `CompilerCacheMetricsTest`
(eviction, nested compiler identity and 128 requests on four worker threads).
The interface search is a positive count neighborhood. Its local infeasibility
answers are scoped to a component and interface assignment; no negative answer
or UNKNOWN is exported as an infeasibility proof for the original model.
Composed counts still go through the global execution scheduler and validator.

`CountCutsetTest` checks all undirected graphs up to six vertices, live-vertex
subsets, random larger graphs and a 256-vertex chain against independent
delete-and-BFS connectivity (68,137 cases). Interface selection uses low-link
separator scores when its estimated scan cost beats repeated bitset traversals;
it preserves the original candidate domains and tie order.

`InterfaceContinuationTest` preserves four planted wide-domain regressions,
including three that need another local search round under a 200,000-work
neighborhood cap. A 192-input seed/order campaign separately reports valid
witnesses and unresolved cases; passing its safety assertions does not mean
all 192 were solved. Paused LCG frontiers are scoped by component and exact
interface values, visited once per round, and retained in a bounded cache.
Eviction discards work without asserting infeasibility; a restart receives a
larger local allowance, while a retained frontier gets another fair slice of
the existing request budget. The test also exercises memory-driven eviction,
cancellation with parked children, idempotent close and independent replay of
scoped certificates after continuation.
Four sparse stars (33 to 241 variables, wide internal counts and many eligible
Boolean separator vertices) must produce valid composed counts within a fixed
20,000-work neighborhood cap, including interface preparation.
Four conditional models also share one budget on four workers, exercising both
settings of the production work-budget multiplier without sharing model state.

`GraphCompiler.cacheMetrics()` observes all cache families and retained macro
compilers, deduplicating compilers by identity without retaining old snapshots
in a global registry. It reports logical weight, estimated bytes, hits/misses,
capacity evictions, and active `GraphPlanningWork` lifetimes (including nested
seed searches until close). Estimates can count shared structures in multiple
families and exclude arbitrary host key/binding objects: they are not a heap
census or a memory limit. A concurrent snapshot is not atomic across compilers.
The comparison harness records these separately from peak request reservations;
older revisions without the API report -1. Use `--fixture-dir` to compare a
generated fixture campaign; the input digest is checked at completion as well.
The source-selection stress families are reproducible without captured game data:

```text
python tests/benchmarks/generate_source_cases.py
python tests/benchmarks/compare_planning.py --java-home <jdk-directory> --baseline <commit> --fixture-dir build/source-selection-fixtures
```

These cover a reopened inventory boundary, demand shared by two downstream
recipes, and a seeded growth recipe. Both work accounts are reported; results
from this targeted family are not a claim of the same speedup on all recipes.

The root-portfolio regressions include first-visit fairness, restored reduced
views, small-divisor scaled searches, and retained LP/matching work. Run a larger
weighted-choice campaign with quantity and recipe-order variation:

```text
python tests/benchmarks/generate_choice_cases.py --seeds 8 --amounts 1,2,16,1000 --output build/choice-quantities
python tests/run.py --java-home <jdk17> --suite fixtures --fixture-dir build/choice-quantities --permutations 3 --work 20000000 --milliseconds 0 --keep-going --output build/choice-20m
python tests/run.py --java-home <jdk17> --suite fixtures --fixture-dir build/choice-quantities --permutations 3 --work 40000000 --milliseconds 0 --keep-going --output build/choice-40m
```

Each generated case includes a planted primitive witness, independently replayed
by the Python generator before writing. The fixture runner separately validates
the returned plan's complete execution prefix and fresh production. Three key
counterexamples are retained in `regression/fixtures/portfolio`; generated bulk
data stays under `build`. `RootPortfolioTest` also covers four-worker requests
with and without the production budget multiplier. `CountAggregationTest` checks
600 small models by exhaustive enumeration, replays the assumptions used by a
contradiction, and checks changed-stock/sibling reuse, beyond-long arithmetic,
scaled-search cancellation and reservation release. Restricted scaled failures
must not become infeasibility proofs for the original integer domain.

At `3b63a578`, the campaign used seeds 100 through 107, all five generator families,
three order permutations, and a 256 MiB request reservation limit. It returned
475 independently verified plans and five unresolved search limits at 20M work;
the same 480 requests all returned verified plans at 40M. On that revision the 20M
command exited with an inconclusive status. All five are known SAT cases, not
infeasibility results. This quantity campaign is separate from the 234 fixed
fixtures, which all passed at 20M across three permutations. Neither result
establishes performance or completeness on arbitrary recipe catalogs.

The retained-prefix follow-up solves all 480 original requests at 20M by restoring
parent partial sums instead of subtracting every coefficient on backtracking.
The additional depth snapshots are reserved against the existing memory limit.
The expanded campaign uses seeds 100 through 131 and amounts `1,2,3,16,1000`:
800 inputs across three order permutations, all 2,400 plans independently verified
at 20M with the same 256 MiB limit. `CountMatchingPrefixTest` adds 96 exhaustive
models, 1100-bit coefficients, partial-prefix rejection, cancellation and local
work/memory cutoffs; a cutoff must remain undecided.

`CountMatchingCoordinatesTest` independently enumerates 280 additional finite
models (181,982 assignments) plus signed boundary cases. Matching uses compact
`long` signatures only after bounding every prefix, suffix and complement
intermediate; wide coordinates and broad intervals retain BigInteger arithmetic.
All witnesses are checked against the original rows. The oracle covers negative
and 100-bit domain offsets, mixed signs, exact and ranged queries, values beyond
signed long, and 1100-bit coefficients. This optimization reduces table storage;
it does not increase the search or memory cap.

The five formerly unresolved input orders are preserved in
`regression/fixtures/retained-prefix`. Their metadata names the original generator
seed, campaign permutation and baseline. Ordinary fixture runs also shuffle these
cases. To reproduce their recorded order exactly and compare the prior revision:

```text
python tests/benchmarks/compare_planning.py --java-home <jdk17> --baseline 3b63a578 --fixture-dir tests/regression/fixtures/retained-prefix --preserve-order --permutations 1 --work 20000000 --milliseconds 0 --memory-mib 256
```

`--preserve-order` retains recipe order and input/output insertion order; its value
is recorded in the comparison summary. Repeated permutations with this flag are
repeated trials of the recorded order, not distinct shuffled inputs.

The arithmetic/propagation follow-up includes three independently checked suites:

- `ExactRationalRegressionTest`: 13,300 fraction pairs compared with full-product
  rational arithmetic, including signs, cancellation, division by zero and the
  2,048-bit precision boundary. Avoiding redundant reduction must preserve exact
  canonical values, floor/ceiling, comparison, equality and hashing.
- `CountLcgWatchTest`: 696 exhaustive signed-integer/Boolean models, 80-bit domain
  offsets, imported forbidden assignments, short retained slices and independent
  proof replay. An eight-pigeon/seven-hole instance exercises clause-pool pruning,
  restarts and backjumps; cancellation and memory-limit cases exercise cleanup.
  Its search limit remains 20M; independent replay of the long UNSAT certificate
  uses a separate 200M verification limit.
  The two watched terms subscribe to both tightening and rollback of a variable's
  bounds; a rollback can change a false integer-bound literal into an unknown one.
  Conflict analysis retains the earliest implication source for each strongest
  frontier bound within that analysis only. A stronger replacement resolves its
  own source; the cache is never read after backtracking or a restart. The same
  exhaustive/proof tests cover this reuse, including relaxed antecedents and
  cancellation while temporary frontier memory is reserved.
  A heap indexed by trail position selects the next implication without scanning
  the whole frontier after every resolution; learned-clause order is preserved.
  The additional 96 models include domains with 16 values, negative 80-bit
  offsets, and independent certificate replay for every model.
- `CountDispatchCancellationTest`: deterministic cancellation and clock deadlines
  at four conflict-sharing handoffs. A branch removed from the waiting frontier
  must already belong to the running wave before any interruptible snapshot.
  Every cutoff must release its request reservations.

A completed matching table can earn one short lookup tail at a count-search
handoff. Construction does not qualify, the tail cannot renew itself within a
turn, and the original request's work, time and memory limits still apply. The
recorded-order benchmark above exercises this boundary as well as the ordinary
shuffled fixture runs; report strict wall deadlines separately from work caps.

The historical LP learning oracle is also runnable explicitly with its test-only
JSON dependency (used by its shared verification helper):

```text
python tests/run.py --java-home <jdk17> --suite probes --case ProductionLearningSafety --classpath <gson.jar>
```

It compares 2,200 finite models to exhaustive enumeration and independently checks
learned-row certificates, including deliberately corrupted derivations.

At migration validation, all 2,944 selected finite/cover oracle inputs passed.
The count corpus produced 187 independently verified conclusions and 87 unresolved
budget limits. The fixed fixtures produced 657 verified results and 36 unresolved
results across 693 deterministic executions. These limits are recorded in
`known-limits.json`; their oracle truth has not been changed.

Historical programs are compiled in separate directories because several campaigns
used different implementations with the same Java class name. `probes/manifest.json`
distinguishes self-contained programs, programs requiring explicit input arguments,
and historical internal-API experiments. The audit compiled 610 of 671 initial probe
variants; four additional save-cycle programs compiled and executed afterward, and
a fifth was classified as an AE adapter probe. Of 256 self-contained programs executed, 229 passed, 25 retained old assertions failed,
and two exceeded their process budget. This is not a claim that all historical
assertions remain valid for the current implementation. Some deliberately require an
old bug to remain present. Per-program evidence and related current checks are in
`provenance/historical-coverage.json`. Six historical proof/reduction oracles were
also promoted to the stable suite with their assertions retained; the precise API
and artifact-path adaptations are in `provenance/promoted-oracles.json`.

To inspect/run an explicitly selected historical program:

```text
python tests/run.py --suite probes --include-historical --case <unique-probe-id> --argument <input> --classpath <optional-test-jars>
python tests/run.py --suite probes --compile-only --keep-going --classpath <optional-test-jars>
```

To compare the preserved count-search portfolios with canonical inputs:

```text
python tests/cases/export.py --output build/count-models.json
python tests/run.py --suite probes --include-historical --case CountBenchmark-8694b527fb78 --classpath <gson-jar> --argument build/count-models.json --argument build/count-results.jsonl --argument lcg-retained,view-retained,domain,quick
```

Historical benchmark process success means its assertions passed; inspect each
JSONL result for `SAT`, `UNSAT`, `UNKNOWN`, `LIMIT` or `ERROR`. The standard count
model suite (`--suite models`) separately returns exit status 2 for unresolved work.

Original independent Python oracle/model generators are preserved under
`oracles/reference`, with source-format inputs/certificates under `oracles/inputs`.
They are historical references and retain their original campaign paths; the formal
entries in the table above are self-contained in this repository. No production JAR
contains these sources or inputs. Optional solver coordinates are in
`dependencies.json`; native solver binaries are not vendored.

## Adapter entry

Run inside the GTL adapter checkout, after synchronizing the product sources:

```text
gradlew -I ../CGSE_CORE/tests/adapter/cgse-adapter.init.gradle "-PcgseRoot=../CGSE_CORE" cgseAdapterTest
```

Adapter production code remains in GTLCore; this repository retains the CGSE
integration tests and explicit host-dependent runners. The command creates a
separate `cgseAdapter` source set using `tests/adapter/java` and
`tests/shared/java`. Existing upstream test sources and tasks stay separate.

`provenance/migration-map.json` maps every original CGSE `src/test` Java file to
its migrated destinations. There were **20** original CGSE Java files: seven core
and thirteen adapter files. Splitting layout checks from AE packet checks yields
21 primary migrated files, plus a shared long-boundary fixture. Unrelated upstream
GTL tests are explicitly listed as retained in their original repository. Original
local reports, logs, obsolete production snapshots, environments and worlds remain
in the local preservation archive or their original location; they are not test
sources shipped in this repository.
