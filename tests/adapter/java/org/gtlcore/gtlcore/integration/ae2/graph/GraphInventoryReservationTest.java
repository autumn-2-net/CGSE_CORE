package org.gtlcore.gtlcore.integration.ae2.graph;

import org.gtlcore.gtlcore.integration.ae2.crafting.ManualCraftingInventoryLock;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.crafting.execution.CraftingSubmitResult;
import org.cgse.core.PlanningBudget;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Actual reservation/stock-reader code with synthetic storage extraction contracts. */
final class GraphInventoryReservationTest {
    private static final AEKey RAW = AEItemKey.of(Items.IRON_INGOT);
    private static final AEKey FLUID = AEFluidKey.of(Fluids.WATER);

    static void run() throws Exception {
        List<String> failed = new ArrayList<>();
        exercise("unadvertised alias", GraphInventoryReservationTest::unadvertisedAlias, failed);
        exercise("owners and submission context", GraphInventoryReservationTest::owners, failed);
        exercise("physical shortage and access filter", GraphInventoryReservationTest::physicalAvailability, failed);
        exercise("exception cleanup", GraphInventoryReservationTest::exceptionCleanup, failed);
        exercise("long boundary", GraphInventoryReservationTest::longBoundary, failed);
        exercise("virtual infinite supply", GraphInventoryReservationTest::virtualSupply, failed);
        if (!failed.isEmpty()) throw new AssertionError("GraphInventoryReservationTest failed: " + failed);
        System.out.println("GraphInventoryReservationTest: PASS (aliases, ownership, access, rollback, limits, infinite/finite stock)");
    }

    private static void exercise(String name, Checked test, List<String> failed) throws Exception {
        try {
            test.run();
            System.out.println("Reservation check PASS: " + name);
        } catch (AssertionError error) {
            failed.add(name + ": " + error.getMessage());
            System.out.println("Reservation check FAIL: " + name + ": " + error.getMessage());
        }
    }

