package org.gtlcore.test;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.me.service.CraftingService;
import appeng.me.service.helpers.NetworkCraftingProviders;
import appeng.util.SettingsFrom;
import com.almostreliable.merequester.requester.RequesterBlockEntity;
import com.almostreliable.merequester.requester.Requests;
import com.almostreliable.merequester.requester.StorageManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.gtlcore.gtlcore.integration.ae2.graph.CraftingEngineRouter;

import java.lang.reflect.*;
import java.util.*;

/** Local-only fixture: real requester block, AE ticks, calculation, CPU, links and storage watchers. */
public final class RequesterStallProbe implements ICraftingProvider {
    static RequesterStallProbe active;
    final Level level;
    final RequesterBlockEntity requester;
    final IGridNode root;
    final IGrid grid;
    final int scenario;
    final String name;
    final AEKey raw, a, b;
    final Map<AEKey, Long> stock = new LinkedHashMap<>();
    final List<IPatternDetails> patterns = new ArrayList<>();
    final List<Flight> flights = new ArrayList<>();
    final Set<Object> seenPlans = Collections.newSetFromMap(new IdentityHashMap<>());
    IGridConnection connection;
    IGridNode provider;
    IStorageProvider store;
    NetworkCraftingProviders registry;
    int ticks, phase, since, pushes, nativeTicks, updates, bindings;
    int baselinePushes, blockedPushes, blockedNativeTicks, blockedUpdates;
    long consumed;
    boolean consumeImmediately, touchedImportedKey, installed;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void log(String value) {
        System.out.println("[Requester Stall] " + value);
    }

    public static void start(Level level, RequesterBlockEntity requester, IGridNode root, int scenario) {
        check(active == null, "Another requester trial is active");
        check(scenario >= 0 && scenario < 3, "scenario");
        active = new RequesterStallProbe(level, requester, root, scenario);
        log("START scenario=" + active.name + " graph=" + CraftingEngineRouter.useGraph());
    }

    RequesterStallProbe(Level level, RequesterBlockEntity requester, IGridNode root, int scenario) {
        this.level = level;
        this.requester = requester;
        this.root = root;
        this.grid = root.getGrid();
        this.scenario = scenario;
        name = List.of("pending", "reconnect", "memory_card").get(scenario);
        String id = "requester_" + UUID.randomUUID();
        raw = LongExecutionProbe.key(id + "_raw");
        a = LongExecutionProbe.key(id + "_a");
        b = LongExecutionProbe.key(id + "_b");
        stock.put(raw, 100L);
        for (var target : List.of(a, b)) {
            patterns.add(PatternDetailsHelper.decodePattern(PatternDetailsHelper.encodeProcessingPattern(
                    new GenericStack[]{new GenericStack(raw, 1)},
                    new GenericStack[]{new GenericStack(target, 64)}), level));
        }
    }

    public static void tick() {
        if (active == null) return;
        try {
            if (active.advance()) {
                var finished = active;
                active = null;
                finished.close();
                log("PASS scenario=" + finished.name + " automatic_ticks=" + finished.nativeTicks +
                        " pushes=" + finished.pushes + " planning_states=" + finished.seenPlans.size() + " recovered=true");
            }
        } catch (Throwable error) {
            log("FAIL " + error);
            error.printStackTrace();
            var failed = active;
            active = null;
            try { failed.requester.getRequestedJobs().forEach(link -> link.cancel()); failed.close(); }
            catch (Throwable cleanup) { cleanup.printStackTrace(); }
        }
    }

    public static void afterRequesterTick(RequesterBlockEntity host) {
        var test = active;
        if (test == null || test.requester != host) return;
        try {
            test.nativeTicks++;
            Object state = ((Object[]) field(host, "requestStatus"))[0];
            if (state.getClass().getSimpleName().equals("PlanState")) test.seenPlans.add(state);
            // Model a fast downstream machine immediately after the native requester tick.
            // This removes real ME stock; no requester field or state is modified.
            if (test.consumeImmediately && test.pending() > 0 && test.amount(test.a) > 0) {
                check(test.known() == 0, "Fast-consumer trial missed the unchanged-snapshot window");
                long removed = test.inventory().extract(test.a, Long.MAX_VALUE, Actionable.MODULATE, host.getActionSource());
                test.consumed += removed;
                log("CONSUMED scenario=pending amount=" + removed + " known=" + test.known() +
                        " pending=" + test.pending() + " actual=" + test.amount(test.a));
            }
        } catch (Throwable error) {
            log("FAIL observer " + error);
            throw new RuntimeException(error);
        }
    }

