package org.gtlcore.localsubmit;

import appeng.api.crafting.*;
import appeng.api.stacks.*;
import appeng.crafting.pattern.AECraftingPattern;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;
import com.gregtechceu.gtceu.api.item.IGTTool;
import com.gregtechceu.gtceu.api.item.capability.ElectricItem;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import net.minecraft.commands.Commands;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.cgse.core.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.TimeUnit;
import io.netty.buffer.Unpooled;

/** Native confirmation rows and packets. Compile against the remapped host jar. */
@Mod("gtlsubmissionprobe")
public final class ToolPreviewProbe {
    static int checks;
    public ToolPreviewProbe() { MinecraftForge.EVENT_BUS.addListener(this::register); }
    void register(RegisterCommandsEvent event) {
        for (String command : List.of("toolpreviewbaseline", "toolpreviewfixed"))
            event.getDispatcher().register(Commands.m_82127_(command).requires(s -> s.m_6761_(4)).executes(c -> {
                try { run(c.getSource().m_81372_(), command.endsWith("fixed")); return 1; }
                catch (Throwable t) { System.out.println("[Submission Probe] FAIL " + t); t.printStackTrace(); return 0; }
            }));
    }
    static void check(boolean value, String text) { if (!value) throw new AssertionError(text); checks++; }
    static void info(String text) { System.out.println("[Tool Preview] " + text); }
    static AEItemKey key(ItemStack value) { return Objects.requireNonNull(AEItemKey.of(value)); }
    static Item item(String id) { return Objects.requireNonNull(ForgeRegistries.ITEMS.getValue(new ResourceLocation(id))); }
    static ItemStack worn(ItemStack value, int damage) { var copy = value.m_41777_(); copy.m_41721_(damage); copy.m_41776_(); return copy; }
    static boolean tool(AEKey key) { return key instanceof AEItemKey item && item.getItem() instanceof IGTTool; }
    static Object extra(CraftingPlanSummaryEntry row, String method) throws Exception { return row.getClass().getMethod(method).invoke(row); }

