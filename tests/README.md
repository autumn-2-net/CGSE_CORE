# CGSE regression suites

Production sources live in `src/main/java`. Everything in this directory is test code,
test data, or test provenance. The stable suite needs only Python 3 and JDK 17:

```text
python tests/run.py --java-home <jdk17> --suite standard --timeout 600
```

The runner writes classes, logs and machine-readable results below `build/tests`.
The 600-second process timeout allows large historical batches to finish on four
logical CPUs; it does not change their per-request work limits.
Exit status **0** means every selected check passed, **1** means a failed assertion,
compilation/setup failure or malformed fixture, and **2** means an explicit unresolved
budget limit. A known SAT case remains SAT in the manifest when the current search
budget runs out. An unresolved result is never accepted as UNSAT or reported as PASS.

| Suite | Command / location | Coverage |
| --- | --- | --- |
| Stable core | `--suite core` | Compiler, planning, exact amounts, cancellation, retained requests, runtime, scheduling, pipeline, recovery, geometry and extracted services |
| Stable oracles | `--suite oracles` | 25 independent oracle programs, 61 pinned count-model boundaries and one archived graph-order boundary at explicit work caps |
| View | `--suite view` | Exact summary/ring data plus optional headless SVG/PNG exporter |
| Fixed counterexamples | `--suite fixtures --keep-going` | All 247 curated fixtures, three deterministic order permutations by default |
| Full graph corpus | `--suite corpus --keep-going` | All graph and count-model records listed in `cases/manifest.json` |
| Count models | `--suite models --keep-going` | 274 exact integer models; historical first LCG allowance by default, explicit retained/view/main-count entries |
| Planner/runtime benchmark | `--suite benchmarks --timeout 600` | Original `GraphBenchmark`: chain, shared diamond, coproducts, growth/cycles and runtime batch dispatch |
| Portable probes | `--suite probes --keep-going` | Self-contained historical programs with compatible current APIs; test-only external dependencies require `--classpath` |
| Complete captured datasets | `python tests/datasets/run.py --java-home <jdk17>` | Full AE inventory/manual patterns, original JEI exports, complete converted catalogs, and recorded request scenarios |
| AE/GTL adapter | `adapter/cgse-adapter.init.gradle` | Real AE keys, Minecraft NBT, routing, capture, persistence and machine adapter contracts |
| AEDUMP | `python tests/ae-dump/check_migration.py --java-home <jdk17>` | Archive format/validation plus extracted request-service handoff |
| Live adapter | `adapter/live/` | Forge/server probes requiring the actual host installation |

`standard` runs core, view, the stable oracles and the pinned count/graph/execution boundaries. `all` also selects every fixed
fixture, portable probe and corpus case; it can take substantially longer and includes
known hard inputs. Benchmark/dataset/AE/live suites are explicit separate entries because their
resource requirements differ. Use `--case <regex>`, `--shard 0/4`, `--milliseconds`,
`--work`, and `--timeout` to control a repeatable selection and its budgets.

## Maintained historical tests

`RequestCoverageTest` keeps incomplete candidate/state domains distinct from
complete infeasibility, including retained smaller requests and direct emission.
`CapturedReachableTest` checks sparse executable witnesses beyond the initial
concrete window. `CapturedStateTest` checks symbolic tool capacity, separate
physical ownership, mixed worn tools, replacement-tool production and bounded
restoration of only the selected uses. Their auxiliary failures are not proofs
about unexpanded state domains.

`CapturedStateRestoreTest` guards exact transition identity and compact serial
witnesses up to one billion fixed-state uses. It checks entity multiplicity and
replacement-tool production, mixed/indivisible slots and order changes, ordinary
returns, consumed/reusable configuration inputs, physical seed preservation and
restoration cost attribution. The production-restored plan runs through actual
return gating and saved runtime state; a compressed count cannot authorize
concurrent use of a single tool. Periodic changing states retain their exact word.

`StateEntityAllocationTest` compares simultaneous tool allocation with 28,002
independent small oracle cases, including indivisible backtracking, very large
entity counts, retained depth and cancellation. `CapturedStateEntityTest` covers
mixed/indivisible slot competition, repeated wear, manufactured replacements and
early activation of already owned tools across stock/slot/recipe permutations.
It replays each exact transition against the original captured source and checks
runtime return routing through save/load, local work limits and incomplete coverage.

`StateEntityMatchingTest` checks 56,798 finite allocation cases against an
independent enumerator, with different slot eligibility, whole-key branching and
integral augmenting paths, very large quantities, memory limits and cancellation.
`CapturedStateSharingTest` covers shared tools under different wear rules,
simultaneous matching after wear, manufactured replacements, disjoint eligibility,
physical seed requirements and request-level incomplete coverage. It replays the
original source rules and checks delayed actual returns through saved runtime state.

`StateRestorationCycleTest` compares 22,000 ordinary-resource prefix cases with
primitive execution, including read arcs, consumed configurations, external
supply, colliding state hashes, ordered pool identity, wide counts and cleanup.
It also compares serial dispatch repetition and nested word composition with
independent primitive execution, including consumed configuration requirements.
`CapturedStateCycleTest` checks observed periodic restoration through billion-use
and long-sized requests, including intermediate counts larger than long. It
preserves multiple physical entities, cross-rule matching, finite prefixes,
binding changes, exact final seeds, replacement attrition, cancellation and
request coverage. Production-restored words also run with delayed actual returns
and save/load. Closed observed words do not prove an entire state family complete.

`CountScheduleWordTest` checks 4,000 random executed words with independent
primitive inventory replay, maximal safe repeat counts and shared read arcs.
Fifty-four ring schedules cover recipe permutations, startup activations and
counts above long. Finite supply, missing startup stock, rejected acceleration,
cancellation, memory refusal and a nonrenewable work allowance are checked too.
The original scan remains available if acceleration fails or assembly rejects
its candidate. Shared-rule state loops additionally run at a 400,000 request
budget as well as the unchanged 40M setting; exact source and runtime verification
still follows every accepted candidate.

`CapturedStateOrderTest` covers execution-order recovery for two, three and five
rules sharing physical tools. Recipe/slot/stock permutations, simultaneous tools,
quantities up to `Long.MAX_VALUE`, exact final seeds, forced production and
replacement manufacturing are checked against source semantics and primitive
execution. An ordinary gate can make every ordering impossible; restricted
failure must remain inconclusive. Restored programs also pass delayed actual
returns, save/load and cancellation checks. `StateScheduleRepairTest` replays
alternative words for 250 independently constructed executable multisets, checks
counts above long, and verifies nonrenewable downstream-work and memory limits.

