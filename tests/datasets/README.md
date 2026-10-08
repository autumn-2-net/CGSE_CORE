# Complete captured datasets

This directory preserves complete source data for the October 2 native network,
linear/pruned search and quantity-regression investigations, October 7 mixed
AE/JEI stress run, and catalog-reference scenarios found across other campaigns.
`index.json` contains the current byte counts and source totals. Large files are
not truncated or sampled: the archive includes full raw JEI exports, complete
inventory/catalog inputs and all identified stock/request scenario files.

`index.json` maps every original path to its compressed object, original byte
length and SHA-256, and compressed byte length and SHA-256. Duplicate original
files share one object. Gzip headers use an empty filename and `mtime=0`; JSON
inside is preserved byte-for-byte, including original formatting and keys.

The import includes every JSON/JSONL at the roots of the seven named capture
campaigns in `import_sources.py`, every JSON/JSONL under
`server/local`, `configured-repro` and `cases`, and the original interpretation
scripts. This includes:

- `mixed-jei-stress-20261007/manual.json`: all 5,903 captured AE recipes, 2,670
  stock keys, original producer order, target list and catalyst settings.
- `extra.json`, `extra-configured.json`, `extra-physical.json` and
  `extra-virtual.json`: all four complete JEI interpretation profiles.
- Both `server/local/recipe-export*/recipes.jsonl` raw exports, including
  serializers excluded from executable conversion.
- `miracle-cycle-20261002/native_network.json`, `patterns.json`, complete
  catalog models, all campaign and server-local snapshots, and native audit
  inputs/results.
- Every `linear-search` scenario including the 51 MB `prior-cases.json`; all
  `pruned-search/archive-cuts-0..4.json` and the 94 MB `archived-baseline.json`;
  all quantity groups including `deep-diagnosis.json` and `deep-native.json`.
- All discovered catalog-reference inputs in the feedback, startup, expansion,
  stability and other campaigns, plus their original generators and drivers.
- Complete large block-entity/network captures and JDK profiling sample exports.
  These raw artifacts are preserved separately from executable request cases.

Run integrity verification without Java or the original checkout:

```text
python tests/datasets/run.py --verify-only
```

Named positive regressions retain the complete captures and set explicit work
caps in `regressions.json`. An unresolved result fails these campaigns; it is
not converted into a mathematical infeasibility result. Run them with:

```text
python tests/datasets/regressions.py --java-home <jdk-directory>
python tests/datasets/regressions.py --java-home <jdk-directory> --workers 4 --expanded-budget --output build/captured-parallel
```

`emitter-residual-progress` covers 1, 100 and 1,000 MAX emitters in manual,
JEI-first and randomized producer orders. It exercises the quantitative stock
view earning additional turns after observed refinement. New borrowed turns
stop once that family's charged work catches up with the original family;
atomic steps can overshoot this soft scheduling boundary. This is a headless replay of real
captured inventory and all 68,187 recipes, not a live Minecraft server test.
The multi-worker mode uses the production retained-work scheduler, including
compiler forks. `--expanded-budget` applies `PlanningBudget.parallelWorkLimit`;
results record the effective cap and peak active workers. Search and compilation
keep separate accounts and share each account across the workers. This does not
multiply the allowance independently for each worker.

Run complete hand-authored and deterministically shuffled mixed catalogs using
Python 3 and JDK 17, without Minecraft, Gradle or third-party libraries:

```text
python tests/datasets/run.py --java-home <jdk-directory>
python tests/datasets/run.py --java-home <jdk-directory> --modes manual,manual-first,extra-first,random --amounts 1,1000
python tests/datasets/run.py --java-home <jdk-directory> --modes random --seed 73 --random-targets 20 --amounts 1,1000
```

The default replay loads the entire captured AE catalog and entire virtual JEI
catalog: 68,187 recipes, 196,921 slots, including all 22,067 reusable slots. It
tests iron ingots and miracle crystals in quantities 1 and 1,000. A target
selector such as `--target gtlcore:miracle_crystal` must resolve to one exact
captured key. `--source` selects another executable snapshot by its original
path below `core-ae-cycle/.local/`:

```text
python tests/datasets/run.py --java-home <jdk-directory> --source miracle-cycle-20261002/both_fixed_snapshot.json --modes manual --amounts 1,1000
```

Replay preserves every input slot's key, amount, original index, configuration
and reusable flags; every output and binding; captured stock and external
resources; producer order; force-craft and seed preservation settings; and
catalyst parallelism/extra-copy limits. Python transfers these exact values to a
small binary format, and `DatasetReplay.java` constructs the new
`org.cgse.core` types. No slot aggregation or catalyst removal occurs in replay.

