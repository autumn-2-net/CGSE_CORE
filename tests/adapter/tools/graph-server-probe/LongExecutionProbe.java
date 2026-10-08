package org.gtlcore.test;

import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.TransfiniteComputationArrayMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftingCPUInterfacePartMachine;
import org.gtlcore.gtlcore.integration.ae2.crafting.IPatternProviderAutoExpand;
import org.gtlcore.gtlcore.integration.ae2.graph.*;
import org.cgse.core.*;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.helpers.patternprovider.PatternProviderTarget;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.Future;

/** Local only: accelerated machines, actual formed array, routing, links and NBT. */
public final class LongExecutionProbe {

    static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE);
    static final List<Trial> trials = new ArrayList<>();
    static int completed;

    static void log(String text) {
        System.out.println(text);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("long-execution.log"), text + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void check(boolean value, String text) {
        if (!value) throw new AssertionError(text);
    }

    public static void start(Level level) throws Exception {
        check(trials.isEmpty(), "probe already active");
        completed = 0;
        TransfiniteComputationArrayMachine array = null;
        for (int x = 100; x < 122; x++) for (int y = 65; y < 88; y++) for (int z = 0; z < 22; z++) {
            var machine = MetaMachine.getMachine(level, new BlockPos(x, y, z));
            if (machine instanceof TransfiniteComputationArrayMachine m) array = m;
            if (machine instanceof MECraftingCPUInterfacePartMachine m) m.setParallelism(256);
        }
        check(array != null && array.isOperational(), "formed array unavailable");
        check(array.getCapacityCpu().getCraftingLogic().unboundedJobStorage(), "array storage policy lost");
        for (int mode = 0; mode < 3; mode++) {
            var t = new Trial(array, mode);
            trials.add(t);
            t.install();
        }
        log("[Long Execute] START overlapping=3 target_each=" + Long.MAX_VALUE);
    }

    public static void tick() {
        for (var it = trials.iterator(); it.hasNext();) {
            var t = it.next();
            try {
                if (t.tick()) {
                    t.remove();
                    it.remove();
                    completed++;
                }
            } catch (Throwable e) {
                log("[Long Execute] FAIL mode=" + t.mode + " " + e);
                e.printStackTrace();
                try {
                    if (t.link != null) t.link.cancel();
                    t.remove();
                } catch (Exception ignored) {}
                it.remove();
            }
        }
        if (completed == 3) {
            log("[Long Execute] ALL PASS concurrent rounding, wide intermediate counts, seeded growth, real array/link/routing/NBT");
            completed++;
        }
    }

    static AEKey key(String id) {
        var tag = new CompoundTag();
        tag.m_128359_("gtl_long_execution_probe", id);
        return AEItemKey.of(net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft:paper")), tag);
    }

    static final class Trial implements ICraftingProvider, IPatternProviderAutoExpand, IAutoExpandSettings, ICraftingRequester {

        final TransfiniteComputationArrayMachine array;
        final IGrid grid;
        final int mode;
        final Map<AEKey, Long> stock = new LinkedHashMap<>();
        final Map<AEKey, BigInteger> initial = new HashMap<>(), consumed = new HashMap<>(), produced = new HashMap<>();
        final List<IPatternDetails> patterns = new ArrayList<>();
        final List<Map<AEKey, Long>> flights = new ArrayList<>();
        final AEKey raw, mid, second, target;
        BigInteger delivered = BigInteger.ZERO;
        Future<ICraftingPlan> future;
        ICraftingLink link;
        NetworkCraftingProviders registry;
        IGridNode mounted;
        IStorageProvider store;
        int ticks, pushes, reloads;
        GraphPlan<AEKey> plan;

        Trial(TransfiniteComputationArrayMachine array, int mode) {
            this.array = array;
            grid = array.getGrid();
            this.mode = mode;
            String tag = UUID.randomUUID().toString();
            raw = key(tag + "raw");
            mid = key(tag + "mid");
            second = key(tag + "second");
            target = key(tag + "target");
            if (mode == 0) {
                pattern(Map.of(raw, 1L), Map.of(target, 2L));
                stock.put(raw, Long.MAX_VALUE / 2 + 1);
            }
            if (mode == 1) {
                pattern(Map.of(raw, 1L), Map.of(mid, 32L));
                pattern(Map.of(mid, 1L), Map.of(second, 1L));
                pattern(Map.of(second, 32L), Map.of(target, 1L));
                stock.put(raw, Long.MAX_VALUE);
            }
            if (mode == 2) {
                pattern(Map.of(target, 4L), Map.of(target, 64L));
                stock.put(target, 4L);
            }
            stock.forEach((k, n) -> initial.put(k, BigInteger.valueOf(n)));
        }

        void pattern(Map<AEKey, Long> in, Map<AEKey, Long> out) {
            var encoded = PatternDetailsHelper.encodeProcessingPattern(in.entrySet().stream().map(e -> new GenericStack(e.getKey(), e.getValue())).toArray(GenericStack[]::new), out.entrySet().stream().map(e -> new GenericStack(e.getKey(), e.getValue())).toArray(GenericStack[]::new));
            patterns.add(PatternDetailsHelper.decodePattern(encoded, array.getLevel()));
        }

        void install() throws Exception {
            store = mounts -> mounts.mount(new MEStorage() {

                public net.minecraft.network.chat.Component getDescription() {
                    return target.getDisplayName();
                }

                public void getAvailableStacks(KeyCounter out) {
                    stock.forEach(out::add);
                }

                public long extract(AEKey k, long n, Actionable a, IActionSource s) {
                    long got = Math.min(n, stock.getOrDefault(k, 0L));
                    if (a == Actionable.MODULATE) stock.put(k, stock.getOrDefault(k, 0L) - got);
                    return got;
                }

                public long insert(AEKey k, long n, Actionable a, IActionSource s) {
                    if (!initial.containsKey(k) && !k.equals(target) && !k.equals(mid) && !k.equals(second)) return 0;
                    long got = Math.min(n, Long.MAX_VALUE - stock.getOrDefault(k, 0L));
                    if (a == Actionable.MODULATE) stock.merge(k, got, Math::addExact);
                    return got;
                }
            });
            grid.getStorageService().addGlobalStorageProvider(store);
            mounted = (IGridNode) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IGridNode.class }, (p, m, a) -> switch (m.getName()) {
                case "getService" -> a[0] == ICraftingProvider.class ? this : null;
                case "getGrid" -> grid;
                case "hashCode" -> System.identityHashCode(p);
                case "equals" -> p == a[0];
                case "toString" -> "Long execution fixture " + mode;
                default -> null;
            });
            var field = CraftingService.class.getDeclaredField("craftingProviders");
            field.setAccessible(true);
            registry = (NetworkCraftingProviders) field.get(grid.getCraftingService());
            registry.addProvider(mounted);
        }

        void remove() {
            registry.removeProvider(mounted);
            grid.getStorageService().removeGlobalStorageProvider(store);
        }

        boolean tick() throws Exception {
            ticks++;
            if (ticks == 10) future = grid.getCraftingService().beginCraftingCalculation(array.getLevel(), () -> array.getActionSource(), target, Long.MAX_VALUE, CalculationStrategy.REPORT_MISSING_ITEMS);
            if (link == null && future != null && future.isDone()) {
                var calculated = (AeGraphPlan) future.get();
                check(!calculated.simulation(), "large plan missing inputs");
                plan = calculated.graph();
                check(calculated.exactBytes().compareTo(MAX) > 0, "fixture did not exceed logical byte long range");
                var result = grid.getCraftingService().submitJob(calculated, this, array.getCapacityCpu(), false, array.getActionSource());
                check(result.successful(), "array rejected " + result.errorCode());
                link = result.link();
                future = null;
                log("[Long Execute] accepted mode=" + mode + " exact_bytes=" + calculated.exactBytes());
            }
            var service = (CraftingService) grid.getCraftingService();
            for (int i = flights.size() - 1; i >= 0; i--) {
                var f = flights.get(i);
                for (AEKey k : new ArrayList<>(f.keySet())) {
                    long offer = Math.min(f.get(k), Long.MAX_VALUE / 7), taken = service.insertIntoCpus(k, offer, Actionable.MODULATE);
                    check(taken == offer, "routing refused " + taken + "/" + offer + " mode=" + mode);
                    if (taken == f.get(k)) f.remove(k);
                    else f.put(k, f.get(k) - taken);
                }
                if (f.isEmpty()) flights.remove(i);
            }
            if (link != null && !link.isDone() && ticks % 13 == 0) {
                array.forEachActiveCpu(cpu -> {
                    if (cpu.getCraftingLogic().getLastLink() != null && cpu.getCraftingLogic().getLastLink().getCraftingID().equals(link.getCraftingID())) {
                        var data = new CompoundTag();
                        cpu.getCraftingLogic().writeToNbt(data);
                        var checkSaved = new GraphJobRuntime<>(GraphJobCodec.read(data.m_128469_(GraphJobCodec.NBT_KEY)));
                        check(checkSaved.remainingDelivery() == Long.MAX_VALUE - delivered.longValueExact(), "NBT delivery remainder lost");
                        cpu.getCraftingLogic().readFromNbt(data);
                        service.addLink((appeng.crafting.CraftingLink) cpu.getCraftingLogic().getLastLink());
                        reloads++;
                    }
                });
            }
            if (link != null && link.isDone()) {
                check(!link.isCanceled() && delivered.equals(MAX) && flights.isEmpty(), "completion before exact delivery");
                var all = new HashSet<>(initial.keySet());
                all.addAll(consumed.keySet());
                all.addAll(produced.keySet());
                for (AEKey k : all) {
                    var amount = initial.getOrDefault(k, BigInteger.ZERO).add(produced.getOrDefault(k, BigInteger.ZERO)).subtract(consumed.getOrDefault(k, BigInteger.ZERO));
                    check(amount.equals(BigInteger.valueOf(stock.getOrDefault(k, 0L)).add(k.equals(target) ? delivered : BigInteger.ZERO)), "physical conservation failed " + k);
                }
                log("[Long Execute] PASS mode=" + mode + " delivered=" + delivered + " ticks=" + ticks + " pushes=" + pushes + " reloads=" + reloads + " refunds=" + stock.getOrDefault(target, 0L));
                return true;
            }
            check(ticks < 2400, "execution timeout pushes=" + pushes + " delivered=" + delivered + " pending_returns=" + flights.size());
            return false;
        }

        public List<IPatternDetails> getAvailablePatterns() {
            return patterns;
        }

        public boolean isBusy() {
            return false;
        }

        public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
            var first = pattern.getInputs()[0];
            long perRun = Math.multiplyExact(first.getPossibleInputs()[0].amount(), first.getMultiplier());
            long runs = inputs[0].get(first.getPossibleInputs()[0].what()) / perRun;
            check(runs > 0, "zero batch");
            for (int i = 0; i < inputs.length; i++) {
                var base = pattern.getInputs()[i].getPossibleInputs()[0];
                check(inputs[i].get(base.what()) == Math.multiplyExact(Math.multiplyExact(base.amount(), pattern.getInputs()[i].getMultiplier()), runs), "wrong input quantity");
            }
            for (var slot : inputs) for (var e : slot) consumed.merge(e.getKey(), BigInteger.valueOf(e.getLongValue()), BigInteger::add);
            var f = new LinkedHashMap<AEKey, Long>();
            for (var o : pattern.getOutputs()) {
                long n = Math.multiplyExact(o.amount(), runs);
                f.put(o.what(), n);
                produced.merge(o.what(), BigInteger.valueOf(n), BigInteger::add);
            }
            flights.add(f);
            pushes++;
            return true;
        }

        public long gtlcore$getMaxPatternOperations(IPatternDetails p, long requested) {
            return Math.min(requested, Long.MAX_VALUE / 17);
        }

        public long gtlcore$findMaxOperationsForTarget(PatternProviderTarget t, BlockEntity be, Direction side, KeyCounter inputs, long requested) {
            return requested;
        }

        public boolean isPatternAutoExpand() {
            return true;
        }

        public void setPatternAutoExpand(boolean enabled) {}

        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return link == null ? ImmutableSet.of() : ImmutableSet.of(link);
        }

        public long insertCraftedItems(ICraftingLink l, AEKey k, long n, Actionable a) {
            check(k.equals(target), "other job delivered wrong key");
            long accepted = Math.min(n, Long.MAX_VALUE / 11);
            if (a == Actionable.MODULATE) delivered = delivered.add(BigInteger.valueOf(accepted));
            check(delivered.compareTo(MAX) <= 0, "overdelivery");
            return accepted;
        }

        public void jobStateChange(ICraftingLink l) {}

        public IGridNode getActionableNode() {
            return array.getActionSource().machine().orElseThrow().getActionableNode();
        }
    }
}