    static void run(ServerLevel level, boolean fixed) throws Exception {
        CraftingRecipe bender = null;
        for (var recipe : level.m_7465_().m_44051_())
            if (recipe instanceof CraftingRecipe c && recipe.m_8043_(level.m_9598_()).m_41720_() == item("gtceu:lv_bender")) { bender = c; break; }
        check(bender != null, "Native bender recipe");
        var frame = new ItemStack[9]; Arrays.fill(frame, ItemStack.f_41583_);
        int width = bender instanceof ShapedRecipe s ? s.m_44220_() : 3;
        ItemStack iron = ((IGTTool)item("gtceu:iron_wrench")).get();
        int toolSlot = -1;
        for (int i = 0; i < bender.m_7527_().size(); i++) {
            var ingredient = bender.m_7527_().get(i); var choices = ingredient.m_43908_();
            if (choices.length == 0) continue;
            int index = i / width * 3 + i % width;
            frame[index] = choices[0].m_41777_();
            if (ingredient.test(iron)) { frame[index] = iron.m_41777_(); toolSlot = index; }
        }
        check(toolSlot >= 0, "Iron wrench accepted by real recipe");
        var pattern = new AECraftingPattern(key(PatternDetailsHelper.encodeCraftingPattern(bender, frame,
                bender.m_8043_(level.m_9598_()), true, false)), level);
        var stock = new LinkedHashMap<AEKey, Long>();
        for (var input : pattern.getInputs()) {
            var first = input.getPossibleInputs()[0];
            stock.merge(first.what(), first.amount() * input.getMultiplier() * (tool(first.what()) ? 1 : 100), Math::addExact);
        }
        var owned = stock.keySet().stream().filter(ToolPreviewProbe::tool).findFirst().orElseThrow();
        var captured = capture(pattern, stock, level);
        var catalog = new CapturedPatternCatalog(List.of(new CapturedPatternCatalog.Entry(pattern,
                PatternFingerprint.capture(pattern), 0, captured)), captured.size());
        var budget = new PlanningBudget(0, 40_000_000, 256L << 20, () -> false, System::nanoTime);
        CapturedPatternCatalog.Prepared prepared;
        GraphPlan<AEKey> plan;
        try (var scheduler = new PlanningScheduler(1, 4, 4096, 2_000_000)) {
            prepared = scheduler.submit(catalog.build(budget), budget).get(30, TimeUnit.SECONDS);
            var work = new RequestPlanningWork<>(prepared.compiler(), pattern.getPrimaryOutput().what(), 100,
                    stock, Set.of(), null, false, false, "REPORT_MISSING_ITEMS", CatalystPolicy.MINIMAL, false, true,
                    budget, () -> false, r -> { throw new AssertionError("Unexpected fallback"); }).capturedCatalog(prepared.candidates());
            plan = scheduler.submit(work, budget).get(30, TimeUnit.SECONDS);
        }
        check(plan.feasible(), "Native 100-use request feasible");
        check(plan.initial().get(owned) == 1, "Exactly one physical wrench extracted");
        var ae = new AeGraphPlan(plan, prepared.bindings(), Set.of(), stock);
        if (fixed) {
            var ring = ae.display();
            var graphRows = new ArrayList<GraphRingView.Row>();
            int offset = 0;
            do {
                var page = ring.page(offset);
                graphRows.addAll(page.rows());
                offset += page.rows().size();
                if (offset >= page.total()) break;
            } while (true);
            var grouped = GraphRingProjection.of(graphRows, plan.target());
            var recipes = grouped.rows().stream().filter(r -> r.kind() == GraphRingView.Kind.RECIPE).toList();
            check(recipes.size() == 1 && recipes.get(0).count().equals(BigInteger.valueOf(100)), "Ring merges 100 equivalent wear-state recipe nodes with exact total count");
            check(grouped.rows().stream().filter(r -> r.kind() == GraphRingView.Kind.RESOURCE && tool(r.icon().what())).count() == 1,
                    "Ring has one real initial tool node");
            check(grouped.aliases().size() == plan.patternTimesExact().size(), "All exact recipe IDs retain a display alias for execution tab");
        }
        var runtime = new GraphJobRuntime<>(plan, plan.initial(), Map.of());
        var before = GraphJobCodec.write(runtime.snapshot());
        var rows = GraphPlanSummary.entries(ae);
        long tools = rows.stream().filter(r -> tool(r.getWhat())).count();
        long made = rows.stream().filter(r -> tool(r.getWhat())).mapToLong(CraftingPlanSummaryEntry::getCraftAmount).sum();
        info("fixed=" + fixed + " native100 rows=" + rows.size() + " toolRows=" + tools + " toolsCrafted=" + made +
                " extracted=" + plan.initial().get(owned) + " exactTransitions=" + plan.recipes().size() + " work=" + budget.searchWork());
        check(fixed ? tools == 1 && made == 0 : tools == 101 && made == 100, "100 returned states are not 100 manufactured tools");
        check(before.equals(GraphJobCodec.write(runtime.snapshot())), "Summary never changes execution snapshot");
        var inventory = new LinkedHashMap<AEKey, Long>(stock);
        var cursor = new PlanCursor(plan.steps());
        for (var batch = cursor.current(); batch != null; batch = cursor.current()) {
            var recipe = plan.recipes().get(batch.recipe());
            for (long run = 0; run < batch.runs(); run++) {
                for (var input : recipe.inputs().entrySet()) {
                    check(inventory.getOrDefault(input.getKey(), 0L) >= input.getValue(), "Exact physical input owned");
                    inventory.merge(input.getKey(), -input.getValue(), Long::sum);
                }
                var toolKey = recipe.inputs().keySet().stream().filter(ToolPreviewProbe::tool).findFirst().orElseThrow();
                var nativeInput = pattern.getInputs()[recipe.slots().stream().filter(s -> tool(s.key())).findFirst().orElseThrow().inputSlot()];
                check(recipe.outputs().containsKey(nativeInput.getRemainingKey(toolKey)), "Actual GT remainder matches exact successor");
                recipe.outputs().forEach((k, n) -> inventory.merge(k, n, Long::sum));
            }
            cursor.dispatched(batch.runs());
        }
        check(inventory.get(plan.target()) == 100, "100 physical products delivered by replay");
        if (fixed) {
            for (var row : rows) packet(row);
            grouping(level, pattern, iron, plan.target());
        }
        System.out.println("[Submission Probe] ALL PASS assertions=" + checks);
    }

