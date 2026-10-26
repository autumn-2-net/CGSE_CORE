package org.gtlcore.gtlcore.integration.ae2.graph;

import appeng.api.stacks.AEKey;
import org.cgse.core.GraphPlan;
import org.cgse.core.PlanStep;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runs the shipped mixin handlers, replacing only Minecraft menu plumbing and provider readiness. */
public final class GraphConfirmationResumeTest implements Opcodes {
    private static final String MIXIN = "org/gtlcore/gtlcore/mixin/ae2/gui/CraftConfirmMenuMixin";
    private static final String NATIVE = "appeng/menu/me/crafting/CraftConfirmMenu";
    private static final String FIXTURE = "org/gtlcore/gtlcore/integration/ae2/graph/ConfirmationResumeFixture";
    private static final String HARNESS = GraphConfirmationResumeTest.class.getName().replace('.', '/');
    private static final Map<Object, State> STATES = new IdentityHashMap<>();
    private static Class<?> fixture;
    private static Object pendingCheck;

    private static final class State {
        int remaining, submitted, checks;
        boolean missingTool;
        State(int remaining) { this.remaining = remaining; }
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        run(appeng.api.stacks.AEItemKey.of(net.minecraft.world.item.Items.STONE));
    }

    static void run(AEKey target) throws Exception {
        fixture = fixtureClass();
        var unsafeField = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        pendingCheck = ((sun.misc.Unsafe) unsafeField.get(null)).allocateInstance(GraphProviderRequirements.class);
        try {
            var feasible = plan(target, false);
            var missing = plan(target, true);

            // The future is published after broadcast HEAD, so this first RETURN
            // has not yet seen any provider check for the replacement plan.
            Object large = menu(feasible, 130, true);
            resume(large);
            check(flag(large, "gtlcore$submitting"), "automatic confirmed intent must survive pending provider scan");
            check(state(large).submitted == 0 && state(large).remaining > 0, "pending scan submitted an unchecked plan");
            call(large, "startJob"); // Duplicate click must not clear the saved confirmation.
            check(flag(large, "gtlcore$submitting") && state(large).submitted == 0, "duplicate click lost or bypassed pending confirmation");
            for (int tick = 0; tick < 8; tick++) { providers(large); resume(large); }
            check(state(large).submitted == 1 && !flag(large, "gtlcore$submitting"), "completed scan must automatically submit exactly once");

            Object small = menu(feasible, 1, true);
            resume(small);
            check(state(small).submitted == 1, "one-slice provider check did not resume immediately");

            Object manual = menu(feasible, 130, false);
            providers(manual); // A periodic broadcast has started a multi-slice check.
            call(manual, "startJob");
            check(flag(manual, "gtlcore$submitting") && state(manual).submitted == 0,
                    "manual Start during a pending provider check must queue the confirmed intent");
            set(manual, "gtlcore$submitRefreshes", 1);
            call(manual, "startJob");
            check(flag(manual, "gtlcore$submitting") && (int) get(manual, "gtlcore$submitRefreshes") == 1,
                    "duplicate Start must not reset an active confirmation's refresh limit");
            for (int tick = 0; tick < 8; tick++) { providers(manual); resume(manual); }
            check(state(manual).submitted == 1 && !flag(manual, "gtlcore$submitting"),
                    "manually confirmed pending scan must submit exactly once when ready");

            Object explicitMissing = menu(missing, 130, false);
            providers(explicitMissing);
            call(explicitMissing, "startJob");
            check(flag(explicitMissing, "gtlcore$submitting") && state(explicitMissing).submitted == 0,
                    "explicit missing-craft confirmation must survive its pending provider check");
            for (int tick = 0; tick < 8; tick++) { providers(explicitMissing); resume(explicitMissing); }
            check(state(explicitMissing).submitted == 1 && !flag(explicitMissing, "gtlcore$submitting"),
                    "explicitly confirmed same missing plan must submit exactly once when ready");
            check(get(explicitMissing, "gtlcore$providerSubmitPlan") == null,
                    "consumed missing-craft confirmation retained its identity anchor");

            Object changedMissing = menu(missing, 130, false);
            providers(changedMissing);
            call(changedMissing, "startJob");
            set(changedMissing, "result", plan(target, true));
            resume(changedMissing);
            check(state(changedMissing).submitted == 0 && !flag(changedMissing, "gtlcore$submitting"),
                    "replacement missing plan must not inherit another plan's explicit confirmation");
            check(get(changedMissing, "gtlcore$providerSubmitPlan") == null,
                    "stopped replacement plan retained a stale confirmation anchor");

            Object blocked = menu(feasible, 130, true);
            state(blocked).missingTool = true;
            for (int tick = 0; tick < 8; tick++) { providers(blocked); resume(blocked); }
            check(state(blocked).submitted == 0 && !flag(blocked, "gtlcore$submitting"), "missing tool must stop automatic submission");
            state(blocked).missingTool = false;
            set(blocked, "gtlcore$providerWarning", "");
            resume(blocked);
            check(state(blocked).submitted == 0, "clearing a warning must not silently reconfirm a stopped order");

            Object incomplete = menu(missing, 130, true);
            resume(incomplete);
            check(state(incomplete).submitted == 0 && !flag(incomplete, "gtlcore$submitting"), "refreshed missing plan must require confirmation");
            Object failed = menu(null, 130, true);
            resume(failed);
            check(state(failed).submitted == 0 && !flag(failed, "gtlcore$submitting"), "failed refresh retained submission intent");

            Object browsing = menu(feasible, 130, false);
            for (int tick = 0; tick < 4; tick++) { providers(browsing); resume(browsing); }
            check(state(browsing).submitted == 0, "browsing a ready plan must not submit it");
            Object closed = menu(feasible, 130, true);
            set(closed, "gtlcore$closed", true);
            for (int tick = 0; tick < 4; tick++) { providers(closed); resume(closed); }
            check(state(closed).submitted == 0, "closed confirmation submitted later");

            System.out.println("GraphConfirmationResumeTest: PASS (actual mixin handlers; refresh/manual/pending/ready/duplicate/explicit-missing/replaced-missing/blocked/missing/failed/browsing/closed)");
        } finally { STATES.clear(); }
    }

