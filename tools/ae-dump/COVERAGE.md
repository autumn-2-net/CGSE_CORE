# AEDUMP migration coverage

The optional Forge diagnostic mod lives entirely in `tools/ae-dump`. Its source,
resources, Gradle wrapper and build entry points were copied from the local
`ae-dump` project on 2026-10-07. Generated `build`, `dist` and `.local` content was
excluded. The mod keeps its `org.gtlcore.aedump` namespace and existing mod ID;
it is a GTL/AE adapter, not part of the portable engine.

| Capture surface | Migrated owner | Preserved output |
| --- | --- | --- |
| Order entry and replan checkpoint | GTL `CraftingEngineRouter`; checkpoint type in `org.cgse.core` | Request identity, strategy, target, amount, owner, replan flag |
| Request coordinator | `RequestPlanningWork` through the host's `planning` field | Actual available inventory, direct emission, craft-less bounds, fallback attempt/mode, selected plan |
| Network capture and catalog preparation | GTL request wrapper | Frozen network snapshot, stock, emitters, cache epoch, host capture/preparation timing |
| Budget and solver trace | `org.cgse.core.PlanningBudget` and `GraphPlanningWork` | Limits, work, memory, phase, bounded event/state history |
| Raw network/legacy preview | AE/GTL host APIs | Pattern definitions, order, alternatives, NBT, topology, permissions, MaxFast previews |

The existing JSON schemas, integer-string quantities, archive entries, command
permissions, capture timing, retention and automatic-export rules are unchanged.
Coordinator output adds `catalogPreparationElapsed` and `catalogParallelNanos`
when present. Missing delegate state during early compilation stays incomplete;
it is not presented as a complete solver input. No capture submits a job or
performs real item extraction, and the mod does not upload archives.

Native migration testing also found that unnamed AE CPUs can return a null name.
Their CPU entry now preserves `name: null` instead of failing the entire CPU
capture section. The live smoke checks include unnamed vanilla crafting CPUs.

Run `python tests/ae-dump/check_migration.py` from the repository root. It checks
package/descriptors and build configuration, compiles the named reflection
projection with the real portable engine on Java 17, and exercises ordinary
planning, direct emission and bounded-alternative fallback. All test code and
temporary test products are kept outside the tool's production source tree.

The original `.local/DumpRoundTrip.java` and `.local/validate_archives.py` checks
are migrated to `tests/ae-dump/runtime/java/local/aedumptest/DumpRoundTrip.java`
and `tests/ae-dump/validate_archives.py`. The runtime helper now uses the official
`TagParser.parseTag` name; compile it with the host's mapped development
classpath and remap its test mod before loading a production server. It is never
part of the shipped AEDUMP JAR. It retains permission/size-cap checks and original
pattern decode comparisons, and checks delegated coordinator fields in CGSE
archives. `checks(UUID)` allows a test server to supply its captured player's ID.

Run `python tests/ae-dump/validate_archives.py <archive-or-directory>` against
native exports. Add `--require-complete` when every selected archive must be
complete. The validator now also rejects missing portable delegate state, lost
fallback flags, direct emission incorrectly marked force-craft, and numeric
values in place of exact integer strings. Seven synthetic archive regressions
exercise these checks as part of `check_migration.py`. Historical follow-up
notes remain in `tests/ae-dump/provenance`; they are not runtime configuration.

Host verification uses `buildAeDump` through `build-with-core.init.gradle` with
an explicit `-PcgseAeDumpDir`. The production mod must then be checked in a native
Forge 1.20.1 server: Mixin application and commands, player preview, machine
request, missing-input/manual export, automatic fallback export, and ZIP fields
including `cgse-input.json`, `result.json`, `cgse-plan.json`, and
`export-status.json`. Pure Java checks do not establish in-game Mixin success.