    public static void storageChanged(StorageManager storage, AEKey key) {
        var test = active;
        if (test != null && storage == test.requester.getStorageManager() && (key.equals(test.a) || key.equals(test.b))) test.updates++;
    }

    public static void watcherBound(StorageManager storage) {
        var test = active;
        if (test != null && storage == test.requester.getStorageManager()) test.bindings++;
    }

    boolean advance() throws Exception {
        ticks++;
        check(ticks < 1800, "Trial timed out: " + state());
        for (var it = flights.iterator(); it.hasNext();) {
            var flight = it.next();
            if (flight.due > ticks) continue;
            long accepted = inventory().insert(flight.key, flight.amount, Actionable.MODULATE, requester.getActionSource());
            check(accepted == flight.amount, "Physical machine output rejected");
            it.remove();
        }
        if (phase == 0) {
            if (requester.getMainNode().getNode() == null) return false;
            connection = GridHelper.createConnection(root, requester.getMainNode().getNode());
            install();
            phase = 1;
            since = ticks;
        } else if (phase == 1) {
            if (!requester.getMainNode().isActive() || ticks - since < 30) return false;
            check(requester.getMainNode().getGrid() == grid, "Requester did not join the powered fixture grid");
            requester.getRequests().setStack(0, new GenericStack(a, 64));
            requester.getRequests().get(0).updateBatch(64);
            requester.getRequests().get(0).updateState(true);
            phase = 2;
        } else if (phase == 2) {
            if (amount(a) != 64 || known() != 64 || pending() != 0 || !idle()) return false;
            check(pushes == 1, "Normal control must execute exactly one recipe");
            baselinePushes = pushes;
            log("CONTROL scenario=" + name + " requested=64 stored=64 pushes=" + pushes + " known=" + known() + " pending=" + pending());
            if (scenario == 0) {
                consumeImmediately = true;
                check(inventory().extract(a, 64, Actionable.MODULATE, requester.getActionSource()) == 64, "Drain first control batch");
                phase = 3;
            } else {
                check(inventory().insert(a, 36, Actionable.MODULATE, requester.getActionSource()) == 36, "Prepare full old stock");
                phase = 4;
            }
            since = ticks;
        } else if (phase == 3) {
            if (consumed != 64 || !idle()) return false;
            check(pushes == baselinePushes + 1, "Second real order did not complete");
            beginBlocked();
        } else if (phase == 4) {
            if (known() != 100 || !idle()) return false;
            if (scenario == 1) {
                connection.destroy();
                connection = null;
                since = ticks;
                phase = 5;
            } else {
                var replacement = new Requests();
                replacement.setStack(0, new GenericStack(b, 64));
                replacement.get(0).updateBatch(64);
                var tag = new CompoundTag();
                tag.m_128365_("requests", replacement.serialize());
                requester.importSettings(SettingsFrom.MEMORY_CARD, tag, null);
                check(b.equals(requester.getRequests().getKey(0)), "Native memory card import failed");
                beginBlocked();
            }
        } else if (phase == 5) {
            if (ticks - since < 35) return false;
            check(!requester.getMainNode().isActive(), "Disconnected requester remained powered");
            check(inventory().extract(a, 100, Actionable.MODULATE, requester.getActionSource()) == 100, "Drain while requester is offline");
            // A terminal/another observer may read the new baseline while this requester is away.
            grid.getStorageService().invalidateCache();
            check(grid.getStorageService().getCachedInventory().get(a) == 0, "Main grid did not publish zero stock");
            phase = 6;
            since = ticks;
        } else if (phase == 6) {
            if (ticks - since < 20) return false;
            check(known() == 100, "Disconnected requester unexpectedly refreshed its old cache");
            connection = GridHelper.createConnection(root, requester.getMainNode().getNode());
            phase = 7;
            since = ticks;
        } else if (phase == 7) {
            if (!requester.getMainNode().isActive() || ticks - since < 30) return false;
            check(requester.getMainNode().getGrid() == grid, "Reconnect changed fixture grid");
            beginBlocked();
        } else if (phase == 8) {
            check(requester.getMainNode().isActive(), "Observation requester offline");
            check(pushes == blockedPushes, "Requester resumed without the recovery control: " + state());
            if (scenario == 2 && !touchedImportedKey && ticks - since >= 100) {
                check(inventory().insert(b, 1, Actionable.MODULATE, requester.getActionSource()) == 1, "Change imported-key stock");
                touchedImportedKey = true;
            }
            if (ticks - since < 400) return false;
            check(nativeTicks - blockedNativeTicks >= 15, "Requester was not being scheduled");
            check(idle(), "Stall was not idle: " + state());
            check(amount(raw) >= 90, "Missing input invalidates fixture");
            check(grid.getCraftingService().getCpus().stream().anyMatch(cpu -> !cpu.isBusy()), "No idle CPU");
            if (scenario == 0) check(amount(a) == 0 && known() == 0 && pending() == 64, "Pending failure absent");
            else check(known() == 100 && amount(scenario == 2 ? b : a) < 64, "Stale baseline failure absent");
            log("REPRODUCED scenario=" + name + " window_ticks=400 new_pushes=0 native_ticks=" +
                    (nativeTicks - blockedNativeTicks) + " stock_updates=" + (updates - blockedUpdates) + " " + state());
            consumeImmediately = false;
            if (scenario == 2) {
                // The normal UI key-change path is the recovery control; retain its native behavior.
                requester.getRequests().setStack(0, new GenericStack(a, 64));
                requester.getRequests().setStack(0, new GenericStack(b, 64));
                requester.getRequests().get(0).updateBatch(64);
            } else check(inventory().insert(a, 1, Actionable.MODULATE, requester.getActionSource()) == 1, "Touch watched inventory");
            phase = 9;
            since = ticks;
        } else if (phase == 9) {
            AEKey target = scenario == 2 ? b : a;
            if (amount(target) != 65 || known() != 65 || pending() != 0 || !idle()) return false;
            check(pushes == blockedPushes + 1, "Recovery did not produce exactly one new order");
            log("RECOVERED scenario=" + name + " recovery_ticks=" + (ticks - since) + " " + state());
            phase = 10;
            since = ticks;
        } else if (phase == 10 && ticks - since >= 40) {
            check(pushes == blockedPushes + 1 && idle(), "Repeated order after recovery");
            return true;
        }
        return false;
    }