    private static AeGraphPlan plan(AEKey target, boolean missing) {
        var graph = new GraphPlan<>(target, 1, false, new PlanStep.Sequence(List.of()), Map.of(),
                Map.of(target, 1L), Map.of(), missing ? Map.of(target, 1L) : Map.of(),
                missing ? GraphPlan.Result.MISSING_INPUT : GraphPlan.Result.FEASIBLE, 0, 0);
        return new AeGraphPlan(graph, Map.of(), Set.of(), missing ? Map.of() : Map.of(target, 1L));
    }

    private static Object menu(AeGraphPlan plan, int patterns, boolean confirmed) throws Exception {
        Object menu = fixture.getConstructor().newInstance();
        STATES.put(menu, new State(patterns));
        set(menu, "result", plan);
        set(menu, "gtlcore$providerWarning", "");
        set(menu, "gtlcore$submitting", confirmed);
        return menu;
    }

    /** Only provider readiness is mocked; the production handlers decide whether the click survives. */
    public static void providers(Object menu) {
        try {
            State state = state(menu);
            Object result = get(menu, "result");
            if (result == null) return;
            state.checks++;
            set(menu, "gtlcore$checkedProvidersPlan", result);
            state.remaining = Math.max(0, state.remaining - 64);
            set(menu, "gtlcore$providerCheck", state.remaining > 0 ? pendingCheck : null);
            set(menu, "gtlcore$providerWarning", state.remaining > 0 ? "checking" : state.missingTool ? "missing tool" : "");
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    /** Equivalent boundary of AE's startJob: run its real injected guard before recording submission. */
    public static void start(Object menu) {
        try {
            var callback = new CallbackInfo("startJob", true);
            call(menu, "gtlcore$guardPendingSubmission", callback);
            if (!callback.isCancelled()) state(menu).submitted++;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void resume(Object menu) throws Exception {
        call(menu, "gtlcore$resumeConfirmedSubmission", new CallbackInfo("broadcastChanges", false));
    }
    private static State state(Object menu) { return STATES.get(menu); }
    private static Object get(Object menu, String name) throws Exception { return fixture.getField(name).get(menu); }
    private static void set(Object menu, String name, Object value) throws Exception { fixture.getField(name).set(menu, value); }
    private static boolean flag(Object menu, String name) throws Exception { return (boolean) get(menu, name); }
    private static void call(Object menu, String name, Object... args) throws Exception {
        Method method = fixture.getDeclaredMethod(name, args.length == 0 ? new Class<?>[0] : new Class<?>[] { CallbackInfo.class });
        method.setAccessible(true);
        try { method.invoke(menu, args); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Error error) throw error;
            if (failure.getCause() instanceof Exception error) throw error;
            throw failure;
        }
    }

    private static Class<?> fixtureClass() throws Exception {
        byte[] bytes;
        String override = System.getProperty("cgse.confirmationMixinClass");
        if (override != null) bytes = Files.readAllBytes(Path.of(override));
        else try (InputStream input = GraphConfirmationResumeTest.class.getClassLoader().getResourceAsStream(MIXIN + ".class")) {
            if (input == null) throw new AssertionError("Missing actual host mixin bytecode");
            bytes = input.readAllBytes();
        }
        var writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        writer.visit(V17, ACC_PUBLIC | ACC_FINAL, FIXTURE, null, "java/lang/Object", null);
        Set<String> handlers = Set.of("gtlcore$guardPendingSubmission", "gtlcore$resumeConfirmedSubmission");
        var found = new java.util.HashSet<String>();
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                return writer.visitField((access & ACC_STATIC) | ACC_PUBLIC, name, descriptor, signature, value);
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if (!handlers.contains(name)) return null;
                found.add(name);
                var method = writer.visitMethod(ACC_PUBLIC, name, descriptor, signature, exceptions);
                return new MethodVisitor(ASM9, method) {
                    private String owner(String type) { return type.equals(MIXIN) || type.equals(NATIVE) ? FIXTURE : type; }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                        super.visitFieldInsn(opcode, owner(owner), name, descriptor);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        super.visitMethodInsn(opcode, owner(owner), name, descriptor, itf);
                    }
                    @Override public void visitTypeInsn(int opcode, String type) { super.visitTypeInsn(opcode, owner(type)); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        check(found.equals(handlers), "Expected actual production mixin handlers not found");
        MethodVisitor ctor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode(); ctor.visitVarInsn(ALOAD, 0); ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        MethodVisitor client = writer.visitMethod(ACC_PUBLIC, "isClientSide", "()Z", null, null);
        client.visitCode(); client.visitInsn(ICONST_0); client.visitInsn(IRETURN); client.visitMaxs(0, 0); client.visitEnd();
        bridge(writer, "gtlcore$checkProviders", "providers");
        bridge(writer, "startJob", "start");
        writer.visitEnd();
        class Loader extends ClassLoader {
            Loader() { super(GraphConfirmationResumeTest.class.getClassLoader()); }
            Class<?> define(byte[] code) { return defineClass(FIXTURE.replace('/', '.'), code, 0, code.length); }
        }
        return new Loader().define(writer.toByteArray());
    }

    private static void bridge(ClassWriter writer, String method, String callback) {
        MethodVisitor code = writer.visitMethod(ACC_PUBLIC, method, "()V", null, null);
        code.visitCode(); code.visitVarInsn(ALOAD, 0);
        code.visitMethodInsn(INVOKESTATIC, HARNESS, callback, "(Ljava/lang/Object;)V", false);
        code.visitInsn(RETURN); code.visitMaxs(0, 0); code.visitEnd();
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