    static CapturedPattern capture(IPatternDetails pattern, Map<AEKey, Long> stock, ServerLevel level) throws Exception {
        var type = Class.forName("org.gtlcore.gtlcore.integration.ae2.graph.GtlPatternCatalog$CandidateCapture");
        var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
        var pool = new KeyCounter(); stock.forEach(pool::add);
        var capture = constructor.newInstance(pattern, PatternFingerprint.capture(pattern), pool, level, new PlanningBudget(0, 40_000_000, () -> false));
        var step = type.getDeclaredMethod("step"); step.setAccessible(true);
        int slices = 0;
        while (!(Boolean)step.invoke(capture)) check(++slices < 20000, "Bounded native capture");
        var result = type.getDeclaredMethod("result"); result.setAccessible(true);
        return (CapturedPattern)result.invoke(capture);
    }

    static void grouping(ServerLevel level, IPatternDetails pattern, ItemStack iron, AEKey product) throws Exception {
        AEKey fresh = key(iron), used = key(worn(iron, 5)), older = key(worn(iron, 9));
        var originalFresh = fresh.toTag().m_6426_(); var originalUsed = used.toTag().m_6426_();
        var recipe = new GraphRecipe<AEKey>("use", "use", List.of(new GraphRecipe.Slot<>(used, 1)), Map.of(product, 1L, older, 1L));
        var manufacture = new GraphRecipe<AEKey>("make", "make", List.of(new GraphRecipe.Slot<>(AEItemKey.of(item("minecraft:iron_ingot")), 1)), Map.of(fresh, 1L));
        var makePattern = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(AEItemKey.of(item("minecraft:iron_ingot")), 1)}, new GenericStack[]{new GenericStack(fresh, 1)}), level);
        var graph = new GraphPlan<>(product, 2, false, new PlanStep.Sequence(List.of(new PlanStep.Batch("use", 2), new PlanStep.Batch("make", 2))),
                Map.of("use", recipe, "make", manufacture), Map.of(fresh, 2L, used, 3L), Map.of(used, 1L),
                Map.of(fresh, 1L, used, 1L), GraphPlan.Result.MISSING_INPUT, 0, 0);
        var view = new AeGraphPlan(graph, Map.of("use", pattern, "make", makePattern), Set.of(), Map.of(fresh, 1L, used, 2L));
        var rows = GraphPlanSummary.entries(view);
        var tools = rows.stream().filter(r -> tool(r.getWhat())).toList();
        check(tools.size() == 1, "Mixed wear and new manufacture merge into one tool row");
        var row = tools.get(0);
        check(row.getStoredAmount() == 3 && row.getMissingAmount() == 2 && row.getCraftAmount() == 2, "Merged quantities count real tools only");
        check(((Number)extra(row, "gtlcore$getCraftTimes")).longValue() == 2, "Only actual tool manufacture counts as crafting rounds");
        check(((Number)extra(row, "gtlcore$getGraphSeed")).longValue() == 1 && (Boolean)extra(row, "gtlcore$isMissingGraphSeed"), "Seed totals survive grouping");
        check(new HashSet<>((List<?>)extra(row, "gtlcore$getMissingKeys")).equals(Set.of(fresh, used)), "Bookmarks preserve both exact missing keys");
        check(((Number)extra(row, "gtlcore$getGroupedToolStates")).intValue() == 2, "Grouped state tooltip");
        packet(row);
        check(fresh.toTag().equals(originalFresh) && used.toTag().equals(originalUsed), "Grouping never mutates AE key NBT");
        check(view.usedItems().get(fresh) == 1 && view.usedItems().get(used) == 2, "Exact reservation quantities preserved");
        check(view.missingItems().get(fresh) == 1 && view.missingItems().get(used) == 1, "Exact missing quantities preserved");

        var enchanted = iron.m_41777_();
        enchanted.m_41663_(Objects.requireNonNull(ForgeRegistries.ENCHANTMENTS.getValue(new ResourceLocation("minecraft:unbreaking"))), 3);
        var named = iron.m_41777_(); named.m_41714_(net.minecraft.network.chat.Component.m_237113_("named tool"));
        var other = ((IGTTool)item("gtceu:steel_wrench")).get();
        check(countGroups(pattern, product, Map.of(fresh, 1L, used, 1L, key(enchanted), 1L, key(named), 1L, key(other), 1L)) == 4,
                "Wear merges while enchantment, name and material stay distinct");
        ItemStack electric = null;
        for (var value : ForgeRegistries.ITEMS.getValues()) if (value instanceof IGTTool tool && tool.isElectric() &&
                String.valueOf(ForgeRegistries.ITEMS.getKey(value)).contains("wrench")) { electric = tool.get(); break; }
        check(electric != null, "Native electric wrench exists");
        var charged = electric.m_41777_(); var drained = electric.m_41777_();
        ((ElectricItem)GTCapabilityHelper.getElectricItem(charged)).setCharge(20000);
        ((ElectricItem)GTCapabilityHelper.getElectricItem(drained)).setCharge(10000);
        check(countGroups(pattern, product, Map.of(key(charged), 1L, key(drained), 1L)) == 1, "Charge states merge");
        var wide = new GraphPlan<>(product, 1, false, new PlanStep.Batch("use", 1), Map.of("use", recipe),
                Map.of(fresh, BigInteger.valueOf(Long.MAX_VALUE), used, BigInteger.valueOf(Long.MAX_VALUE)), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var wideRows = GraphPlanSummary.entries(new AeGraphPlan(wide, Map.of("use", pattern), Set.of(), Map.of()));
        check(wideRows.stream().filter(r -> tool(r.getWhat())).findFirst().orElseThrow().getStoredAmount() == Long.MAX_VALUE,
                "Group exact totals before UI saturation");
        var target = new GraphPlan<>(older, 1, false, new PlanStep.Batch("use", 1), Map.of("use", recipe),
                Map.of(used, 1L), Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        var targetRows = GraphPlanSummary.entries(new AeGraphPlan(target, Map.of("use", pattern), Set.of(), target.initial()));
        check(targetRows.stream().anyMatch(r -> r.getWhat().equals(older) && r.getCraftAmount() == 1), "Explicit worn target is visible");
        var byproduct = PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                new GenericStack[]{new GenericStack(fresh, 1)}, new GenericStack[]{new GenericStack(product, 1), new GenericStack(older, 1)}), level);
        var outputRows = GraphPlanSummary.entries(new AeGraphPlan(target, Map.of("use", byproduct), Set.of(), target.initial()));
        check(outputRows.stream().anyMatch(r -> r.getWhat().equals(older) && r.getCraftAmount() == 1), "Explicit processing tool output is retained");
        info("grouping, shortages, bookmarks, seed, electric, enchantment, manufacture, target and packet checks passed");
    }

    static long countGroups(IPatternDetails pattern, AEKey product, Map<AEKey, Long> stock) {
        var recipe = new GraphRecipe<AEKey>("use", "use", List.of(), Map.of(product, 1L));
        var plan = new GraphPlan<>(product, 1, false, new PlanStep.Batch("use", 1), Map.of("use", recipe), stock, Map.of(), Map.of(), GraphPlan.Result.FEASIBLE, 0, 0);
        return GraphPlanSummary.entries(new AeGraphPlan(plan, Map.of("use", pattern), Set.of(), stock)).stream().filter(r -> tool(r.getWhat())).count();
    }

    static void packet(CraftingPlanSummaryEntry entry) throws Exception {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            entry.write(buffer); var copy = CraftingPlanSummaryEntry.read(buffer);
            check(buffer.readableBytes() == 0, "Packet fully consumed");
            check(copy.getWhat().equals(entry.getWhat()) && copy.getStoredAmount() == entry.getStoredAmount() &&
                    copy.getMissingAmount() == entry.getMissingAmount() && copy.getCraftAmount() == entry.getCraftAmount(), "Packet quantities preserved");
            for (String field : List.of("gtlcore$getCraftTimes", "gtlcore$getGraphSeed", "gtlcore$isMissingGraphSeed",
                    "gtlcore$getGroupedToolStates", "gtlcore$getMissingKeys"))
                check(extra(entry, field).equals(extra(copy, field)), "Packet metadata " + field);
        } finally { buffer.release(); }
    }
}