`CapturedStateChoiceTest` covers 97 positive cases where material dependencies
fix the execution order but eligible tools are not interchangeable across future
rules. It checks multiple specialized tools, simultaneous mixed/indivisible and
cross-rule slots, quantities through `Long.MAX_VALUE`, exact seeds, forced
production, independent physical replay, delayed returns and save/load. Conflicting
preferences and low budgets cannot invent tools or export an infeasibility proof.
`StateChoiceRepairTest` compares preferred allocation and matching with independent
literal assignments; preference scores must never remove feasible choices. It
also exercises wide quantities, score-cache revisions and lifetime work limits.

`CapturedStateSupportTest` compares retained source selection with 1,200
independent full-scan finite closures. It checks borrowed-index ownership,
original entry order, retained work, cancellation and memory/source limits.
`CapturedStateSupportPipelineTest` adds 30 positive requests with up to 16,384
irrelevant sources, separate state rules, reversed source order, simultaneous
mixed/indivisible tools and quantities through `Long.MAX_VALUE`. Manufactured
non-prototype tools, return-only bridges and independent required seed producers
must survive selection. Physical replay and delayed runtime returns/save-load
verify plans; selected failures must resume the original attempt, preserve
incomplete coverage and share its existing lifetime budget.

`CapturedStateExpansionTest` covers 44 positive requests with omitted cross-rule
state candidates. Four- and eight-state cycles, source/slot/stock permutations,
quantities through `Long.MAX_VALUE`, simultaneous mixed/indivisible tools,
ordinary tool production, exact seeds and forced/preserved requests must produce
physically executable plans. It independently checks each source transition and
small primitive executions, and replays delayed returns through save/load.
Missing tools/materials, incompatible whole-key groups, impossible exact seeds
and later stock changes guard against invented inventory or stale conclusions.
Same-rule wear/charge up to `Long.MAX_VALUE` must not trigger state enumeration
or an unrelated finite solve; unbounded eligibility chains and oversized output
sets must stop at the local support limit. Work/memory refusal and cancellation
cannot renew the allowance, leak workspace or prove the original domain empty.

`CapturedStateMaterialTest` adds 73 requests for exact returned states used as
ordinary ingredients, direct targets, final seeds, reusable read arcs and consumed
configurations. One deterministic rule suffices; capacities through `Long.MAX_VALUE`
must not expand unused wear states. It covers both source/stock orders, one to four
simultaneous tools, ordinary tool manufacturing, forced/preserved requests and
changed inventories. Returned plans undergo independent source-contract and
physical-inventory replay plus delayed runtime returns/save-load. Unfunded
manufacturing, insufficient simultaneous entities, spent final seeds, distant
undeclared transitions, quota/memory refusal and cancellation remain safe.
Only one-step transitions of known states and cross-rule boundaries are tried;
this finite recovery does not claim completeness for arbitrary wear paths.

`PlanCursorLoopTest` checks whole-loop skips against primitive expanded programs,
including shared and nested occurrences, partial batches, stale boundaries,
cancellation and saved exact counts above long. `CapturedStateLoopTest` preserves
interleaved host bindings and actual tool ownership through billion-round loops,
mixed rules, multiple tools, nested dispatches and per-dispatch configurations.
Its three-source shared-rule family uses the standard 40M budget: finding the
auxiliary schedule remains separate from accelerating physical restoration.
Delayed and rejected returns plus save/load are replayed on the restored words.

`StateTransitionTest` verifies exact and uncertain return contracts and bounded
state discovery. `StateReturnRuntimeTest` covers actual alternative receipts,
parallel equivalent/disjoint return domains, partial returns, synchronous handoff,
rejection, cancellation and persistence. Native AE/GT callbacks and molecular
assembler execution require the explicit local live-adapter checks.
`StateReturnConcurrencyTest` guards aggregate return capacity and temporary
subscriptions; `StateReturnPlaceholderTest` prevents placeholder completion from
discarding unresolved physical state returns.
`DagReturnWakeupTest` requires actual returns to wake the next operation in
128/1000-state chains within two dispatch checks, including a saved prefix.
It also preserves normal/retry service under repeated wakeups and rejects
duplicate dispatches after synchronous full or partial acceptance.
`DagWakeIndexTest` compares active subscribers with independent ordered sets
through 65,537 operations, including sparse keys and all bitmap-level boundaries.
`DagWakeEquivalenceTest` compares 384,000 events with an independent queue model:
recipe order, lane fairness, retry deadlines, synchronous receipts, accepted
prefixes and counts beyond long must agree. `DagSharedWakeTest` retains distinct
source bindings and checks linear notification work for up to 8,192 consumers.
Its physical runtime cases cover one/four tools, partial dispatch, separate and
synchronous receipts, older returns during rejection, and saved ownership.

The `Historical*Test` programs provide 31 maintained regressions. The migration
index maps 44 archived sources, including two reference helpers and aliases
whose distinct generator/branching variants are explicitly retained. They run in `standard` and can also be selected with
`--suite core --case '^Historical'`. `provenance/migrated-probes.json` records each
original program, its source-artifact hash, the unchanged portable source hash
(LF-normalized), its replacement and the exact contract changes.

Coverage includes budget overflow and concurrent reservations, 700 reduction,
1,200 Boolean, 1,000 MITM, 1,000 LCG and 1,500 neighborhood finite-domain oracles,
400 view oracles, 384 conserved-graph order replays, 300,000 scheduling decisions,
deep nested execution above long, cancellation, continuation ownership and all
six original warm-request inputs. The warm tests use independent subset/CNF
checks and original generators without loading the old native benchmark libraries.

The probe runner follows these migration mappings by default. Original sources
remain under `probes/java`; use `--original-probes` to reproduce the old program,
including its original arguments/dependencies and obsolete assertions. Explicit
`--argument` and `--compile-only` also select the original source. A shared
replacement executes once per invocation; alias result rows name the actual check.

Successful early proofs and valid witnesses no longer fail merely because a
particular old strategy did not run. Numerical, execution, memory and proof
assertions remain enforced. UNKNOWN and exhausted budgets are still unresolved;
historical failing labels are not rewritten into passing expectations. Local
player captures and recovered incident inputs remain in the local case registry.

