// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import appeng.api.networking.*;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.security.*;
import appeng.api.stacks.AEItemKey;
import com.mojang.brigadier.arguments.LongArgumentType;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.*;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import java.util.*;

@Mod("aedump")
public final class AeDump {
    public AeDump() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, DumpConfig.SPEC);
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class, IExtensionPoint.DisplayTest.IGNORE_ALL_VERSION);
        MinecraftForge.EVENT_BUS.register(this);
        DumpManager.LOG.info("AE Network Dump installed. /ae dump exports the full network of your last preview; archives: logs/ae-dump");
    }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent e) {
        if (e.phase == TickEvent.Phase.END) DumpManager.guard(DumpManager::tick);
    }
    @SubscribeEvent public void stop(ServerStoppedEvent e) { DumpManager.clear(); }
    @SubscribeEvent public void commands(RegisterCommandsEvent e) {
        var dump = Commands.literal("dump").executes(c -> export(c.getSource(), 0, false))
                .then(Commands.literal("latest").requires(s -> s.hasPermission(2)).executes(c -> export(c.getSource(), 0, true)))
                .then(Commands.literal("status").executes(c -> { reply(c.getSource(), DumpManager.status()); return 1; }))
                .then(Commands.literal("list").executes(c -> {
                    var source = c.getSource(); var list = DumpManager.records(owner(source), source.hasPermission(2));
                    if (list.isEmpty()) reply(source, "没有已保留的预览。安装后重新计算一次，再执行 /ae dump。");
                    for (var r : list) reply(source, "#" + r.id + " " + r.request.get("engine") + " " + r.result + " 数量=" + r.request.get("amount"));
                    return list.size();
                }))
                .then(Commands.argument("id", LongArgumentType.longArg(1)).executes(c -> export(c.getSource(), LongArgumentType.getLong(c, "id"), c.getSource().hasPermission(2))))
                .then(Commands.literal("network").requires(s -> s.hasPermission(2)).executes(c -> {
                    var s = c.getSource(); var p = s.getPlayerOrException();
                    if (!(p.pick(8, 0, false) instanceof BlockHitResult hit)) { reply(s, "请看向要导出的 ME 网络方块。"); return 0; }
                    return network(s, hit.getBlockPos());
                }).then(Commands.argument("position", BlockPosArgument.blockPos()).requires(s -> s.hasPermission(2))
                        .executes(c -> network(c.getSource(), BlockPosArgument.getLoadedBlockPos(c, "position")))));
        var root = e.getDispatcher().register(Commands.literal("ae").then(dump));
        e.getDispatcher().register(Commands.literal("aedump").executes(c -> export(c.getSource(), 0, false)).redirect(root.getChild("dump")));
    }
    private static UUID owner(CommandSourceStack s) { return s.getEntity() instanceof net.minecraft.server.level.ServerPlayer p ? p.getUUID() : null; }
    private static void reply(CommandSourceStack s, String text) { s.getServer().execute(() -> s.sendSuccess(() -> Component.literal(text), false)); }
    private static int export(CommandSourceStack s, long id, boolean all) {
        var r = DumpManager.find(owner(s), all, id);
        if (r == null) { reply(s, "没有匹配的预览。先重新计算；服务器控制台可用 /ae dump latest。或看向网络执行 /ae dump network。"); return 0; }
        return DumpManager.export(r, "manual", text -> reply(s, text)) ? 1 : 0;
    }
    private static int network(CommandSourceStack s, BlockPos pos) {
        if (!s.getLevel().hasChunkAt(pos)) { reply(s, "目标区块未加载。"); return 0; }
        Object be = s.getLevel().getBlockEntity(pos);
        IGridNode node = node(be);
        if (node == null) node = node(Reflect.call(be, "getMetaMachine"));
        if (node == null) { reply(s, "该方块没有可用的 ME 网络节点。"); return 0; }
        var player = s.getEntity() instanceof net.minecraft.world.entity.player.Player p ? p : null;
        IActionSource action = player != null ? IActionSource.ofPlayer(player) : IActionSource.empty();
        var r = DumpManager.begin(node.getGrid(), s.getLevel(), action, AEItemKey.of(Items.AIR), 0,
                CalculationStrategy.REPORT_MISSING_ITEMS, "NETWORK_ONLY", false);
        if (r == null) { reply(s, "AE dump 已禁用或采集队列已满。"); return 0; }
        r.result = "NETWORK_ONLY"; r.done = true;
        return DumpManager.export(r, "manual-network", text -> reply(s, text)) ? 1 : 0;
    }
    private static IGridNode node(Object value) {
        if (value instanceof IActionHost host) return host.getActionableNode();
        if (value instanceof IInWorldGridNodeHost host) {
            for (var d : Direction.values()) { var n = host.getGridNode(d); if (n != null) return n; }
            return host.getGridNode(null);
        }
        Object main = Reflect.call(value, "getMainNode"); return main instanceof IManagedGridNode n ? n.getNode() : null;
    }
}
