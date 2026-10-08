package org.gtlcore.test;

import org.gtlcore.gtlcore.api.crafting.IAutoExpandSettings;
import org.gtlcore.gtlcore.common.machine.multiblock.electric.TransfiniteComputationArrayMachine;
import org.gtlcore.gtlcore.integration.ae2.crafting.*;
import org.gtlcore.gtlcore.integration.ae2.graph.*;

import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
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
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import com.google.common.collect.ImmutableSet;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Future;

/** Local only: real storage/crafting routing with shared, interleaved physical returns. */
public final class ReturnRoutingProbe implements ICraftingProvider, IPatternProviderAutoExpand, IAutoExpandSettings {

    static ReturnRoutingProbe active;
    final TransfiniteComputationArrayMachine array;
    final IGrid grid;
    final Map<AEKey, Long> stock = new LinkedHashMap<>(), initial = new HashMap<>(), consumed = new HashMap<>(), produced = new HashMap<>(), delivered = new HashMap<>();
    final List<IPatternDetails> patterns = new ArrayList<>();
    final List<Flight> flights = new ArrayList<>();
    final List<Order> orders = new ArrayList<>();
    final Random random = new Random(25092026);
    final AEKey raw, hammer, plate, target;
    NetworkCraftingProviders registry;
    IGridNode mounted;
    IStorageProvider store;
    int ticks, pushes, reloads, completed, normalCpuOrders, synchronousReturns, catalogRefreshes;
    long surplusReturns;

    static void check(boolean b, String s) {
        if (!b) throw new AssertionError(s);
    }