The four JEI profiles have different original semantics. `extra-virtual.json`
uses captured virtual ingredient keys for reusable inputs; `extra.json` uses
abstract reusable physical resources; `extra-physical.json` represents ordinary
input/output catalyst loops; `extra-configured.json` assumes tools are already
installed in machines and therefore omits those slots in its original model.
Choose with `--extra`; the replay does not convert one profile into another.
The archived conversion reports document excluded dynamic serializers,
guaranteed-output assumptions and environmental conditions. Raw unadapted
recipes remain preserved, but are not silently treated as executable recipes.

Results go to `build/datasets` unless `--output` selects another directory:
`run.json` records source hashes, slot counts, requests, seed and budgets;
`results.jsonl` records each solver result and verification status; and
`summary.json` keeps verified plans separate from inconclusive outcomes.
Every feasible result passes `PlanVerifier`, runtime-inventory verification and
an exact check that the plan does not overdraw captured stock. The expected
truth is `UNKNOWN` unless `--require-feasible` explicitly requires `SAT`.
`TIMEOUT`, `SEARCH_LIMIT`, missing previews and other unresolved outcomes remain
`INCONCLUSIVE`; they are never reported as an infeasibility proof. Process
success means the replay completed with no invalid plans or violated explicit
expectations, not that every requested plan was resolved.

`scenarios.json` indexes 55 original catalog-reference input paths containing
37,112 groups and 156,033 requests (including repeated historical variants).
It records the source SHA, JSON container, request counts, native-order status,
and exact catalog association with preserved driver/generator evidence. The
scan covers all `.local` JSON files regardless of file size; `large_json_audit`
lists every file above 32 MiB and confirms its lossless preservation.

Replay these groups independently of the original checkout:

```text
python tests/datasets/scenarios.py --list
python tests/datasets/scenarios.py --java-home <jdk-directory> --source linear-search-20261002/all-linear.json --limit-groups 3 --assert-known-feasible
python tests/datasets/scenarios.py --java-home <jdk-directory> --source quantity-regression-20261002/linear.json --limit-groups 2 --assert-known-feasible
python tests/datasets/scenarios.py --java-home <jdk-directory> --source pruned-search-20261002/archive-cuts-0.json --limit-groups 2 --assert-known-feasible
python tests/datasets/scenarios.py --java-home <jdk-directory> --source quantity-regression-20261002/deep-native.json --limit-groups 1 --request-limit 2 --milliseconds 3000
```

`--group` selects an exact original ID and is repeatable. Without it, the first
three groups are selected; `--limit-groups 0` selects all groups. All requests
within selected groups run in their original order unless `--request-limit`
explicitly selects a prefix. Every group gets a warm compiler, and each request
marked `cold` gets a fresh compiler. Thus a failed maximal request precedes the
smaller warm requests exactly as in the archived `QuantitySweep` driver.

The replay uses `group.catalog` as a complete replacement when present. Only
groups without a catalog use the original `audit-original-catalog.json` linked
by their generator or runner. Group stock replaces catalog stock, including an
explicit empty map. Producer order and explicit empty producer maps remain
distinct from an omitted map. Catalyst policy, slot flags, binding IDs, group
budgets and optional fallback diagnostics are retained. `QuantitySweep` passed
`preserve_seeds=true` and `force_craft=true`; this entry preserves those settings.
Fallback retains its separate 250 ms / 131,072 work / 16 MiB budget and never
replaces the primary result. Feasible primary and fallback plans both undergo
exact stock and runtime-inventory verification. Fresh compilation also runs
`DatasetTransportTest` to guard slot and catalyst serialization.

Use `--milliseconds`, `--work` or `--memory-mib` only for deliberate budget
overrides, which are recorded in `run.json`. Expectations stay `UNKNOWN` by
default; `--assert-known-feasible` enables the original `known_feasible_max`
assertion for quantities within that recorded range. Native-order specifications
are archived with their original AE drivers, but this standalone entry refuses
to substitute a guessed catalog for live provider-order behavior. Ordinary
network cases replay the preserved compiled snapshot; live AE capture hooks and
host-only probe diagnostics remain the responsibility of their archived drivers.

Representative new-entry runs verified 18 feasible plans among 21 linear
requests, 22 among 78 external-catalog quantity requests, all 6 archive-cut
requests, and one of two deep-native requests. Remaining oversized requests
were explicitly inconclusive; all enabled known-feasible assertions passed.
The complete-catalog runner also verified manual and random JEI modes after the
transport update. Run artifacts are written under `build/` and are not fixtures.

To regenerate the archive from the original checkout, preserving original files:

```text
python tests/datasets/import_sources.py <path-to-core-ae-cycle>
```

Compression is reproducible with the same Python/zlib version. Original content
hashes remain the source-of-truth across compressor versions.