    private static void unadvertisedAlias() {
        var canonical = token(1);
        var alias = token(9);
        var storage = new Stock();
        storage.physical.add(RAW, 10);
        storage.physical.add(alias, Long.MAX_VALUE);
        storage.advertised.add(RAW, 10);
        storage.advertised.add(canonical, Long.MAX_VALUE);
        check(storage.extract(alias, 36, Actionable.SIMULATE, null) == 36, "alias must really be extractable");
        try (var reservation = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 7, alias, 36), null)) {
            check(reservation != null, "extractable unadvertised alias must not discard the entire reservation");
            check(storage.maxRequested == 36, "acquisition probes only enough stock to cover the reservation");
            check(storage.extract(RAW, 10, Actionable.MODULATE, null) == 3, "other orders may only take spare real material");
            reservation.submit(() -> {
                check(storage.extract(RAW, 7, Actionable.MODULATE, null) == 7, "owner must take its reserved real material");
                check(storage.extract(alias, 36, Actionable.SIMULATE, null) == 36, "owner can still extract alias");
                return CraftingSubmitResult.successful(null);
            });
        }
        check(!ManualCraftingInventoryLock.hasReservations(storage), "close must release both keys");
        check(storage.modulations == 2, "reservation must never itself transfer stock");
        check(storage.advertisements == 0, "reservation must query requested keys, not enumerate the network");
    }

    private static void owners() {
        var storage = stock(10);
        ManualCraftingInventoryLock.AvailabilityContext[] deferred = {null};
        try (var first = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 4), null);
             var second = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 3), null)) {
            check(first != null && second != null, "two funded reservations");
            check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 3, "other reservations counted exactly once");
            first.submit(() -> {
                check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 7, "owner excludes only its own claim");
                deferred[0] = ManualCraftingInventoryLock.captureAvailabilityContext();
                second.submit(() -> {
                    check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 6, "nested owner retains foreign claim");
                    return CraftingSubmitResult.successful(null);
                });
                check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 7, "nested submit restores previous owner");
                return CraftingSubmitResult.successful(null);
            });
            check(deferred[0].call(() -> storage.extract(RAW, 10, Actionable.SIMULATE, null)) == 7,
                    "deferred query retains active owner");
            check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 3, "query context must not leak");
            first.close();
            first.close();
            check(deferred[0].call(() -> storage.extract(RAW, 10, Actionable.SIMULATE, null)) == 7,
                    "expired context cannot spend another reservation");
        }
        check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 10, "all stock unlocked after closing");
    }

    private static void physicalAvailability() {
        var storage = stock(10);
        storage.physical.add(FLUID, 5);
        storage.advertised.add(FLUID, 50);
        try (var reservation = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 7, FLUID, 6), null)) {
            check(reservation == null, "advertised stock cannot satisfy a real shortfall");
        }
        check(!ManualCraftingInventoryLock.hasReservations(storage), "failed multi-key request must be atomic");
        var blocked = stock(10);
        blocked.deny = true;
        try (var reservation = ManualCraftingInventoryLock.tryAcquire(blocked, amounts(RAW, 1), null)) {
            check(reservation == null, "extraction denial must not be bypassed by visible stock");
        }
        var hidden = stock(10);
        hidden.advertised.clear();
        try (var reservation = ManualCraftingInventoryLock.tryAcquire(hidden, amounts(RAW, 7), null)) {
            check(reservation != null, "empty display cache is not authoritative absence");
        }
    }

    private static void exceptionCleanup() {
        var storage = stock(10);
        storage.physical.add(FLUID, 5);
        storage.advertised.add(FLUID, 5);
        try (var held = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 4), null)) {
            check(held != null, "existing reservation setup");
            storage.fail = FLUID;
            boolean failed = false;
            try (var unexpected = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 2, FLUID, 1), null)) {
                // A throwing provider must not publish any partial claim.
            } catch (IllegalStateException expected) { failed = true; }
            storage.fail = null;
            check(failed, "physical provider failure must be observed");
            check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 6, "failed acquisition preserves foreign claim");
            try {
                held.submit(() -> { throw new IllegalStateException("submit failure"); });
            } catch (IllegalStateException expected) {}
            check(storage.extract(RAW, 10, Actionable.SIMULATE, null) == 6, "failed submit restores owner context");
        }
        check(!ManualCraftingInventoryLock.hasReservations(storage), "exception path releases on close");
    }

    private static void longBoundary() {
        var storage = stock(Long.MAX_VALUE);
        try (var first = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, Long.MAX_VALUE - 1), null);
             var second = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 1), null);
             var overflow = ManualCraftingInventoryLock.tryAcquire(storage, amounts(RAW, 1), null)) {
            check(first != null && second != null && overflow == null, "saturated finite total must not overflow");
            check(storage.extract(RAW, Long.MAX_VALUE, Actionable.SIMULATE, null) == 0, "all finite stock is reserved");
            second.close();
            check(storage.extract(RAW, Long.MAX_VALUE, Actionable.SIMULATE, null) == 1, "release restores exact spare amount");
        }
    }

    private static void virtualSupply() throws Exception {
        // Isolate the existing type-name boundary without booting GT machines.
        // The synthetic provider has the same non-decrementing extraction contract.
        String name = "org.gtlcore.gtlcore.common.machine.VirtualIngredientSupplyMachine";
        var bytes = new ClassWriter(0);
        bytes.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name.replace('.', '/'), null,
                Stock.class.getName().replace('.', '/'), null);
        var constructor = bytes.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, Stock.class.getName().replace('.', '/'), "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        bytes.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(GraphInventoryReservationTest.class.getClassLoader()); }
            Class<?> define() { byte[] code = bytes.toByteArray(); return defineClass(name, code, 0, code.length); }
        }
        var virtual = (Stock) new Loader().define().getConstructor().newInstance();
        virtual.physical.add(RAW, Long.MAX_VALUE);
        virtual.inexhaustible = true;
        check(virtual.extract(RAW, Long.MAX_VALUE, Actionable.MODULATE, null) == Long.MAX_VALUE &&
                virtual.extract(RAW, Long.MAX_VALUE, Actionable.MODULATE, null) == Long.MAX_VALUE,
                "virtual fixture must retain its non-decrementing supply contract");
        var budget = new PlanningBudget(0, 10_000, 1 << 20, () -> false, System::nanoTime);
        try (var read = new ExactInventoryRead(virtual, RAW, null, budget)) {
            while (!read.step()) {}
            check(read.unlimited(), "virtual supplier must be external unlimited supply");
            check(read.amount().equals(BigInteger.valueOf(Long.MAX_VALUE)), "current physical window retained");
        }
        check(budget.reservedBytes() == 0, "reader memory released");
        virtual.deny = true;
        try (var read = new ExactInventoryRead(virtual, RAW, null, budget)) {
            while (!read.step()) {}
            check(!read.unlimited() && read.amount().signum() == 0, "offline/denied virtual supply is not usable");
        }
        try (var read = new ExactInventoryRead(stock(Long.MAX_VALUE), RAW, null, budget)) {
            while (!read.step()) {}
            check(!read.unlimited(), "unknown saturated stock must never be invented as infinite");
        }
    }

    private static AEKey token(int count) {
        var stack = new ItemStack(Items.PAPER);
        var tag = new CompoundTag();
        tag.putInt("payloadCount", count);
        stack.setTag(tag);
        return AEItemKey.of(stack);
    }

    private static Stock stock(long count) {
        var storage = new Stock();
        storage.physical.add(RAW, count);
        storage.advertised.add(RAW, count);
        return storage;
    }

    private static KeyCounter amounts(Object... entries) {
        var amounts = new KeyCounter();
        for (int i = 0; i < entries.length; i += 2) amounts.add((AEKey) entries[i], ((Number) entries[i + 1]).longValue());
        return amounts;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private interface Checked { void run() throws Exception; }

    public static class Stock implements MEStorage {
        final KeyCounter physical = new KeyCounter(), advertised = new KeyCounter();
        boolean deny, inexhaustible;
        AEKey fail;
        int modulations, advertisements;
        long maxRequested;
        public Stock() {}
        public Component getDescription() { return Component.literal("reservation test"); }
        public void getAvailableStacks(KeyCounter out) { advertisements++; out.addAll(advertised); }
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            maxRequested = Math.max(maxRequested, amount);
            if (key.equals(fail)) throw new IllegalStateException("provider unavailable");
            if (deny) return 0;
            // Same boundary as NetworkStorageMixin, including recursive simulation.
            long allowed = ManualCraftingInventoryLock.limitExtraction(this, key, amount, source);
            long extracted = Math.min(allowed, physical.get(key));
            if (mode == Actionable.MODULATE) {
                modulations++;
                if (!inexhaustible) physical.add(key, -extracted);
            }
            return extracted;
        }
    }
}