    static void log(String s) {
        System.out.println(s);
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of("return-routing.log"), s + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void start(Level level) throws Exception {
        check(active == null, "already active");
        TransfiniteComputationArrayMachine array = null;
        for (int x = 100; x < 122; x++) for (int y = 65; y < 88; y++) for (int z = 0; z < 22; z++) if (MetaMachine.getMachine(level, new BlockPos(x, y, z)) instanceof TransfiniteComputationArrayMachine m) array = m;
        check(array != null && array.isOperational(), "formed array missing");
        active = new ReturnRoutingProbe(array);
        active.install();
        log("[Return Routing] START native+array shared catalyst, coproducts, network insert, concurrent orders and reload");
    }

    public static void tick() {
        if (active == null) return;
        try {
            if (active.advance()) {
                active.remove();
                active = null;
            }
        } catch (Throwable t) {
            log("[Return Routing] FAIL " + t);
            t.printStackTrace();
            try {
                for (var o : active.orders) if (o.link != null) o.link.cancel();
                active.remove();
            } catch (Exception ignored) {}
            active = null;
        }
    }

    ReturnRoutingProbe(TransfiniteComputationArrayMachine array) {
        this.array = array;
        this.grid = array.getGrid();
        String prefix = UUID.randomUUID().toString();
        raw = LongExecutionProbe.key(prefix + "raw");
        hammer = LongExecutionProbe.key(prefix + "hammer");
        plate = LongExecutionProbe.key(prefix + "plate");
        target = LongExecutionProbe.key(prefix + "double_plate");
        pattern(Map.of(raw, 1L, hammer, 1L), Map.of(plate, 1L, hammer, 1L));
        pattern(Map.of(plate, 2L, hammer, 1L), Map.of(target, 1L, hammer, 1L));
        stock.put(raw, 1000000L);
        stock.put(hammer, 4096L);
        initial.putAll(stock);
    }

    void pattern(Map<AEKey, Long> in, Map<AEKey, Long> out) {
        var encoded = PatternDetailsHelper.encodeProcessingPattern(in.entrySet().stream().map(e -> new GenericStack(e.getKey(), e.getValue())).toArray(GenericStack[]::new), out.entrySet().stream().sorted(Comparator.comparingInt(e -> e.getKey().equals(hammer) ? 1 : 0)).map(e -> new GenericStack(e.getKey(), e.getValue())).toArray(GenericStack[]::new));
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
                if (!Set.of(raw, hammer, plate, target).contains(k)) return 0;
                if (a == Actionable.MODULATE) stock.merge(k, n, Math::addExact);
                return n;
            }
        }, 100);
        grid.getStorageService().addGlobalStorageProvider(store);
        mounted = (IGridNode) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { IGridNode.class }, (p, m, a) -> switch (m.getName()) {
            case "getService" -> a[0] == ICraftingProvider.class ? this : null;
            case "getGrid" -> grid;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "Return routing fixture";
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

    boolean advance() throws Exception {
        ticks++;
        if (ticks >= 10 && ticks % 7 == 3 && orders.size() < 16) {
            Order o = new Order(orders.size() % 2 == 0 ? target : plate, 71 + orders.size());
            orders.add(o);
            o.future = grid.getCraftingService().beginCraftingCalculation(array.getLevel(), () -> array.getActionSource(), o.key, o.amount, CalculationStrategy.REPORT_MISSING_ITEMS);
        }
        for (var o : orders) if (o.link == null && o.future != null && o.future.isDone()) {
            var plan = o.future.get();
            check(!plan.simulation(), "unexpected missing inputs");
            ICraftingCPU cpu = array.getCapacityCpu();
            if (normalCpuOrders < 3) for (var c : grid.getCraftingService().getCpus()) if (c instanceof CraftingCPUCluster && !c.isBusy() && c.getAvailableStorage() >= plan.bytes()) {
                cpu = c;
                break;
            }
            var submitted = grid.getCraftingService().submitJob(plan, o, cpu, false, array.getActionSource());
            check(submitted.successful(), "submit " + submitted.errorCode());
            o.link = submitted.link();
            o.future = null;
            if (cpu instanceof CraftingCPUCluster) normalCpuOrders++;
        }
        if (ticks % 11 == 0) {
            registry.removeProvider(mounted);
            registry.addProvider(mounted);
            catalogRefreshes++;
        }
        Collections.shuffle(flights, random);
        for (var it = flights.iterator(); it.hasNext();) {
            var f = it.next();
            if (ticks < f.due) continue;
            var keys = new ArrayList<>(f.output.keySet());
            Collections.shuffle(keys, random);
            for (AEKey key : keys) {
                long n = Math.min(f.output.get(key), 1 + random.nextInt(13));
                networkReturn(key, n);
                if (n == f.output.get(key)) f.output.remove(key);
                else f.output.put(key, f.output.get(key) - n);
                if (random.nextBoolean()) break;
            }
            if (f.output.isEmpty()) it.remove();
        }
        if (ticks % 17 == 0) {
            array.forEachActiveCpu(cpu -> {
                var logic = cpu.getCraftingLogic();
                if (logic.getLastLink() != null) {
                    var data = new CompoundTag();
                    logic.writeToNbt(data);
                    logic.readFromNbt(data);
                    ((CraftingService) grid.getCraftingService()).addLink((appeng.crafting.CraftingLink) logic.getLastLink());
                    reloads++;
                }
            });
            for (var cpu : grid.getCraftingService().getCpus()) if (cpu instanceof CraftingCPUCluster c && c.isBusy()) {
                var graph = ((GraphCpuAccess) c.craftingLogic).gtlcore$graphController();
                if (graph.ownsTask()) {
                    var data = new CompoundTag();
                    graph.write(data);
                    graph.read(data);
                    ((CraftingService) grid.getCraftingService()).addLink(graph.link());
                    reloads++;
                }
            }
        }
        completed = 0;
        for (var o : orders) if (o.link != null && o.link.isDone()) {
            check(!o.link.isCanceled() && o.accepted == o.amount, "inexact completed order");
            completed++;
        }
        if (completed == 16 && flights.isEmpty()) {
            check(normalCpuOrders > 0, "native CPU coverage missing");
            for (AEKey key : List.of(raw, hammer, plate, target)) {
                long expected = initial.getOrDefault(key, 0L) + produced.getOrDefault(key, 0L) - consumed.getOrDefault(key, 0L);
                check(expected == stock.getOrDefault(key, 0L) + delivered.getOrDefault(key, 0L), "conservation failure " + key);
            }
            check(stock.getOrDefault(hammer, 0L) == 4096, "lost retained hammers");
            log("[Return Routing] PASS completed=" + completed + " native_orders=" + normalCpuOrders + " ticks=" + ticks + " pushes=" + pushes + " reloads=" + reloads + " synchronous_returns=" + synchronousReturns + " catalog_refreshes=" + catalogRefreshes + " hammers=" + stock.get(hammer) + " legitimate_surplus=" + surplusReturns);
            return true;
        }
        check(ticks < 2400, "timeout completed=" + completed + " flights=" + flights.size() + " stored=" + stock);
        return false;
    }

    void networkReturn(AEKey k, long n) {
        long can = ((CraftingService) grid.getCraftingService()).insertIntoCpus(k, n, Actionable.SIMULATE);
        check(can == 0 || grid.getCraftingService().isRequesting(k), "request index lost live return: " + k);
        String waiting = "";
        for (var cpu : grid.getCraftingService().getCpus()) if (cpu instanceof CraftingCPUCluster c) waiting += " native=" + c.craftingLogic.getWaitingFor(k);
        waiting += " array=" + array.getRequestedAmount(k);
        long before = stock.getOrDefault(k, 0L), got = grid.getStorageService().getInventory().insert(k, n, Actionable.MODULATE, array.getActionSource());
        check(got == n, "network refused physical return");
        check(stock.getOrDefault(k, 0L) - before == n - can, "live return escaped CPU into storage: key=" + (k.equals(hammer) ? "hammer" : k.equals(plate) ? "plate" : "target") + " offered=" + n + " cpu_simulated=" + can + " stored_before=" + before + " after=" + stock.getOrDefault(k, 0L) + " tick=" + ticks + " waiting_before=" + waiting);
        surplusReturns += n - can;
    }

    public List<IPatternDetails> getAvailablePatterns() {
        return patterns;
    }

    public boolean isBusy() {
        return false;
    }

    public boolean pushPattern(IPatternDetails p, KeyCounter[] inputs) {
        var first = p.getInputs()[0];
        long runs = inputs[0].get(first.getPossibleInputs()[0].what()) / Math.multiplyExact(first.getPossibleInputs()[0].amount(), first.getMultiplier());
        for (var slot : inputs) for (var e : slot) consumed.merge(e.getKey(), e.getLongValue(), Math::addExact);
        var output = new LinkedHashMap<AEKey, Long>();
        for (var o : p.getOutputs()) {
            long n = Math.multiplyExact(o.amount(), runs);
            output.put(o.what(), n);
            produced.merge(o.what(), n, Math::addExact);
        }
        if (pushes++ % 3 == 0) {
            var key = new ArrayList<>(output.keySet()).get(random.nextInt(output.size()));
            networkReturn(key, output.remove(key));
            synchronousReturns++;
        }
        if (!output.isEmpty()) flights.add(new Flight(ticks + 1 + random.nextInt(12), output));
        return true;
    }

    public long gtlcore$getMaxPatternOperations(IPatternDetails p, long requested) {
        return Math.min(requested, 37);
    }

    public long gtlcore$findMaxOperationsForTarget(PatternProviderTarget t, BlockEntity be, Direction side, KeyCounter inputs, long requested) {
        return requested;
    }

    public boolean isPatternAutoExpand() {
        return true;
    }

    public void setPatternAutoExpand(boolean enabled) {}

    record Flight(int due, Map<AEKey, Long> output) {}

    final class Order implements ICraftingRequester {

        final AEKey key;
        final long amount;
        long accepted;
        Future<ICraftingPlan> future;
        ICraftingLink link;

        Order(AEKey key, long amount) {
            this.key = key;
            this.amount = amount;
        }

        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            return link == null ? ImmutableSet.of() : ImmutableSet.of(link);
        }

        public long insertCraftedItems(ICraftingLink l, AEKey k, long n, Actionable a) {
            check(k.equals(key), "wrong requester target");
            long got = Math.min(n, amount - accepted);
            if (a == Actionable.MODULATE) {
                accepted += got;
                delivered.merge(k, got, Math::addExact);
            }
            return got;
        }

        public void jobStateChange(ICraftingLink l) {}

        public IGridNode getActionableNode() {
            return array.getActionSource().machine().orElseThrow().getActionableNode();
        }
    }
}