`CountSessionBudgetTest` keeps persistent conflict-proof retention on the separate
cache preparation account, including full request accounts, partial cache grants,
cancellation, shared concurrent grants and readers paused during publication.
`CountSessionReuseTest` independently checks bounded proof-support reduction,
recipe-coordinate changes, subset/superset catalogs, changed goals and inventory,
new producers, external supplies, transitive premises, eviction and serialized
proof replay. Current-request matching and revalidation remain charged search work;
only complete material rows can justify an imported conflict.

Audit archived programs separately from their input coverage:

```text
python tests/probes/coverage.py --results build/tests --output build/coverage
```

Repeat `--results` for additional completed run directories. The JSON/CSV report
lists every historical program, its previous compile/execution status, maintained
replacement and actual result evidence. Compilation alone is `COMPILED_ONLY`;
missing arguments, native dependencies, helpers and obsolete diagnostics remain
explicit. Related dataset replay is not silently counted as execution of an old
program. Run directories are evidence for the build used in that run, not proof
that the current checkout has been tested.

The added historical checks include both affine offset distributions (2,400
systems), locked/unlocked LCG branching, 12,000 LP cache transitions, sparse
activity caches, exact proof reconstruction, continuation ownership, compiler
fusion and the large cycle cases after the old diagnostic's failed reflection.
`CountComponentRootProofTest` keeps a complete root proof after an inconclusive
sibling while confining guarded proofs. `SeedVerificationLifecycleTest` closes
partially retained verification on local-budget exit and cancellation.

`ForwardContinuationTest` checks retained original-state traversal and incremental
independent closure verification, including incomplete grants, failed frontier
insertions and cancellation. `regression/execution-boundaries.json` pins the
previously UNKNOWN 182-state rendezvous network, its 425-state impossible neighbor
and a funded 11-firing neighbor, each in three recipe orders. The complete original
recipes and stock are preserved; a local state/work cutoff is never an UNSAT oracle.
Three additional archived boundaries cap observed work at the default 40M request
budget. They catch auxiliary search evicting an almost completed primary count
turn. A smaller request budget changes strategy grants and is tested separately;
it is not interchangeable with an observed-work assertion.
The archived `pebble_01486` boundary also keeps short scout grants bounded so
auxiliary work cannot delay the next viable source view past the shared budget.
Two deep choice-chain boundaries require the complete count model to retain its
turn after stock-view interruptions. `AllocationWorkTest` checks that a paused
allocator only charges its own work to its local quota, while all intervening
strategy work still consumes the shared request budget, including cancellation.

`CountUnitFlowTest` checks forced production in pure conversion networks against
an independent token-capacity oracle, including changed stock on a reused compiler,
recipe permutations and quantities through `Long.MAX_VALUE`. Existing target stock
cannot become fresh production by circulating through conversions. The stronger
bound excludes mutable read arcs, inconsistent units, indivisible target batches,
growth/joint-input recipes and a second mandatory flow goal; target-consuming
startup witnesses remain accepted under the general production check.

`QuantityPotentialTest` checks sparse material-weight certificates on large
conversion catalogs, with recipe permutations and changing stock, mandatory
seeds, excluded producers and external supply. Numerical pricing only proposes
weights; every original producer and the current deficit require exact checks.
The regressions include an omitted producer, per-push configuration semantics,
one-unit boundaries at `Long.MAX_VALUE`, cancellation and optional memory denial.
A funded 2000-link alternative must also finish within its original 900k budget;
optional proof pricing yields its own work allowance to executable paths first.
Certificates reuse the immutable catalog cache; failed auxiliary searches do
not publish an infeasibility result or retain a stock-dependent failure.
`HistoricalAdmissionTest` also guards the sparse proof's admission: it must not
recruit an unrelated count scout ahead of the funded 7000-link stock path.
`GraphExecutionBoundaries` limits work on two existing source/seed examples:
the optional proof consumes the request budget without spending a paused
frontier's local turn or waking parked views before primary search resumes.

`CountBoundCoverTest` compares 400 complete finite assignment sets before and
after reduction. It covers uneven resource incidence, duplicate rows and genuine
non-unit upper bounds; a missing singleton resource must not become an invented
unit capacity. `CountRangedKernelTest` checks 2,400 signed and large-offset boxes
against exhaustive enumeration. Its retained affine cases require identical
work and witnesses across small and large grants, full restoration, optional
memory refusal and cancellation. Two existing corpus inputs are pinned in
`regression/execution-boundaries.json`, each in three recipe orders.

`CountEquationSlicesTest` checks 1,200 original-coordinate finite boxes against
exhaustive enumeration, with signed coefficients, 90-bit fixed offsets and row
permutations. Three-variable equation search compiles fixed terms and the pivot
substitution once, advances modular residues without rebuilding the inverse,
and reuses that state across small work grants. Projected constraints can skip
an impossible interval before enumeration. Every witness is checked against the
original rows; stopping this candidate strategy never proves infeasibility.
Memory refusal, cancellation and disposal are also covered.
`CountEquationResidueTest` keeps a separate 9,000-residue witness whose earlier
slices cannot be skipped by interval tightening; four row orders and paired
whole/small grants guard both exact restoration and repeated-work overhead.

