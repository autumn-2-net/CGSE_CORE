// Isolated server only. Observe real AEDUMP hooks; never ship with either production mod.
var dumpMigrationPending = null;
function dumpMigrationFail(error) { console.error('[AEDUMP Migration] FAIL ' + error); }
ServerEvents.commandRegistry(event => {
    function command(name, action) {
        event.register(event.commands.literal(name).requires(source => source.hasPermission(4)).executes(context => {
            try { action(context.source.level); return 1; } catch (error) { dumpMigrationFail(error); return 0; }
        }));
    }
    command('aedumpmigrationverify', level => {
        var manager = Java.loadClass('org.gtlcore.aedump.DumpManager');
        var record = manager.find(null, true, 0);
        if (record === null || !record.done || record.graphPlan === null) throw new Error('Missing completed CGSE record');
        if (!record.input.get('complete').getAsBoolean()) throw new Error('RequestPlanningWork actual input was not captured');
        for (var flag of ['directEmission', 'fallbackAttempted', 'fallbackMode'])
            if (!record.coordinator.has(flag)) throw new Error('Missing coordinator flag ' + flag);
        Java.loadClass('org.cgse.core.GraphPlan');
        console.info('[AEDUMP Migration] CAPTURE PASS id=' + record.id + ' result=' + record.result + ' recipes=' + record.input.getAsJsonArray('recipes').size());
    });
    command('aedumpmigrationlimit', level => {
        if (dumpMigrationPending !== null) throw new Error('Capture probe already active');
        var Config = Java.loadClass('org.gtlcore.gtlcore.config.ConfigHolder').INSTANCE;
        var DumpConfig = Java.loadClass('org.gtlcore.aedump.DumpConfig');
        DumpConfig.enabled.set(true); DumpConfig.autoErrors.set(true);
        var Pos = Java.loadClass('net.minecraft.core.BlockPos');
        var Key = Java.loadClass('appeng.api.stacks.AEItemKey');
        var Source = Java.loadClass('appeng.me.helpers.MachineSource');
        var Strategy = Java.loadClass('appeng.api.networking.crafting.CalculationStrategy');
        var cpu = level.getBlockEntity(new Pos(33, 65, 0));
        var grid = cpu.getMainNode().getNode().getGrid(), source = new Source(cpu);
        var target = Key['of(net.minecraft.world.item.ItemStack)'](Item.of('minecraft:netherite_upgrade_smithing_template'));
        var oldSteps = Config.ae2GraphPlannerMaxSteps, oldFallback = Config.ae2GraphFallback;
        Config.ae2GraphPlannerMaxSteps = 1; Config.ae2GraphFallback = false;
        try {
            var before = Java.loadClass('org.gtlcore.aedump.DumpManager').IDS.get();
            var future = grid.getCraftingService().beginCraftingCalculation(level, () => source, target, 1000000000000, Strategy.REPORT_MISSING_ITEMS);
            dumpMigrationPending = {future: future, source: source, ticks: 0, failed: false, before: before, oldSteps: oldSteps, oldFallback: oldFallback};
        } catch (error) {
            Config.ae2GraphPlannerMaxSteps = oldSteps; Config.ae2GraphFallback = oldFallback; throw error;
        }
    });
});
ServerEvents.tick(event => {
    var state = dumpMigrationPending;
    if (state === null) return;
    var Config = Java.loadClass('org.gtlcore.gtlcore.config.ConfigHolder').INSTANCE;
    try {
        if (++state.ticks > 2400) throw new Error('Automatic archive timed out');
        if (!state.future.isDone()) return;
        if (!state.failed) {
            try { state.future.get(); throw new Error('Tiny budget unexpectedly returned a plan'); }
            catch (error) {
                if (String(error).indexOf('SEARCH_LIMIT') < 0) throw error;
                state.failed = true;
            } finally {
                Config.ae2GraphPlannerMaxSteps = state.oldSteps; Config.ae2GraphFallback = state.oldFallback;
            }
        }
        var record = Java.loadClass('org.gtlcore.aedump.DumpManager').find(null, true, 0);
        if (record === null || record.id <= state.before || !record.done || record.saved === null) return;
        if (String(record.result) !== 'ERROR') throw new Error('Expected captured budget failure, got ' + record.result);
        console.info('[AEDUMP Migration] AUTO PASS id=' + record.id + ' file=' + record.saved);
        dumpMigrationPending = null;
    } catch (error) {
        Config.ae2GraphPlannerMaxSteps = state.oldSteps; Config.ae2GraphFallback = state.oldFallback;
        if (!state.future.isDone()) state.future.cancel(false);
        dumpMigrationPending = null; dumpMigrationFail(error);
    }
});
