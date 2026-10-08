package org.gtlcore.test;

import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraftforge.common.util.FakePlayerFactory;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.menu.me.crafting.*;

import java.lang.reflect.*;
import java.util.*;

/** Actual menu injections and addon summary serialization on isolated Forge. */
public final class PlanningUiProbe {

    static final class Host extends appeng.api.implementations.menuobjects.ItemMenuHost implements appeng.api.storage.ISubMenuHost, appeng.api.networking.security.IActionHost {

        final ReturnRoutingProbe fixture;

        Host(net.minecraft.world.entity.player.Player player, ReturnRoutingProbe fixture) {
            super(player, null, fixture.patterns.get(0).getDefinition().toStack());
            this.fixture = fixture;
        }

        @Override
        public appeng.api.networking.IGridNode getActionableNode() {return fixture.mounted;}
        @Override public void returnToMainMenu(net.minecraft.world.entity.player.Player player, appeng.menu.ISubMenu menu) {}
        @Override public net.minecraft.world.item.ItemStack getMainMenuIcon() {return getItemStack();}
        @Override public boolean onBroadcastChanges(net.minecraft.world.inventory.AbstractContainerMenu menu) {return true;}
    }

    static Field field(String name) throws Exception {
        var f = CraftConfirmMenu.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void run(ServerLevel level, ReturnRoutingProbe fixture) throws Exception {
        var player = FakePlayerFactory.getMinecraft(level);
        var previous = player.f_36096_;
        var menu = new CraftConfirmMenu(991, player.m_150109_(), new Host(player, fixture));
        player.f_36096_ = menu;
        try {
            var job = field("job");
            var constructor = GraphPlanningRequest.class.getDeclaredConstructor(PlanningBudget.class);
            constructor.setAccessible(true);
            field("whatToCraft").set(menu, fixture.target);
            field("amount").setInt(menu, Integer.MAX_VALUE);
            field("gtlcore$longAmount").setLong(menu, Long.MAX_VALUE);
            field("gtlcore$calculationStrategy").set(menu, CalculationStrategy.REPORT_MISSING_ITEMS);
            for (var error : List.of(new GraphPlanningFailure(GraphPlan.Result.INFEASIBLE, "checked"),
                    new GraphPlanningFailure(GraphPlan.Result.UNKNOWN, "unresolved"),
                    new PlanningBudget.Exhausted(PlanningBudget.Limit.SEARCH_LIMIT))) {
                var request = constructor.newInstance(new PlanningBudget(0, 1_000_000, () -> false));
                request.completeExceptionally(error);
                job.set(menu, request);
                menu.m_38946_();
                var view = (GraphPlanMenu) menu;
                check(view.gtlcore$planningFailure().equals(GraphPlanningFailure.messageKey(error)), "menu lost classified failure");
                check(job.get(menu) == null && menu.getPlan() == null, "failed calculation remained submit-ready");
                view.gtlcore$retryPlanning();
                Object retry = job.get(menu);
                check(retry instanceof GraphPlanningRequest && retry != request, "retry did not restart calculation");
                check(field("gtlcore$longAmount").getLong(menu) == Long.MAX_VALUE, "retry narrowed long order");
                check(field("gtlcore$calculationStrategy").get(menu) == CalculationStrategy.REPORT_MISSING_ITEMS, "retry changed missing strategy");
                view.gtlcore$retryPlanning();
                check(job.get(menu) == retry, "duplicate retry cancelled new job");
                ((GraphPlanningRequest) retry).cancel(true);
                job.set(menu, null);
                menu.clearError();
                check(view.gtlcore$planningFailure().isEmpty(), "failure not reset");
            }
        } finally {
            menu.m_6877_(player);
            player.f_36096_ = previous;
        }
        var pattern = fixture.patterns.get(0);
        String binding = PatternFingerprint.of(pattern);
        var recipe = new GraphRecipe<>(binding, binding, List.of(new GraphRecipe.Slot<>(fixture.raw, 1)), Map.of(fixture.plate, 1L, fixture.target, 1L));
        var original = new GraphPlan<>(fixture.target, 1, true, new PlanStep.Batch(binding, 1), Map.of(binding, recipe), Map.of(fixture.raw, 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        com.neuvillette.ae2ct.Config.CONFIG.setConfig(com.electronwill.nightconfig.core.CommentedConfig.inMemory());
        for (boolean fallback : new boolean[]{false,true}) for (var proof : Arrays.asList(null, new GraphPlan.SeedOptimality(0, 0, true, true, false), new GraphPlan.SeedOptimality(0, 0, false, false, true))) {
            var plan = new AeGraphPlan(original.withSeedOptimality(proof), Map.of(binding, pattern), Set.of(), Map.of(fixture.raw, 1L), fallback);
            var summary = CraftingPlanSummary.fromJob(fixture.grid, fixture.array.getActionSource(), plan);
            var copy = GraphPacketProbe.roundTrip(summary);
            check(Objects.equals(((GraphPlanSummaryView) copy).gtlcore$seedOptimality(), proof), "actual addon packet changed optimality");
            check(((GraphPlanSummaryView) copy).gtlcore$fallback() == fallback, "actual addon packet lost fallback marker");
            var legacy = CraftingPlanSummary.fromJob(fixture.grid, fixture.array.getActionSource(), plan.summaryView());
            var legacyCopy = GraphPacketProbe.roundTrip(legacy);
            check(((GraphPlanSummaryView) legacyCopy).gtlcore$graphPlanId() == null, "Legacy summary acquired graph identity");
            var tree = ((com.neuvillette.ae2ct.api.ICraftingPlanSummary) legacyCopy).getJob();
            check(tree.recipes.size() == 1 && !tree.recipes.get(0).inputs().isEmpty(), "Legacy tree lost recipes");
        }
        ReturnRoutingProbe.log("[Planning UI] PASS actual menu classified failure/retry/duplicate/long/strategy; GRAPH normal/fallback stub, LEGACY real recipes and packets");
    }
}
