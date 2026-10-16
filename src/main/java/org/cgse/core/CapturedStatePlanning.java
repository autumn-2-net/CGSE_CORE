// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Positive-only capacity proposal followed by exact physical-state restoration.
 * Capacity tokens exist exclusively in this auxiliary coordinate system. Every
 * activation spends a physical entity, and restoration keeps entities separate
 * until all slots of an operation have been allocated. No capacity relaxation
 * failure is a proof about the captured state family.
 */
final class CapturedStatePlanning<K> implements AutoCloseable {

    private record Key<K>(K physical, String family, boolean entity) {
        static <K> Key<K> physical(K key) { return new Key<>(key, null, false); }
        static <K> Key<K> capacity(String family) { return new Key<>(null, family, false); }
        static <K> Key<K> entities(String family) { return new Key<>(null, family, true); }
    }
    private record Activation<K>(K state, StateFamily<K> family) {}
    private record Returned<K>(K before, K state, StateFamily<K> family, long amount) {}
    // The auxiliary variant pins the source binding, candidate choices and
    // family contracts. Equal net columns alone never identify a transition.
    private record Transition<K>(String variant, List<GraphRecipe.Slot<K>> slots, Map<K, Long> outputs) {}

    enum Status { RUNNING, VERIFIED, DECLINED, LIMITED }
    enum Reason {
        NONE, LOCAL_WORK_LIMIT, WORKSPACE_DECLINED, AUXILIARY_RESULT, ACTIVATION_OWNERSHIP,
        UNKNOWN_AUXILIARY_BINDING, UNEXPECTED_CAPACITY_OUTPUT, INSUFFICIENT_DISTINCT_ENTITIES,
        INVALID_STATE_ALGEBRA, ORDINARY_INPUT_OWNERSHIP, UNFUNDED_EXACT_RESTORATION,
        FORCE_CRAFT_NOT_PROVED, UNREPRESENTABLE_RESTORATION
    }
    record Outcome(Status status, Reason reason) {}

    private final List<CapturedCatalog.Entry<K>> entries;
    private final K target;
    private final long amount, started = System.nanoTime();
    private final Map<K, Long> stock, seeds;
    private final Set<K> external;
    private final boolean preserve, force;
    private final PlanningBudget budget;
    private final long workStarted, allowance;
    private final Map<String, StateFamily<K>> families = new LinkedHashMap<>();
    private final Set<String> entityFamilies = new LinkedHashSet<>();
    private final Set<K> known = new LinkedHashSet<>();
    private final List<CapturedCatalog.Entry<Key<K>>> auxiliary = new ArrayList<>();
    private final Map<String, CapturedCatalog.Entry<K>> sources = new LinkedHashMap<>();
    private final Map<String, Activation<K>> activations = new LinkedHashMap<>();
    private final Map<String, Map<K, BigInteger>> pools = new LinkedHashMap<>();
    private final Map<K, BigInteger> free = new LinkedHashMap<>();
    private final Map<String, GraphRecipe<K>> restored = new LinkedHashMap<>();
    private final Map<Transition<K>, String> transitions = new LinkedHashMap<>();
    private final List<PlanStep> program = new ArrayList<>();
    private List<K> activationStates = List.of();
    private CapturedCatalog<Key<K>>.Work planning;
    private GraphPlan<Key<K>> proposed;
    private PlanCursor cursor;
    private AllocationSearch.Candidate<K> assembly;
    private PlanVerification<K> verification;
    private ForceCraftProof<K> production;
    private PlanningBudget.CandidateObservation observation;
    private GraphPlan<K> result;
    private int entry, activation;
    private long memory;
    private long restoreWork, restoreCalls;
    private long programNodes = 1; // Includes the root sequence before optimization.
    private BigInteger restoredFirings = BigInteger.ZERO;
    private Outcome outcome = new Outcome(Status.RUNNING, Reason.NONE);
    private boolean prepared, done;