    void beginBlocked() throws Exception {
        since = ticks;
        blockedPushes = pushes;
        blockedNativeTicks = nativeTicks;
        blockedUpdates = updates;
        phase = 8;
        log("OBSERVE scenario=" + name + " " + state());
    }

    void install() throws Exception {
        store = mounts -> mounts.mount(new MEStorage() {
            public net.minecraft.network.chat.Component getDescription() { return raw.getDisplayName(); }
            public void getAvailableStacks(KeyCounter result) { stock.forEach(result::add); }
            public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
                if (!key.equals(raw) && !key.equals(a) && !key.equals(b)) return 0;
                if (mode == Actionable.MODULATE) stock.merge(key, amount, Math::addExact);
                return amount;
            }
            public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
                long got = Math.min(amount, stock.getOrDefault(key, 0L));
                if (mode == Actionable.MODULATE) stock.put(key, stock.getOrDefault(key, 0L) - got);
                return got;
            }
        }, 10000);
        grid.getStorageService().addGlobalStorageProvider(store);
        provider = (IGridNode) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{IGridNode.class}, (p,m,x) -> switch(m.getName()) {
            case "getService" -> x[0] == ICraftingProvider.class ? this : null;
            case "getGrid" -> grid;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == x[0];
            case "toString" -> "Requester stall recipe fixture";
            default -> null;
        });
        registry = (NetworkCraftingProviders) field(grid.getCraftingService(), "craftingProviders");
        registry.addProvider(provider);
        installed = true;
    }

    void close() {
        requester.getRequests().get(0).updateState(false);
        if (connection != null) connection.destroy();
        if (installed) {
            registry.removeProvider(provider);
            grid.getStorageService().removeGlobalStorageProvider(store);
        }
    }

    public List<IPatternDetails> getAvailablePatterns() { return patterns; }
    public boolean isBusy() { return false; }
    public boolean pushPattern(IPatternDetails pattern, KeyCounter[] inputs) {
        long runs = inputs[0].get(raw);
        check(runs == 1, "Unexpected requester recipe batch");
        pushes++;
        for (var output : pattern.getOutputs()) flights.add(new Flight(ticks + 2, output.what(), output.amount()));
        return true;
    }

    MEStorage inventory() { return grid.getStorageService().getInventory(); }
    long amount(AEKey key) { return stock.getOrDefault(key, 0L); }
    long known() { return requester.getStorageManager().get(0).getKnownAmount(); }
    long pending() throws Exception { return (long) field(requester.getStorageManager().get(0), "pendingAmount"); }
    boolean idle() throws Exception { return ((Object[]) field(requester, "requestStatus"))[0].getClass().getSimpleName().equals("IdleState"); }
    String state() throws Exception {
        return "phase=" + phase + " known=" + known() + " pending=" + pending() + " actual=" + amount(scenario == 2 && phase >= 8 ? b : a) +
                " status=" + ((Object[])field(requester, "requestStatus"))[0].getClass().getSimpleName() + " bindings=" + bindings;
    }
    static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }
    record Flight(int due, AEKey key, long amount) {}
}