The narrow-band search is a candidate strategy after exact affine elimination:
in a two-dimensional kernel it enumerates at most 128 integer row activities
and intersects every original projected constraint along each Diophantine line.
Its refusal or failure does not prove infeasibility. This complements the ranged
row/GCD reasoning documented in
[SCIP's linear constraint handler](https://github.com/scipopt/scip/blob/master/src/scip/cons_linear.c)
with an exact witness search; it adds no native dependency or floating tolerance.

The continuation/LP follow-up is documented in `../docs/effort-and-rational-lp.txt`.
It records the equal-budget three-order comparison and the separate known-SAT
miracle-crystal full-catalog cutoffs. Those cutoffs are not counted as passes.
The next boundary is `../docs/source-refinement-and-dispatch.txt`: seed/source
repair, local-region row admission, exact feedback propagation and cooperative
dispatch accounting. `SourceRefinementTest`, `PropagationFeedbackTest`,
`RegionBoundaryProjectionTest` and `PlanningDispatchAccountingTest` are in the
standard suite. The pinned full-capture cases can be run with
`python tests/datasets/regressions.py --java-home <jdk17>` and with
`--workers 4`, optionally `--expanded-budget`. Extra-first miracle-crystal
cutoffs remain recorded known-SAT failures, not passing expectations.

The full corpus preserves recipes in their input order, stock, exact quantities,
bindings, physical input slots, configuration/reusable flags, external supply and
request flags. Each canonical record has a content-derived ID, origin file/JSON
pointer, original names, oracle truth, seed and budget. Byte-identical source aliases
are recorded in `provenance/duplicates.json`; original archive members are listed in
`provenance/archive-entries.json`. Complete captures are gzip compressed with original
and compressed SHA-256 checksums in `datasets/index.json`.

`CountJumpBoundaryTest` exercises 643 exact integer-boundary cases through the
hinge sweep, its low-memory alternative and retained continuations. Gains of
roughly `-1e30` can round to the same double while the candidate one unit below
the breakpoint is still infeasible; tied gains are compared by subtracting the
candidate violations before conversion. This is candidate ranking, not a proof.
`CountSparseCutTest` checks sparse certified consequences in 32..8192-coordinate
models, including foreign scopes, invalid combinations, optional memory refusal,
cancellation and the independent proof-transfer admission boundary. Dense bound
sharing retains its separate dimension cap.

The primitive witness checker counts actual target production separately from
net inventory change. Forced production requires positive net gain, enough output
and enough final inventory for delivery; target inputs may serve as cycle seeds. Returning a
reusable configuration token is not production. `FixtureProductionTest` and the
`forced-production` fixtures cover self-copying, nested/shared repetitions beyond
long, read arcs and a stocked target with no fuel. Runtime unit checks use a
controlled clock; a separate deadline check preserves the real two-millisecond
yield condition, continuation and nanoTime wraparound.
`CountForcedGainTest` checks that the count model includes the same strictly
positive gain required by final witness verification. It retains the safe
relaxation for consumed configuration inputs charged per physical push and the
productive startup cases whose net gain is smaller than the requested amount.
Cold/warm request scopes, target stock at Long.MAX_VALUE, unforced stock delivery
and 60 ring/order/preservation cases are covered under a one-million-work cap.

## Controlled planning comparison

The main count stage has a separate reproducible comparison:

```text
python tests/benchmarks/compare_count_models.py --java-home <jdk17> --baseline 842b3eea --parity
```

It archives baseline sources without changing the checkout, then runs the same
canonical count inputs and the same harness at 20M work, 256 MiB, one worker and
no wall deadline. `--parity` adds 72 odd-cycle/feasible-neighbor cases. Every model
outcome, work value, gained/lost conclusion and source/harness fingerprint is saved.
Remaining UNKNOWN and unsupported conversions stay explicit; benchmark completion
does not mean every model is solved. See `../docs/parity-and-root-continuation.txt`.

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

`InterfaceContinuationTest` requires witnesses for 384 planted wide-domain
seed/order/interface cases under a 200,000-work neighborhood cap. Interfaces
have either four or 32 values. Conditional LCG and exact integer-kernel repair
retain state across local slices; fresh interfaces alternate with continuations.
Both frontiers and answers belong to the component and exact interface values.
Retention follows the shared byte allowance rather than a fixed entry count.
Eviction discards work without asserting infeasibility; a restart receives a
larger local allowance, while a retained frontier gets another fair slice of
the existing request budget. The test also exercises memory-driven eviction,
cancellation with parked children, idempotent close and independent replay of
scoped certificates after continuation.
`CountKernelTest` independently enumerates 480 finite boxes, including signed
domains, offsets beyond long, large coefficients and infeasible models. Every
returned kernel witness is restored and checked against original rows/bounds;
the kernel strategy never publishes a negative conclusion. Its cancellation,
memory refusal, local work limits and exhausted-budget resume also release all
reservations. The complete conditional backend must agree with the finite oracle
on every feasible/infeasible case, including any missed lattice-only candidate.
Four sparse stars (33 to 241 variables, wide internal counts and many eligible
Boolean separator vertices) must produce valid composed counts within a fixed
20,000-work neighborhood cap, including interface preparation.
Four conditional models also share one budget on four workers, exercising both
settings of the production work-budget multiplier without sharing model state.

For a controlled before/after comparison of the same conditional models:

```text
python tests/benchmarks/compare_conditional.py --java-home <jdk17> --baseline <commit>
```

Both revisions compile the same current harness and run with identical local,
request and memory caps. CSV rows retain every unresolved input, work and logical
reservation peak. Source/harness digests guard against changes during a run.
The recorded timing includes JVM warmup and is not a wall-time performance claim.

`ResidualNeighborhoodTest` covers 16 shuffled catalogs exceeding 9,000 recipes
whose offered supports lack a necessary co-producer. Demand boundaries admit
that source and its upstream chain into a bounded count neighborhood. Original
configuration/reusable inputs, physical seeds, force production and full outer
planning are checked, along with exclusions, catalog replacement, cancellation,
memory refusal and a low-budget two-source split. Catalog scans retain their
cursor across turns. Pages omitted by the current bounded support are never
reported as globally infeasible; this remains a positive-witness neighborhood,
not a complete replacement for the full-catalog model.

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

The 2026-10-09 replay distinguishes the default `lcg-first` entry (one local
allowance) from `lcg-retained`, `views`, and `main-counts`. Select an entry with
`--model-engine`; add `--model-trace` for bounded diagnostics, including on cutoffs.
The new default entry preserves the previous behavior. With 20M work and no wall
deadline, the pre-change first-slice replay concludes 194 of 274; 80 are unresolved.
Retained LCG resolves nine of those 80 at the same request cap.

`main-counts` embeds finite original models as recipes and invokes
`IntegerCountSearch`. It is the main count stage, not the complete graph planner.
It checks original bounds/rows and independently replays primitive plan prefixes.
The capacity follow-up improves its corpus result from 229 to 236 conclusions,
leaving 32 unresolved and six unsupported finite conversions. Unsupported input
also exits 2; no model's truth is changed. Detailed scope, equal-budget results and
remaining IDs are in `../docs/count-corpus-followup.txt` and
`provenance/count-replay-20261009.json`.

```text
python tests/run.py --java-home <jdk17> --suite models --model-engine main-counts --case clique-coloring --milliseconds 0 --work 20000000
python tests/run.py --java-home <jdk17> --suite models --model-engine main-counts --milliseconds 0 --work 20000000 --keep-going
```

`HallCapacityTest` covers complete pairwise capacities, missing-edge feasible
neighbors, exhaustive finite assignments, independent proof chains/serialization,
and interruption inside nested workspace reservations. `CapacityPipelineReview`
exercises the same models through the count-stage corpus entry with row/column
permutations and signed domain shifts.

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


The numeric continuation/root-proof follow-up is documented in
`../docs/numeric-continuation-and-root-proofs.txt`. `NumericContinuationTest`
checks exact arithmetic handoffs and four-worker shared caps. The canonical
comparison accepts `--permutations 3` to vary rows, coordinates and term order.
Optional `oracles/verify_count_models.py` cross-checks the recorded residuals
using test-only Z3, CP-SAT and SCIP installations, preserving unbounded domains
and exactly checking every returned integer witness. Recorded oracle versions,
input hashes and witnesses are in `provenance/numeric-oracles-20261009.json`;
these native packages are not needed by the stable suite or production core.

`SeedEntryRepairTest` checks a closed conversion cycle whose cheap initial
stock cannot fund the goal. It requires entry repair at another material,
then verifies the original executable plan. Its bounded scan is also checked
across coordinator-thread handoff, cancellation and memory/local-work limits.
The full captured JEI-first boundary is pinned in `datasets/regressions.json`;
the equal-budget comparison is in `../docs/funded-cycle-entries.txt`.

`CountGramSchmidtTest` compares integer Gram factors with an independent explicit
vector oracle, including wide ambient matrices, large signed coefficients and
retained execution. Expanded affine/kernel admission is checked against original
bounds and rows, with rank refusal, memory/cancellation limits and worker handoff.
The new canonical positive boundary is part of `CountModelBoundaries`; the
complete 822-case equal-budget comparison and remaining order sensitivity are
recorded in `../docs/affine-gram-admission.txt` and
`provenance/affine-gram-admission-20261010.json`.

```text
python tests/run.py --java-home <jdk17> --suite standard --case 'CountGramSchmidtTest|CountKernelTest|NumericContinuationTest|CountModelBoundaries' --timeout 600
```

The next numeric boundary is recorded in `../docs/numeric-progress-and-grants.txt`
and `provenance/numeric-progress-grants-20261010.json`. Sparse/cancelled Gram
supports add 97 independent matrix checks. Numeric continuations now require
each pause to have consumed its owner grant, including 512 nested handoffs;
removing each renewal independently reproduces a premature pause. The count
boundary includes the failing full-corpus permutation through `corpus_order`.
It uses the complete manifest's deterministic shuffle stream, shared with the
comparison runner, rather than shuffling only the selected boundary records.

`CountLatticePotentialTest` independently checks deeper lattice insertions with
Gram determinants, exact factor updates, certified integer enclosures, retained
handoffs and request-scoped norm reuse. `../docs/lattice-potential-refinement.txt`
and `provenance/lattice-potential-20261010.json` distinguish the unchanged 20M
corpus outcomes from three new 80M successes. The three new boundaries have an
explicit per-case `work` cap; existing boundaries still run at 20M. The comparison
runner accepts `--work` and `--complete-corpus-order` to reproduce the same
filtered higher-budget campaign without changing solver defaults.

`CountLcgMinimizeTest` checks integer-bound conflict minimization against 54
independently enumerated boxes and 120 finite-domain models. It covers exact
bound strength/direction, both optional allocation refusals, partial reason
retry, worker handoff, actual learned-bound removal and independent replay of
all 120 serialized proofs. The gained `wide-79` ordering and protected `wide-52`
and `wide-68` orderings are pinned in `count-boundaries.json`. Equal-budget
outcomes, rejected broader policies and residual UNKNOWN orders are recorded in
`../docs/integer-conflict-minimization.txt` and
`provenance/integer-conflict-minimization-20261010.json`.

`CountProgressSchedulingTest` checks retained LP progress and exact numeric
elimination evidence against duplicate rows, fixed variables and trial faces.
It covers mandatory visits, stagnation, one-time ceilings, proof eligibility,
large integer budgets and cleanup. `RootPortfolioTest` also guards the work
of six cheap matching witnesses against premature LP extensions. The new 27
20M count/order boundaries are pinned in `regression/count-boundaries.json`.
Diagnosis and rejected broader policies are recorded in
`../docs/retained-progress-scheduling.txt` and
`provenance/retained-progress-scheduling-20261010.json`.

The model runner now also accepts `lp-retained`, `pb-retained`,
`affine-retained` and `mitm-retained` with `--model-engine`. These direct
original-row entries separate tool capacity from portfolio dispatch. Returned
witnesses still pass the independent row/domain oracle. An admission refusal
or an incomplete retained search stays UNKNOWN; it is never an UNSAT result.

`CapturedBatchAllocationTest` adds 600 exhaustive multi-firing stock comparisons
and 24 permutations of a 12-slot, 300-alternative domain under one/four-worker
scheduling, including expanded budgets. `CapturedProofTest` independently checks
candidate ordinals, archived coverage and compressed serial witnesses, including
tampering, overlapping inventory, physical returns and large/deep programs.
Both are in the standard suite. Reproduce the focused boundary with
`--suite standard --case Captured`; implementation scope and the equal-budget
split-stock counterexample are in `../docs/captured-candidate-domains.txt`.

`CapturedFlowAllocationTest` adds 600 independent exhaustive upstream/downstream
allocation comparisons and 24 wide candidate/provider permutations. It checks
shared raw stock, physical returns, startup-dead rings, exact large-count
recovery, explicit seed goals, optional admission, cancellation and independent
CGD replay. Its equal-budget upstream counterexample also records the work
benefit of reusing recovered counts instead of repeating count search. It is
included in the same standard `--case Captured` group.

`IntegerLpLearningTest` adds 400 independently enumerated bounded-integer
models, 1600 branch domains, sparse working supports, exact large offsets and
portable cut proofs. `CountRowTemplatesTest` checks changed inventories and
goals, reordered/updated producers, parallel requests and independently
replayed current-RHS derivations. Both test budget refusal and cleanup.
The model runner accepts `--model-engine integer-lp-retained`; it exposes the
new tool separately from its more selective main-portfolio admission. The
direct 500k `wide-68` boundary and its three protected main-portfolio orderings
are included in the standard suite. Scope, rejected broader admission and
remaining limits are in `../docs/integer-lp-and-row-templates.txt`.

`CountNormalizedTemplatesTest` adds 180 changing-inventory/demand/order
comparisons and 360 portable multi-step proofs. Intermediate normalization
floors remain explicit; new suppliers, conditional premises and cancellation
are checked independently. `IntegerLpProposalTest` exercises no-cut numerical
witnesses through the normal candidate feedback path, rejection followed by
continued exact search, omitted boundary rows and proof-mode exclusion. Run
these together with `--suite standard --case 'TemplatesTest|IntegerLp'`.

`CountRecoveryAdmissionTest` covers bounded choice kernels behind aliases and
private chains, boundary byproducts outside the count closure, changed stock,
large finite domains, deterministic-chain refusal, configuration semantics,
optional memory refusal and cancellation. `GraphExecutionBoundaries` pins five
previously unresolved deep-chain inputs in three orders at the default budget;
every returned plan is independently replayed against the primitive recipes.

`GraphEdgeCompactionTest` checks 600 seeded catalogs against an independent
physical-reachability oracle, including configuration returns, stock boundaries
and exclusions. Cold, warmed, packed and four-worker compilation must preserve
ordered SCCs. Co-output chains and rings also cover repeated dependency edges,
bounded memory, cancellation and incomplete-cache cleanup. Twelve additional
catalogs exercise equal-but-distinct resource keys in hash-collision tree bins.

`GraphTopologyReuseTest` checks physical dependency reuse across alternative
recipe objects. In 240 seeded catalogs, ordered regions must equal a fresh
compilation and satisfy an independent recipe-reachability oracle. Dense chains
and rings check saved compilation work and bounded reservations; changed counts,
bindings, stock leaves, exclusions, input/output order and real versus virtual
returns cannot inherit old demand semantics. Equal-but-distinct output keys in
collision tree bins deliberately miss. Matching is resumable, uses the compilation
account, and publishes only complete partitions into the existing bounded cache.
Cancellation, optional memory refusal, independent cache budgets and four-worker
continuations/publications are covered. This does not reuse execution proofs or
make a restricted source choice a global infeasibility result.

`StockCountHandoffTest` checks initial and retained complete-model turns during
stock-view repair, cold/warm work caps, independent finite-state enumeration,
changed stock, 24 recipe permutations and one/four-worker handoffs. Cancellation
must release both live frontiers. Seven additional `GraphExecutionBoundaries`
retain the default 40M request budget, including two feasible cases that prevent
extra count turns from starving stock/allocation witnesses.

`CountSparseCongruenceTest` checks integer equality elimination against 1,200
independent determinant oracles, cyclic Latin systems of even and odd order,
changed right-hand sides with explicit witnesses, and sparse supports embedded
in 8,000 unrelated coordinates. Every negative keeps a replayable original-row
divisibility certificate. Shuffling, low budgets, cancellation, magnitude refusal
and memory release are covered. Canonical Latin-16/20 cases additionally guard
the complete planner at its unchanged default budget. Two existing recipe
orders of `recycle_mix7` retain executable-witness coverage at the same 40M
request budget, so faster equality analysis cannot hide a lost feasible path.

`CountLpProximityTest` compares the Boolean LP neighborhood with independent
enumeration on 1,800 models in two row orders. Every fractional coordinate stays
free, and at most three rounded integral choices change. The indexed join must
check all material rows and reject overlapping flips. Tests include signed and
90-bit scaled rows, a required third change, feasible points outside the searched
neighborhood, changed domains, imported exclusions, worker handoffs, unrelated
work between visits, cancellation and optional memory refusal. Restricted
exhaustion exports no negative conclusion. The main LP arm retains one bounded
attempt per request across branches and workers, and independently validates its
candidate in the original search scope. All work is charged to the request;
the scout and productive LP continuation retain separate local allowances.
Canonical knapsack guards cover a recovered witness in four recipe orders and
an existing plan near the 40M limit that initially exposed duplicated scout work
and premature loss of the LP frontier's allocation.
Exact opposite rows additionally exercise a factored equality hash join, including
different row scales, fixed variables, hash collisions and remaining inequalities.
A feasible model outside the neighborhood checks that exhaustion stays local.
Cancellation and retained workspace checks cover partial equality tables; a
32-choice graph guards the previously wasted dominance-index construction budget.

`CountLpPricedTest` adds 600 independent five-change Boolean neighborhood oracles
in two row orders and 200 exhaustive price-ordered subset checks. The deeper
candidate search follows the existing shallow opportunity, restricts its own
domain with an LP aggregate, and explores inexpensive changes first using a
retained primitive heap. No failed neighborhood exports an infeasibility proof.
Checks include signed/fixed/scaled rows, 200-bit price proposals, four and five
required changes, unchanged caller domains, memory and queue admission, charged
heap resizing, cancellation, and worker handoffs. Canonical knapsack guards replay
three formerly unresolved graphs in four recipe orders at the unchanged 40M budget.
Two fast infeasibility guards ensure the deeper neighborhood preserves the initial
LP sample. Its original LP point and index remain retained for a later visit;
candidate work must not prematurely park a nearly complete proof behind other
expensive strategies.
`CountStrategyTraceTest` checks that real LP/LCG and neighborhood visits appear
under their actual strategy names, with identical results and work when metrics
are enabled or disabled; a retained LP is not reported as model compilation.

`CountProgressSchedulingTest` also checks measured LP-to-candidate handoffs:
completed LP work excludes unrelated frontiers and resets on resume; waiting
compensation is bounded by one policy batch, the original candidate cap and
the shared remaining budget. Observation cannot construct scouts or bypass
proof-mode eligibility, including long-limit and seven-unit tail controls.
The `mknapcb4_16_bks` execution boundary retains its canonical order, original
SAT oracle and 40M budget, with independent primitive-prefix validation.

`CountSearchOwnershipTest` covers complete-scope stock/count handoffs. Identical
recipe and resource order can transfer live branches without refunding their
work; different orders retain separate searches and exchange only certified
order clauses. The full backward closure, original recipe objects and immutable
request parameters must match before either operation. Its 36 positive/negative
permutation controls replay primitive firings independently; further controls
cover changed inventory, goals, seeds, providers, slots, exclusions, new supply,
memory refusal, cancellation and cleanup after exclusive ownership transfer.

`CountPackingTailTest` checks 3,072 exact suffix queries against independent
primitive sums. Signed/scaled rows, forced holes, changed prefixes, bit 64 and
worker handoffs reuse one retained table without carrying query assumptions to
the next prefix. A parity oracle exercises lazy table admission and the ordinary
search under memory refusal; cancelled builds/queries release their storage.
Fourteen original multi-dimensional knapsack cases now have strict execution
boundaries at the existing 40M budget. The suffix index is private to its Boolean
face; it cannot export a restricted failure as an original-model proof.
Three existing witnesses also retain their 2M, 8M and 20M cost guards: speculative
table construction must not consume the original search's low-budget solution.
Index partition samples affect ordering only. Exact leaf bounds are joined after
both children finish; 156 uniquely determined full-box assignments guard against
using sampled extrema for pruning. Storage follows the actual eight-point leaves.

`CountPackingIndexTest` checks 144 unique signed/scaled assignments and 256
exhaustive radius oracles, including 64 variables, incomplete tree leaves,
worker handoffs, compact-memory admission and cancellation during tree joins.
Sampled partition axes never replace exact subtree activity bounds. Retained
combination prefixes must survive the switch between both exchange halves.

`CountPackingScoutTest` distinguishes six- from eight-coordinate neighborhoods,
checks the saved learned frontier and compiled projection, and exercises phase
limits, handoffs and cancellation. The original 40M execution boundaries retain
newly verified wide-exchange witnesses and both old witnesses displaced
by premature phase expiry or a missing restoration notification. Numerical LP fixings and exchange radii remain local hints;
all accepted counts still pass original-row and primitive-execution validation.

`CountPackingThresholdTest` compares 4,096 signed, clustered and scaled models
with independent exhaustive evaluation, including the first witness in the
original branch order. Integer intervals narrow the existing threshold lookup;
large coefficients and exact ties still use their original values. The optional
index has its own bounded reservation and uses ordinary binary search when that
reservation is refused. Checks cover worker handoffs, interval boundaries up to
57-bit coefficients, identical witnesses with and without index memory, and
400 construction/search work limits with complete cleanup. This changes neither
the neighborhood nor its proof scope, and adds no persistent cache.
Three canonical packing inputs also guard first-plan cost below 2M reported
work under the unchanged 40M scheduling envelope. A smaller request budget
changes strategy allocation and is tested separately; it is not equivalent to
this observed-cost bound.

`CountPackingServiceTest` uses a planted, independently funded 100-recipe
fixture to exercise complementary packing service on a second worker. Both
frontiers retain their stacks and fixed ceilings; charged work cannot be
refunded on handoff. Completion of the initial restricted exchange ends this
extra service while preserving ordinary LP/portfolio continuation. Cancellation
releases both owners. The existing exhaustive packing checks still validate the
underlying searches and their proof boundaries.
Three canonical witnesses now have actual 2M request-budget guards, and another
has a 4M guard plus a 3M observed-cost guard under the normal 40M budget. A smaller
budget must leave a bounded initial sample and useful exchange queries after
index preparation. No restricted failure is promoted to an original-model
proof. Internal scout allocation changes within the unchanged request, total
search and normal-phase ceilings; handoffs cannot renew those ceilings.
The same four inputs also run through four-worker execution boundaries with
fixed and expanded budgets; independent primitive replay remains mandatory.
Measured early service offsets later discretionary LP companion visits once;
the service-ledger check forbids refunding charged work or granting the same
opportunity twice. Existing 12M cost guards remain unchanged.


`CountPackingQueryTest` checks repeated exact index queries after RHS and fixed
suffix changes, including bit 64 and a worker handoff. The last separating row
is checked against each new query; it is never a reusable infeasibility verdict.
A query-cost guard catches repeated scanning of already known harmless rows.
The table keeps constant storage across queries and releases it with its owner.
Exact absolute-sum bounds allow narrower point storage. Boundary controls straddle
the signed 32-bit range and also use 43-bit scales; query arithmetic and node
bounds remain 64-bit. The existing exhaustive tail and exchange oracles cover
both representations. `mknapcb2_19_bks` additionally guards first-plan cost below
6M reported work within the unchanged 40M request allowance.
A separate 8M request guards observed cost below 5M; this cost bound does not
imply a 5M request budget is sufficient.

The retained suffix table also reuses each row's exact minimum over its complete
Boolean domain. Query restoration visits only fixed variables at the adverse
endpoint: a positive coefficient fixed to one or a negative coefficient fixed
to zero. Both endpoints of mixed fixed/free domains, including bit 64, are
checked against independently formed slacks in `CountPackingTailTest`.
These summaries depend only on the table's coefficients; changed query bounds
and fixings are always reapplied. Construction and query work remain charged to
search, while the three row arrays belong to the table's reserved workspace.
Restoration charges one bounded batch for row setup and at most one for the
actual fixed-endpoint corrections, reusing construction scratch space. This
avoids per-row shared-counter updates without discounting performed work.

`CountPackingProjectionTest` checks 4,608 queries against independent point
enumeration. After repeated paid suffix traversal, an optional index combines
the demand row with each other row over the same immutable point set. Sorting
the common axis once allows exact prefix minima to exclude queries that pass
each row's separate minimum. Passing these pairwise relaxations is not a joint
witness; the original suffix search still checks all rows and fixed values.
This follows the per-capacity bound idea used by
[OR-Tools' knapsack solver](https://github.com/google/or-tools/blob/stable/ortools/algorithms/knapsack_solver.cc),
with CGSE deriving each bound from its exact finite suffix table.

One summary per eight points reduces projection storage. Incomplete final
blocks are checked exactly, including tied axis values and non-power-of-two
point counts. Tests cover signed 43-bit scales, extreme query bounds, changed
fixed variables at bit 64, worker handoffs, cancellation, memory refusal and
exhaustion before publishing a result. A parity oracle exercises deferred
admission through the real suffix search; later relaxed queries must recover
their independently enumerated witnesses. A 160 KiB guard admits the widest
compact projection without retaining a dense summary for every point.
All construction and query work belongs to the current search; it never uses
the independent cross-request cache-preparation allowance. Restricted misses
remain local to this suffix and cannot become global infeasibility proofs.
The `mknapcb2_04_bks` first-plan cost guard keeps optional construction from
displacing a short original search: observed work must stay below 6M within
the unchanged 40M scheduling envelope. This is not a 6M request-budget claim.

`CapturedIndexReuseTest` checks complete producer-index reuse within one immutable
capture, with 1,024/16,384 irrelevant sources, reversed source order, changing
targets and stock, stable state families, worn tools and manufactured replacements.
Low-budget requests must still produce independently verified physical plans;
cached producer incidence is compared with a full literal scan of every output
and return. Return candidates beyond 256, empty domains, virtual configuration
returns, force/preserve/seeds, replaced catalogs and incomplete captures guard
scope. Another 120 shuffled catalogs compare source priority and execution words
against literal GraphCompiler models. Cancellation, independent cache-account
refusal, an oversized index, a 64 KiB borrower and four concurrent publishers
exercise ownership and resource limits. A retained cache has its own 8 MiB
estimated-container cap; request peaks exclude that persistent allocation.

`CapturedStatePreparationTest` independently widens seven discovery dimensions
to 10,000 elements: stock, external supply, source lists, outputs, slots,
candidates and family identities. Construction must not scan them eagerly.
Preparation yields between elements and stops at its original local allowance;
memory refusal, mid-discovery cancellation and controlled timeout all release
workspace. The synthetic family rejects algebra calls during discovery, so
enumerating unused physical states cannot pass this regression.

`CapturedSupportCacheTest` checks target/seed-key scope under deliberate hash
collisions, immutable source order, 32-entry/64-KiB limits, oversized scopes,
cancellation, independent retention-account refusal and four concurrent writers.
`CapturedStateReuseTest` checks 48 cold/warm cyclic-tool budget combinations with
up to 16,384 irrelevant sources, sparse/full stock and reversed source order.
Small physical plans are replayed against the original tool rules and stock.
Changed tool states, missing tools, manufactured replacements, required-seed
amounts, force/preserve modes, capture replacement and explicit parent remainders
guard against stale results or renewed budgets. All 48 requests require verified
plans, including the eight formerly limited large-stock requests. Warm compilation
must stay below 1,000 work units and request workspace below 600,000 bytes.

`CapturedStockReuseTest` checks positive stock-key hints under changing durability,
quantities, external supply, target stock, required seeds and capture identity.
Unknown worn keys and mixed simultaneous entities must trigger a full-stock retry;
missing tools cannot reuse old quantities or become a global negative proof.
Manufactured replacements, forced production, cancellation, low budgets and four
concurrent requests are checked with physical source replay. `CapturedStockProjectionTest`
checks independent optional retention, hash collisions, aggregate entry/byte limits,
partial preparation refusal, immutable hints and resumable current-stock reads.
The retained hints contain keys, not amounts; request workspace excludes their
separate 64-KiB estimated-container cap. All attempts share the original state-arm
allowance.

`CapturedStateProducerReuseTest` checks 48 cold/warm requests whose new targets
share a tool-manufacturing boundary, with up to 16,384 unrelated producer keys,
both recipe orders and existing/manufactured tools. The twelve former low-budget
misses require physically replayed witnesses. Current stock, external supply,
seed/force/preserve modes, replaced captures, new producers, cancellation,
memory/work limits and four concurrent targets guard the scope of reuse.
`CapturedStateProducerCacheTest` checks identical rule/index ownership, immutable
complete projections, partial-scan refusal, independent preparation quotas,
concurrent publication and 32-entry/64-KiB estimated-container limits. Only a
completed scan may be retained; no more than 256 keys per rule are admitted.
The 1,200 finite closure oracles in `CapturedStateSupportTest` now run without
retention, while populating it and while reusing it, preserving original sources
and their priority. Reuse selects a positive attempt; it carries no stock amounts
or negative solver facts and does not unfold unused tool states.

`CapturedStateRecoveryTest` checks optional discovery of implicit state-return
sources after a selected state attempt fails. Sixty shuffled dependency chains
exercise repeated discovery across sources, both with and without retained
producer projections. Two 16,384-rule catalogs require keeping useful sources
when discovery reaches its allowance. Parent work limits, cancellation, memory
refusal, original source order and incomplete projection rejection are checked.
The canonical producer index remains unchanged; a bounded selected subset is
only a positive candidate, not a complete closure or infeasibility certificate.

`CapturedStateRecoveryPipelineTest` checks 178 public requests with up to 16,384
unrelated ordinary or stateful sources. Physical worn states are consumed as
ordinary ingredients, requested directly, or retained as final seeds. Cold and
warm requests, terminal returns, 8,192/Long.MAX_VALUE capacities, simultaneous
tools, tool manufacturing and changed stock are checked with independent source
semantics and integer execution replay. Delayed returns and runtime save/load
exercise recovered plans. The third request must compile in fewer than 2,000
work units. A verified recovery may extend a cached source list in its original
order, dropping its old stock projection; `CapturedSupportCacheTest` guards
identity, scope, independent retention quotas and aggregate cache limits.
Recovery probes one transition at a declared boundary; it does not enumerate
unused durability states or prove arbitrary state reachability.

`CapturedStateSourceReuseTest` checks 140 public requests across distinct targets
that need the same implicit tool return. The 96-request matrix includes 512 and
16,384 ordinary/stateful noise sources, both source orders, terminal returns and
work caps from 20,000 to 40 million. Each positive plan is replayed against its
original source and physical inventory; warm compilation must stay below 2,000
work units. Mixed simultaneous tools, replacement manufacturing, delayed returns,
persistence, changed stock/seeds and replaced captures guard the reuse scope.
An old source hint that cannot use current stock must preserve the full recovery
path, which can discover and retain a newly funded source for the next target.
Cancellation and an injected shared-clock advance check partial-trial cleanup
and the original parent allowance. Source-hint discovery plus its trial has a
shared cap of one quarter of the state remainder, at most 65,536 work units.

`CapturedStateSourceCacheTest` checks immutable capture/position ownership,
original source order, stateful-slot eligibility, invalid and oversized inputs,
partial-scan refusal, independent cache quotas, cancellation and four concurrent
publishers. The optional pool retains at most 128 source positions, under an
8-KiB estimated-container cap; it contains no stock quantities, inferred bounds,
complete coverage or negative solver facts. Only a verified selected state plan
may populate this pool, and declined/expired hinted trials retain the original
full source-discovery path.

`CapturedPreviewScopeTest` checks that only restricted view owners omit optional
refill previews, preserving complete capture previews and positive witnesses.
`CapturedHandoffTest` injects limits after the real child's proof/witness is ready
and before parent publication; it also covers children that finish in the same
step as their local proof because no discarded preview is needed.
