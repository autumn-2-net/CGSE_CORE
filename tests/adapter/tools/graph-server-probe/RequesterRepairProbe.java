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
public final class RequesterRepairProbe implements ICraftingProvider {
    static RequesterRepairProbe active;
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
    int expectedPushes, flightDelay = 2;
    boolean blockExports;
    boolean consumeImmediately, touchedImportedKey, installed;

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void log(String value) {
        System.out.println("[Requester Repair] " + value);
    }

    public static void start(Level level, RequesterBlockEntity requester, IGridNode root, int scenario) {
        check(active == null, "Another requester trial is active");
        check(scenario >= 0 && scenario < 5, "scenario");
        active = new RequesterRepairProbe(level, requester, root, scenario);
        log("START scenario=" + active.name + " graph=" + CraftingEngineRouter.useGraph());
    }

    RequesterRepairProbe(Level level, RequesterBlockEntity requester, IGridNode root, int scenario) {
        this.level = level;
        this.requester = requester;
        this.root = root;
        this.grid = root.getGrid();
        this.scenario = scenario;
        name = List.of("pending", "reconnect", "memory_card", "inflight_import", "saved_pending").get(scenario);
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
                        " pushes=" + finished.pushes + " planning_states=" + finished.seenPlans.size() + " fixed=true");
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
                if (test.consumed >= 192) test.consumeImmediately = false;
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
            check(requester.getMainNode().getGrid() == grid, "Wrong grid");
            accountingContracts();
            requester.getRequests().setStack(0, new GenericStack(a, 64));
            requester.getRequests().get(0).updateBatch(64);
            requester.getRequests().get(0).updateState(true);
            phase = 2;
        } else if (phase == 2) {
            if (amount(a) != 64 || known() != 64 || pending() != 0 || !idle()) return false;
            check(pushes == 1, "Control overcrafted");
            baselinePushes = pushes;
            log("CONTROL scenario=" + name + " requested=64 actual=64 known=64 pending=0 pushes=1");
            if (scenario == 0 || scenario == 3) {
                consumeImmediately = scenario == 0;
                if (scenario == 3) { flightDelay = 60; blockExports = true; }
                check(inventory().extract(a, 64, Actionable.MODULATE, requester.getActionSource()) == 64, "Drain control");
                phase = scenario == 0 ? 3 : 11;
            } else {
                check(inventory().insert(a, 36, Actionable.MODULATE, requester.getActionSource()) == 36, "Old stock 100");
                phase = 4;
            }
            since = ticks;
        } else if (phase == 3) {
            if (consumed < 192) return false;
            check(consumed == 192 && pushes == 4, "Three native cycles must have been consumed");
            expectedPushes = 5;
            beginFixed();
        } else if (phase == 4) {
            if (known() != 100 || !idle()) return false;
            if (scenario == 1) {
                connection.destroy(); connection = null;
                since = ticks; phase = 5;
            } else if (scenario == 2) {
                importB(); expectedPushes = 2; beginFixed();
            } else {
                check(inventory().extract(a, 100, Actionable.MODULATE, requester.getActionSource()) == 100, "Drain saved stock");
                var saved = requester.getStorageManager().serialize();
                var slot = saved.m_128469_("0");
                slot.m_128356_("known_amount", 100);
                slot.m_128356_("pending_amount", 64);
                requester.getStorageManager().deserialize(saved);
                expectedPushes = 2; beginFixed();
                log("LOADED old-save accounting known=100 pending=64 actual=0");
            }
        } else if (phase == 5) {
            if (ticks - since < 35) return false;
            check(!requester.getMainNode().isActive(), "Not disconnected");
            check(inventory().extract(a, 100, Actionable.MODULATE, requester.getActionSource()) == 100, "Drain offline");
            grid.getStorageService().invalidateCache();
            check(grid.getStorageService().getCachedInventory().get(a) == 0, "Publish zero");
            phase = 6; since = ticks;
        } else if (phase == 6) {
            if (ticks - since < 20) return false;
            check(known() == 100, "Disconnected baseline unexpectedly changed");
            connection = GridHelper.createConnection(root, requester.getMainNode().getNode());
            expectedPushes = 2; beginFixed();
        } else if (phase == 8) {
            check(ticks - since < 240, "No autonomous replenishment: " + state());
            if (!requester.getMainNode().isActive()) return false;
            check(pushes <= expectedPushes, "Overcraft after trigger");
            if (amount(target()) != 64 || known() != 64 || pending() != 0 || !idle()) return false;
            check(pushes == expectedPushes, "Replenishment did not execute exactly the needed recipes");
            if (scenario == 3) check(amount(a) == 64, "Old in-flight output lost or duplicated");
            log("FIXED scenario=" + name + " response_ticks=" + (ticks - since) + " actual=64 known=64 pending=0 pushes=" + pushes);
            if (scenario == 2) {
                // Prove the imported key is also tracked after its first replenishment.
                check(inventory().extract(b, 64, Actionable.MODULATE, requester.getActionSource()) == 64, "Drain imported B");
                expectedPushes++; phase = 10; since = ticks;
            } else { phase = 9; since = ticks; blockedNativeTicks = nativeTicks; }
        } else if (phase == 10) {
            check(ticks - since < 240, "New key not watched");
            check(pushes <= expectedPushes, "Imported-key overcraft");
            if (amount(b) != 64 || known() != 64 || pending() != 0 || !idle()) return false;
            check(pushes == expectedPushes, "Imported-key refill count");
            log("WATCHED scenario=memory_card drained_and_refilled=true");
            phase = 9; since = ticks; blockedNativeTicks = nativeTicks;
        } else if (phase == 9) {
            check(requester.getMainNode().isActive() && idle() && pushes == expectedPushes, "Stable full stock overcrafted");
            check(amount(target()) == 64 && known() == 64 && pending() == 0, "Stable stock drift");
            if (ticks - since < 400) return false;
            check(nativeTicks - blockedNativeTicks >= 15, "Not being scheduled");
            check(amount(raw) == 100 - pushes, "Raw material balance");
            check(grid.getCraftingService().getCpus().stream().anyMatch(cpu -> !cpu.isBusy()), "No free CPU");
            log("STABLE scenario=" + name + " ticks=400 native_ticks=" + (nativeTicks-blockedNativeTicks) + " extra_orders=0");
            return true;
        } else if (phase == 11) {
            if (pushes != 2 || requester.getRequestedJobs().isEmpty()) return false;
            check(!flights.isEmpty(), "In-flight job already delivered");
            var links = requester.getRequestedJobs();
            importB();
            check(requester.getRequestedJobs().equals(links), "Import replaced active link");
            phase = 12;
        } else if (phase == 12) {
            var storage = requester.getStorageManager().get(0);
            if (storage.getBufferAmount() != 64) return false;
            check(a.equals(storage.getKey()) && amount(a) == 0 && amount(b) == 0, "Old output buffer mismatch");
            phase = 13; since = ticks;
        } else if (phase == 13) {
            var storage = requester.getStorageManager().get(0);
            check(storage.getBufferAmount() == 64 && a.equals(storage.getKey()), "Blocked output lost");
            check(pushes == 2, "Crafted during blocked old output");
            if (ticks - since < 40) return false;
            blockExports = false; flightDelay = 2;
            expectedPushes = 3; beginFixed();
            log("PRESERVED scenario=inflight_import link=true buffer=64 blocked_ticks=40");
        }
        return false;
    }

    AEKey target() { return scenario == 2 || scenario == 3 ? b : a; }

    void beginFixed() { since = ticks; phase = 8; }

    void importB() {
        var replacement = new Requests();
        replacement.setStack(0, new GenericStack(b, 64));
        replacement.get(0).updateBatch(64);
        var tag = new CompoundTag();
        tag.m_128365_("requests", replacement.serialize());
        requester.importSettings(SettingsFrom.MEMORY_CARD, tag, null);
        check(b.equals(requester.getRequests().getKey(0)), "Import did not change key");
    }

    void accountingContracts() throws Exception {
        // Exercise the transformed dependency class separately; do not mutate the live requester's state.
        var storage = new StorageManager.Storage();
        var tag = new CompoundTag();
        tag.m_128365_("key", a.toTagGeneric());
        tag.m_128356_("buffer_amount", 128);
        tag.m_128356_("known_amount", 100);
        tag.m_128356_("pending_amount", 64);
        storage.deserialize(tag);
        check(storage.getBufferAmount() == 128 && (long)field(storage,"pendingAmount") == 0, "NBT must retain buffer, drop stale pending");
        var accounting = (org.gtlcore.gtlcore.integration.ae2.requester.RequesterStorageAccounting) storage;
        var service = grid.getStorageService();
        accounting.gtlcore$updateSnapshot(service, 10, a, 0);
        accounting.gtlcore$prepareExport(service, 10, a);
        storage.compute(64); accounting.gtlcore$finishExport(a);
        for (int i = 0; i < 100; i++) {
            accounting.gtlcore$updateSnapshot(service, 10, a, 0);
            check(accounting.gtlcore$hasStock(64), "Same sample lost pending / can double-order");
        }
        accounting.gtlcore$prepareExport(service, 10, a);
        storage.compute(64); accounting.gtlcore$finishExport(a);
        check((long)field(storage,"pendingAmount") == 128, "Same-generation exports did not accumulate");
        accounting.gtlcore$updateSnapshot(service, 11, a, 0);
        check(!accounting.gtlcore$hasStock(1), "Unchanged new sample did not clear pending");
        accounting.gtlcore$updateSnapshot(service, 11, a, Long.MAX_VALUE - 1);
        accounting.gtlcore$prepareExport(service, 11, a);
        storage.compute(2); accounting.gtlcore$finishExport(a);
        check(accounting.gtlcore$hasStock(Long.MAX_VALUE), "Known + pending overflow");
        accounting.gtlcore$prepareExport(service, 11, a);
        storage.compute(Long.MAX_VALUE); accounting.gtlcore$finishExport(a);
        check((long)field(storage,"pendingAmount") == Long.MAX_VALUE, "Pending overflow not saturated");
        accounting.gtlcore$updateSnapshot(service, 11, b, 0);
        check(!accounting.gtlcore$hasStock(1), "A pending counted as B");
        log("CONTRACTS same_sample=100 newer_unchanged=true nbt_buffer=true long_overflow=true changed_key=true");
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
                if (blockExports && key.equals(a)) return 0;
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
        for (var output : pattern.getOutputs()) flights.add(new Flight(ticks + flightDelay, output.what(), output.amount()));
        return true;
    }

    MEStorage inventory() { return grid.getStorageService().getInventory(); }
    long amount(AEKey key) { return stock.getOrDefault(key, 0L); }
    long known() { return requester.getStorageManager().get(0).getKnownAmount(); }
    long pending() throws Exception { return (long) field(requester.getStorageManager().get(0), "pendingAmount"); }
    boolean idle() throws Exception { return ((Object[]) field(requester, "requestStatus"))[0].getClass().getSimpleName().equals("IdleState"); }
    String state() throws Exception {
        return "phase=" + phase + " known=" + known() + " pending=" + pending() + " actual=" + amount(target()) +
                " status=" + ((Object[])field(requester, "requestStatus"))[0].getClass().getSimpleName() + " bindings=" + bindings;
    }
    static Object field(Object object, String name) throws Exception {
        var f = object.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(object);
    }
    record Flight(int due, AEKey key, long amount) {}
}
