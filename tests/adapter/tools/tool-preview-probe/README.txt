Independent native regression mod: ToolPreviewProbe.java (gtlsubmissionprobe).
Keep it separate from the gtlgraphprobe mod; never install in a player world.

Compile this source with --release 17 against the selected remapped GTL JAR,
the isolated Forge server's mods and libraries. Package its classes with Forge
mods.toml declaring modId="gtlsubmissionprobe", version="1", license="MIT".
The disposable server must contain the real GTCEu basic bender recipe.

toolpreviewfixed: real pattern capture and main CGSE solve of 100 benders using
one iron wrench; checks concise preview and original exact state program.
Replays each dispatch's material balance and compares its predicted tool return
with the native GT remainder callback. This is not a physical assembler test.
Also checks worn/electric grouping, real manufacture, explicit worn targets,
enchantment/name/material isolation, exact missing keys and seeds, >long totals,
and actual mixin-extended summary-entry packet read/write.

toolpreviewbaseline: reproduces the old summary's 101 tool rows / 100 crafted
returns, while the plan needs exactly one physical wrench. For old host builds.

Expected terminal marker: [Submission Probe] ALL PASS.
The normal all runner lists this as requiring a disposable Forge environment.
Its portable companion PlanSummaryProjectionTest is discovered automatically.
Keep run logs, artifacts and player evidence under .local/cases/, not in Git.