    CapturedStatePlanning(List<CapturedCatalog.Entry<K>> entries, K target, long amount, Map<K, Long> stock,
                          Set<K> external, Map<K, Long> seeds, boolean preserve, boolean force, PlanningBudget budget) {
        this.entries = entries; this.target = target; this.amount = amount; this.stock = stock;
        this.external = external; this.seeds = seeds; this.preserve = preserve; this.force = force; this.budget = budget;
        workStarted = budget.nodes(); allowance = budget.remainingWork() / 2;
        if (allowance < 1024) { stop(Reason.LOCAL_WORK_LIMIT); return; }
        if (!reserve(2048)) return;
        try {
            for (var value : stock.entrySet()) { budget.compilationCheck(); known.add(value.getKey()); free.put(value.getKey(), BigInteger.valueOf(value.getValue())); }
            known.addAll(external);
            for (var source : entries) {
                budget.compilationCheck();
                for (var output : source.recipe().outputs()) known.add(output.what());
                for (var input : source.recipe().inputs()) for (var candidate : input.candidates()) {
                    budget.compilationCheck(); known.add(candidate.stack().what());
                    if (candidate.remaining() != null) known.add(candidate.remaining());
                    var family = candidate.family();
                    if (family != null) {
                        if (family.id() == null || family.id().isEmpty()) throw new IllegalArgumentException("Empty state family identity");
                        families.putIfAbsent(family.id(), family);
                    }
                }
            }
            if (!reserve(160L * (known.size() + families.size()))) return;
            activationStates = List.copyOf(known);
            if (budget.proofJournal() != null) budget.proofJournal().markIncomplete();
            budget.note("candidate_state", "start; families=" + families.size() + "; known=" + known.size() +
                    "; allowance=" + allowance + "; negative_proofs=false");
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    boolean step() {
        if (done) return true;
        var observed = observation;
        long observedWork = observed == null ? 0 : budget.threadWork(), observedNanos = observed == null ? 0 : System.nanoTime();
        try (var costs = budget.trace(PlanningCostTrace.Origin.ALLOCATION,
                !prepared ? PlanningCostTrace.Stage.PREPARE : verification != null ? PlanningCostTrace.Stage.VERIFY :
                        assembly != null ? PlanningCostTrace.Stage.ASSEMBLE : proposed != null ? PlanningCostTrace.Stage.RESTORE : PlanningCostTrace.Stage.SEARCH)) {
            budget.checkpoint();
            budget.phase(!prepared ? PlanningBudget.Phase.BUILD : assembly == null ? PlanningBudget.Phase.SOLVE : PlanningBudget.Phase.VERIFY);
            if (budget.nodes() - workStarted >= allowance) return stop(Reason.LOCAL_WORK_LIMIT);
            if (!prepared) prepare();
            else if (verification != null) verify();
            else if (assembly != null) {
                if (assembly.step()) {
                    if (assembly.plan == null) return stop(Reason.UNFUNDED_EXACT_RESTORATION);
                    verification = new PlanVerification<>(assembly.plan, budget);
                }
            } else if (proposed != null) restore();
            else if (planning.step()) {
                proposed = planning.result().plan();
                planning.close(); planning = null;
                if (!proposed.feasible()) return stop(Reason.AUXILIARY_RESULT);
                cursor = new PlanCursor(proposed.steps());
                observation = budget.observeCandidate(PlanningBudget.CandidateOrigin.ALLOCATION);
            }
            return done;
        } catch (PlanningBudget.Exhausted limit) {
            if (limit.limit() != PlanningBudget.Limit.MEMORY_LIMIT) throw limit;
            return stop(Reason.WORKSPACE_DECLINED);
        } catch (ArithmeticException overflow) {
            return stop(Reason.UNREPRESENTABLE_RESTORATION);
        } finally { if (observed != null) observed.step(observedWork, observedNanos); }
    }

    private void prepare() {
        budget.compilationCheck();
        if (entry < entries.size()) {
            var source = entries.get(entry++);
            var inputs = new ArrayList<CapturedRecipe.Input<Key<K>>>();
            var entities = new LinkedHashMap<String, Long>();
            for (var input : source.recipe().inputs()) {
                var candidates = new ArrayList<CapturedRecipe.Candidate<Key<K>>>();
                var seen = new LinkedHashSet<String>();
                for (var candidate : input.candidates()) {
                    budget.compilationCheck();
                    if (candidate.family() == null) {
                        if (!reserve(128)) return;
                        candidates.add(new CapturedRecipe.Candidate<>(new CapturedRecipe.Amount<>(Key.physical(candidate.stack().what()), candidate.stack().amount()),
                                candidate.remaining() == null ? null : Key.physical(candidate.remaining()), candidate.configuration(), candidate.reusable()));
                    } else {
                        // One state transition describes one entity, not an
                        // unspecified stack or dispatch-level configuration.
                        if (candidate.stack().amount() != 1 || candidate.configuration() || candidate.reusable()) continue;
                        var family = candidate.family();
                        if (seen.add(family.id())) {
                            if (!reserve(128)) return;
                            candidates.add(new CapturedRecipe.Candidate<>(
                                    new CapturedRecipe.Amount<>(Key.capacity(family.id()), 1), null, false));
                        }
                    }
                }
                if (candidates.isEmpty()) return; // This entry has no supported auxiliary interpretation.
                inputs.add(new CapturedRecipe.Input<>(input.multiplier(), candidates, input.mode()));
                // A fixed family choice needs all these entities simultaneously.
                // Ambiguous family/ordinary choices keep the capacity-only view;
                // independent marginal choices must not invent a slot relation.
                String family = candidates.get(0).stack().what().family();
                if (family != null && candidates.stream().allMatch(c -> family.equals(c.stack().what().family())))
                    entities.merge(family, input.multiplier(), Math::addExact);
            }
            for (var need : entities.entrySet()) {
                // Positive capacity already requires at least one activation.
                // Do not enlarge ordinary one-tool graphs with a redundant arc.
                if (need.getValue() <= 1) continue;
                budget.compilationCheck();
                if (!reserve(256)) return;
                entityFamilies.add(need.getKey());
                inputs.add(new CapturedRecipe.Input<>(1, List.of(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>(Key.entities(need.getKey()), need.getValue()), null, true, true)),
                        CapturedRecipe.InputMode.INDIVISIBLE));
            }
            var outputs = new ArrayList<CapturedRecipe.Amount<Key<K>>>();
            for (var output : source.recipe().outputs()) outputs.add(new CapturedRecipe.Amount<>(Key.physical(output.what()), output.amount()));
            String id = "state_recipe_" + (entry - 1);
            sources.put(id, source);
            if (!reserve(256L + 160L * (inputs.size() + outputs.size()))) return;
            auxiliary.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(inputs, outputs, source.recipe().external(), true)));
            return;
        }
        if (activation < activationStates.size()) {
            // Only initial / declared / ordinary produced states are activated.
            // Never close the remaining-state chain or enumerate capacity values.
            K state = activationStates.get(activation++);
            for (var family : families.values()) {
                budget.compilationCheck();
                if (!family.accepts(state)) continue;
                long capacity = family.capacity(state);
                if (capacity <= 0) continue;
                String id = "state_activate_" + activations.size();
                var input = new CapturedRecipe.Input<Key<K>>(1, List.of(new CapturedRecipe.Candidate<>(
                        new CapturedRecipe.Amount<>(Key.physical(state), 1), null, false)), CapturedRecipe.InputMode.INDIVISIBLE);
                var output = new CapturedRecipe.Amount<Key<K>>(Key.capacity(family.id()), capacity);
                boolean entities = entityFamilies.contains(family.id());
                if (!reserve(entities ? 640 : 512)) return;
                activations.put(id, new Activation<>(state, family));
                var outputs = entities ? List.of(output, new CapturedRecipe.Amount<Key<K>>(Key.entities(family.id()), 1)) : List.of(output);
                auxiliary.add(new CapturedCatalog.Entry<>(id, id, new CapturedRecipe<>(List.of(input), outputs, false, false)));
            }
            return;
        }
        var available = new LinkedHashMap<Key<K>, Long>();
        stock.forEach((key, count) -> available.put(Key.physical(key), count));
        var required = new LinkedHashMap<Key<K>, Long>();
        for (var seed : seeds.entrySet()) {
            // Activation removes a physical key only in the proposal. Its exact
            // final state is unknown here; retaining that same key as an auxiliary
            // seed would reject even an unchanged returned tool. The original
            // seed requirement remains mandatory in assembly and verification.
            boolean state = false;
            for (var family : families.values()) {
                budget.compilationCheck();
                if (family.accepts(seed.getKey())) { state = true; break; }
            }
            if (!state) required.put(Key.physical(seed.getKey()), seed.getValue());
        }
        var emitted = new LinkedHashSet<Key<K>>();
        external.forEach(key -> emitted.add(Key.physical(key)));
        planning = new CapturedCatalog<>(auxiliary, false).begin(Key.physical(target), amount, available, emitted,
                required, preserve, force, budget);
        prepared = true;
    }

    private void restore() {
        long before = budget.threadWork();
        budget.check();
        var batch = cursor.current();
        if (batch == null) {
            assembly = new AllocationSearch.Candidate<>(new PlanStep.Sequence(program), restored, target, amount, stock,
                    seeds, external, preserve, force, false, budget, started);
            return;
        }
        try { restore(batch); }
        finally { restoreWork += budget.threadWork() - before; restoreCalls++; }
    }

    private void restore(PlanStep.Batch batch) {
        var recipe = proposed.recipes().get(batch.recipe());
        var activating = activations.get(recipe.binding());
        if (activating != null) {
            BigInteger count = BigInteger.valueOf(batch.runs());
            if (!spend(activating.state(), count)) { stop(Reason.ACTIVATION_OWNERSHIP); return; }
            pool(activating.family()).merge(activating.state(), count, BigInteger::add);
            cursor.dispatched(batch.runs()); return;
        }
        var source = sources.get(recipe.binding());
        if (source == null) { stop(Reason.UNKNOWN_AUXILIARY_BINDING); return; }
        boolean stateful = recipe.slots().stream().anyMatch(slot -> slot.key().family() != null);
        long runs = stateful ? 1 : batch.runs();
        var slots = new ArrayList<GraphRecipe.Slot<K>>();
        var outputs = new LinkedHashMap<K, Long>();
        var returns = new ArrayList<Returned<K>>();
        for (var output : recipe.outputs().entrySet()) {
            if (output.getKey().entity()) continue; // Auxiliary read-arc balance, never a real return.
            if (output.getKey().family() != null) { stop(Reason.UNEXPECTED_CAPACITY_OUTPUT); return; }
            outputs.merge(output.getKey().physical(), output.getValue(), Math::addExact);
        }
        for (var slot : recipe.slots()) {
            budget.check();
            if (slot.key().entity()) continue;
            if (slot.key().family() == null) {
                slots.add(new GraphRecipe.Slot<>(slot.key().physical(), slot.amount(), slot.inputSlot(), slot.configuration(), slot.reusable()));
            } else {
                var family = families.get(slot.key().family());
                var input = source.recipe().inputs().get(slot.inputSlot());
                if (!takeTools(family, slot, source.recipe().mixed(input), slots, returns)) {
                    if (!done) stop(Reason.INSUFFICIENT_DISTINCT_ENTITIES);
                    return;
                }
            }
        }
        // The physical inventory excludes activated entities. Ordinary inputs
        // cannot silently spend a tool already assigned to a capacity pool.
        var ordinaryNeeded = new LinkedHashMap<K, BigInteger>();
        var ordinarySpent = new LinkedHashMap<K, BigInteger>();
        var ordinaryProduced = new LinkedHashMap<K, BigInteger>();
        for (var slot : recipe.slots()) {
            if (slot.key().family() != null) continue;
            BigInteger quantity = BigInteger.valueOf(slot.amount()).multiply(BigInteger.valueOf(slot.configuration() ? 1 : runs));
            ordinaryNeeded.merge(slot.key().physical(), quantity, BigInteger::add);
            if (!slot.reusable()) ordinarySpent.merge(slot.key().physical(), quantity, BigInteger::add);
        }
        // A fixed point of the immutable exact transition is repeatable without
        // enumerating unused states. Keep serial firing boundaries: consumed
        // configurations are paid once per iteration, and tools return before
        // the next one. An arbitrary changing-state callback has no such shortcut.
        long repetitions = stateful && returns.stream().allMatch(r -> r.before().equals(r.state())) ? batch.runs() : 1;
        boolean repeating = repetitions > 1;
        if (repeating) for (var output : recipe.executionOutputs().entrySet()) {
            if (output.getKey().entity()) continue;
            ordinaryProduced.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()), BigInteger::add);
        }
        for (var needed : ordinaryNeeded.entrySet()) {
            if (repeating) budget.check();
            if (external.contains(needed.getKey())) continue;
            BigInteger available = free.getOrDefault(needed.getKey(), BigInteger.ZERO);
            if (available.compareTo(needed.getValue()) < 0) {
                stop(Reason.ORDINARY_INPUT_OWNERSHIP); return;
            }
            if (repeating) {
                BigInteger loss = ordinarySpent.getOrDefault(needed.getKey(), BigInteger.ZERO)
                        .subtract(ordinaryProduced.getOrDefault(needed.getKey(), BigInteger.ZERO));
                if (loss.signum() > 0) repetitions = Math.min(repetitions,
                        ExactAmounts.capped(available.subtract(needed.getValue()).divide(loss).add(BigInteger.ONE)));
            }
        }
        if (repeating) {
            BigInteger repeat = BigInteger.valueOf(repetitions);
            var ordinaryKeys = new LinkedHashSet<>(ordinarySpent.keySet());
            ordinaryKeys.addAll(ordinaryProduced.keySet());
            for (K key : ordinaryKeys) {
                budget.check();
                BigInteger delta = ordinaryProduced.getOrDefault(key, BigInteger.ZERO).subtract(ordinarySpent.getOrDefault(key, BigInteger.ZERO));
                free.put(key, free.getOrDefault(key, BigInteger.ZERO).add(delta.multiply(repeat)).max(BigInteger.ZERO));
            }
        } else ordinarySpent.forEach(this::spend);
        // Even a terminal fragment cannot fund an ordinary input of this same
        // operation. Release returns only after all physical inputs are owned.
        for (var value : returns) if (value.state() != null) {
            outputs.merge(value.state(), value.amount(), Math::addExact);
            if (value.family().accepts(value.state()) && value.family().capacity(value.state()) > 0)
                pool(value.family()).merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
            else free.merge(value.state(), BigInteger.valueOf(value.amount()), BigInteger::add);
        }
        // Keep the simple one-dispatch path for changing states and ordinary
        // batches; it needs neither repeat-bound arithmetic nor a delta overlay.
        if (!repeating) for (var output : recipe.executionOutputs().entrySet()) {
            if (!output.getKey().entity())
                free.merge(output.getKey().physical(), BigInteger.valueOf(output.getValue()).multiply(BigInteger.valueOf(runs)), BigInteger::add);
        }
        var transition = new Transition<>(recipe.id(), slots, outputs);
        String id = transitions.get(transition);
        if (id == null) {
            if (!reserve(384L + 112L * (slots.size() + outputs.size()))) return;
            id = "state_exact_" + restored.size();
            var concrete = new GraphRecipe<>(id, source.binding(), slots, outputs);
            restored.put(id, concrete);
            transitions.put(new Transition<>(recipe.id(), concrete.slots(), concrete.outputs()), id);
        }
        if (!append(id, runs, repetitions)) return;
        long dispatched = Math.multiplyExact(runs, repetitions);
        restoredFirings = restoredFirings.add(BigInteger.valueOf(dispatched));
        cursor.dispatched(dispatched);
    }

    private boolean append(String id, long runs, long repetitions) {
        PlanStep.Batch leaf = new PlanStep.Batch(id, runs);
        if (!program.isEmpty()) {
            int last = program.size() - 1;
            PlanStep previous = program.get(last);
            PlanStep body = previous instanceof PlanStep.Repeat r ? r.body() : previous;
            long count = previous instanceof PlanStep.Repeat r ? r.times() : 1;
            if (body.equals(leaf) && count <= Long.MAX_VALUE - repetitions) {
                if (!(previous instanceof PlanStep.Repeat)) programNodes++;
                program.set(last, new PlanStep.Repeat(body, count + repetitions));
                return true;
            }
        }
        if (!reserve(96)) return false;
        program.add(repetitions == 1 ? leaf : new PlanStep.Repeat(leaf, repetitions));
        programNodes += repetitions == 1 ? 1 : 2;
        return true;
    }

    private boolean takeTools(StateFamily<K> family, GraphRecipe.Slot<Key<K>> slot, boolean mixed,
                              List<GraphRecipe.Slot<K>> slots, List<Returned<K>> returns) {
        long left = slot.amount();
        var pool = pool(family);
        var iterator = pool.entrySet().iterator();
        while (iterator.hasNext()) {
            var value = iterator.next();
            budget.check();
            K state = value.getKey();
            long before = family.capacity(state);
            if (!family.accepts(state) || before <= 0) { stop(Reason.INVALID_STATE_ALGEBRA); return false; }
            if (!mixed && value.getValue().compareTo(BigInteger.valueOf(left)) < 0) continue;
            long use = value.getValue().min(BigInteger.valueOf(left)).longValueExact();
            K after = family.nominalAfterOne(state);
            // Capacity is a guaranteed lower bound, possibly saturated or
            // infinite. An exact return may retain more than before - 1 uses
            // (zero wear / repair / saturation); it must never guarantee less.
            long afterCapacity = after != null && family.accepts(after) ? family.capacity(after) : 0;
            if (afterCapacity < before - 1) { stop(Reason.INVALID_STATE_ALGEBRA); return false; }
            BigInteger remaining = value.getValue().subtract(BigInteger.valueOf(use));
            if (remaining.signum() == 0) iterator.remove();
            else value.setValue(remaining);
            slots.add(new GraphRecipe.Slot<>(state, use, slot.inputSlot()));
            returns.add(new Returned<>(state, after, family, use));
            left -= use;
            if (left == 0) return true;
        }
        return false;
    }

    private Map<K, BigInteger> pool(StateFamily<K> family) {
        return pools.computeIfAbsent(family.id(), ignored -> new LinkedHashMap<>());
    }

    private boolean spend(K key, BigInteger amount) {
        BigInteger available = free.getOrDefault(key, BigInteger.ZERO);
        if (available.compareTo(amount) < 0 && !external.contains(key)) return false;
        free.put(key, available.subtract(amount).max(BigInteger.ZERO));
        return true;
    }

    private void verify() {
        if (!verification.step()) return;
        if (force) {
            if (production == null) production = new ForceCraftProof<>(assembly.plan, verification, seeds, budget);
            if (!production.step()) return;
            if (!production.proved()) { stop(Reason.FORCE_CRAFT_NOT_PROVED); return; }
        }
        result = assembly.plan; done = true;
        outcome = new Outcome(Status.VERIFIED, Reason.NONE);
        finishObservation(PlanningBudget.CandidateOutcome.ACCEPTED);
        budget.note("candidate_state", "verified_exact_execution; variants=" + restored.size() +
                "; program_nodes=" + programNodes + "; restored_firings=" + restoredFirings +
                "; restore_calls=" + restoreCalls + "; restore_work=" + restoreWork + "; work=" + (budget.nodes() - workStarted));
    }

    private boolean reserve(long bytes) {
        if (!budget.tryReserve(bytes)) { stop(Reason.WORKSPACE_DECLINED); return false; }
        memory += bytes; return true;
    }

    private boolean stop(Reason reason) {
        done = true; finishObservation(PlanningBudget.CandidateOutcome.INCONCLUSIVE);
        outcome = new Outcome(reason == Reason.LOCAL_WORK_LIMIT || reason == Reason.WORKSPACE_DECLINED ? Status.LIMITED : Status.DECLINED, reason);
        budget.note("candidate_state", "declined; reason=" + reason + "; variants=" + restored.size() +
                "; program_nodes=" + programNodes + "; restored_firings=" + restoredFirings + "; restore_work=" + restoreWork); return true;
    }

    GraphPlan<K> result() { return result; }
    Outcome outcome() { return outcome; }

    CompletableFuture<?> waitingFor() { return planning == null ? null : planning.waitingFor(); }

    private void finishObservation(PlanningBudget.CandidateOutcome outcome) {
        if (observation != null) observation.finish(outcome);
        observation = null;
    }

    @Override public void close() {
        finishObservation(PlanningBudget.CandidateOutcome.ABANDONED);
        if (planning != null) planning.close(); planning = null;
        if (production != null) production.close(); production = null;
        if (verification != null) verification.close(); verification = null;
        if (assembly != null) assembly.close(); assembly = null;
        cursor = null; proposed = null;
        families.clear(); entityFamilies.clear(); known.clear(); activationStates = List.of(); auxiliary.clear(); sources.clear(); activations.clear(); pools.clear();
        free.clear(); restored.clear(); transitions.clear(); program.clear();
        budget.release(memory); memory = 0; done = true;
    }
}
