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
| Fixed counterexamples | `--suite fixtures --keep-going` | All 231 curated fixtures, three deterministic order permutations by default |
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
