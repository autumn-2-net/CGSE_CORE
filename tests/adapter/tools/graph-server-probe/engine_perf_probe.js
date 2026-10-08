// Local-only, isolated Forge server benchmark. No jobs are submitted.
ServerEvents.tick(event => Java.loadClass('org.gtlcore.test.EnginePerfProbe').tick());
ServerEvents.commandRegistry(event => {
    event.register(event.commands.literal('engineperf').requires(s => s.hasPermission(4)).executes(ctx => {
        var Pos=Java.loadClass('net.minecraft.core.BlockPos');
        var Source=Java.loadClass('appeng.me.helpers.MachineSource');
        var Key=Java.loadClass('appeng.api.stacks.AEItemKey');
        var cpu=ctx.source.level.getBlockEntity(new Pos(1,65,0));
        ctx.source.level.getBlockEntity(new Pos(0,65,1)).getInternalInventory().setItemDirect(0,Item.of('ae2:item_storage_cell_256k'));
        Java.loadClass('org.gtlcore.test.EnginePerfProbe').start(cpu.getMainNode().getNode().getGrid(),ctx.source.level,
            new Source(cpu),Key['of(net.minecraft.world.item.ItemStack)'](Item.of('minecraft:paper')));
        return 1;
    }));
});
