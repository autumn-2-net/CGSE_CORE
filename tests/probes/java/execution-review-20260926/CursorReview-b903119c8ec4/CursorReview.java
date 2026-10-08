package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

public final class CursorReview {
    public static void main(String[] args) {
        Random random = new Random(20260927);
        for (int trial = 0; trial < 3000; trial++) {
            List<PlanStep> pool = new ArrayList<>();
            for (int i = 0; i < 4; i++) pool.add(new PlanStep.Batch("r" + i, random.nextInt(4)));
            for (int i = 0; i < 10; i++) {
                PlanStep child = pool.get(random.nextInt(pool.size()));
                pool.add(random.nextBoolean() ? new PlanStep.Repeat(child, random.nextInt(4)) :
                    new PlanStep.Sequence(List.of(child, pool.get(random.nextInt(pool.size())))));
            }
            PlanStep root = new PlanStep.Sequence(pool.subList(10, pool.size()));
            List<String> flat = new ArrayList<>();
            flatten(root, flat);
            PlanCursor cursor = new PlanCursor(root);
            int offset = 0;
            while (offset < flat.size()) {
                if (random.nextBoolean()) cursor = new PlanCursor(root, cursor.snapshot());
                PlanStep.Batch batch = cursor.current();
                int end = offset;
                while (end < flat.size() && flat.get(end).equals(flat.get(offset))) end++;
                if (batch == null || !batch.recipe().equals(flat.get(offset)) || batch.runs() > end - offset)
                    throw new AssertionError("Order changed " + trial);
                Map<String,BigInteger> wanted = new HashMap<>();
                for (int i = offset; i < flat.size(); i++) wanted.merge(flat.get(i), BigInteger.ONE, BigInteger::add);
                if (!wanted.equals(cursor.remainingCountsExact())) throw new AssertionError("Counts changed " + trial);
                int use = 1 + random.nextInt((int)batch.runs());
                cursor.dispatched(use);
                offset += use;
            }
            if (cursor.current() != null || !cursor.remainingCountsExact().isEmpty()) throw new AssertionError("Work left");

            // Older saves assign pre-order IDs and may stop inside a now-batched sequence.
            LegacyPlanCursor old = new LegacyPlanCursor(root);
            offset = 0;
            while (old.current() != null) {
                var saved = old.snapshot().stream().map(p -> new PlanCursor.Position(p.node(), p.remaining())).toList();
                PlanCursor restored = new PlanCursor(root, saved);
                if (!restored.snapshot().equals(saved) || !restored.remainingCountsExact().equals(old.remainingCountsExact()))
                    throw new AssertionError("Old cursor incompatible " + trial);
                var batch = restored.current();
                if (!batch.recipe().equals(flat.get(offset))) throw new AssertionError("Restored order");
                old.dispatched(1);
                offset++;
            }
        }
        for (int depth : new int[]{60,63,65,70}) {
            PlanStep root = new PlanStep.Batch("a", 1);
            for (int i=0;i<depth;i++) root = new PlanStep.Sequence(List.of(root,root));
            PlanCursor cursor = new PlanCursor(root);
            BigInteger remaining = BigInteger.ONE.shiftLeft(depth);
            int dispatches = 0;
            while (remaining.signum() > 0) {
                var batch = cursor.current();
                if (batch == null || !cursor.remainingCountsExact().equals(Map.of("a",remaining))) throw new AssertionError("Large counts");
                long use = batch.runs();
                if (dispatches == 0) use = Math.min(use, 3);
                cursor.dispatched(use);
                remaining = remaining.subtract(BigInteger.valueOf(use));
                cursor = new PlanCursor(root,cursor.snapshot());
                if (++dispatches > 400) throw new AssertionError("Shared homogeneous program expanded");
            }
            if (cursor.current() != null) throw new AssertionError("Large program unfinished");
        }
        PlanStep empty = new PlanStep.Sequence(List.of());
        for (int i=0;i<10000;i++) empty = new PlanStep.Sequence(List.of(empty,empty));
        if (new PlanCursor(empty).current() != null) throw new AssertionError("Empty shared tree");
        PlanStep mixed = new PlanStep.Sequence(List.of(new PlanStep.Batch("a",1),new PlanStep.Batch("b",1)));
        for (int i=0;i<4000;i++) mixed = new PlanStep.Sequence(List.of(mixed,mixed));
        PlanCursor deep = new PlanCursor(mixed);
        deep.dispatched(1);
        BigInteger total = BigInteger.ONE.shiftLeft(4000);
        if (!deep.remainingCountsExact().equals(Map.of("a",total.subtract(BigInteger.ONE),"b",total))) throw new AssertionError("Mixed suffix");
        System.out.println("PASS: 3000 flattened execution/reload oracles, legacy save positions, 60..70-level large batches, 10000 empty / 4000 mixed shared levels");
    }

    static void flatten(PlanStep step, List<String> out) {
        if (step instanceof PlanStep.Batch batch) for (long i=0;i<batch.runs();i++) out.add(batch.recipe());
        else if (step instanceof PlanStep.Repeat repeat) for (long i=0;i<repeat.times();i++) flatten(repeat.body(),out);
        else for (PlanStep child : ((PlanStep.Sequence)step).children()) flatten(child,out);
    }
}
