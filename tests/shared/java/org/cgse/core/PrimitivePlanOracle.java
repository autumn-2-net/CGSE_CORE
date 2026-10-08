// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.cgse.core;

import java.math.BigInteger;
import java.util.*;

/** Independent primitive-arc prefix arithmetic; does not call the production validator. */
final class PrimitivePlanOracle {
    record Summary(Map<String, BigInteger> need, Map<String, BigInteger> delta) {}

    static Summary summary(PlanStep step, Map<String, GraphRecipe<String>> recipes, Map<PlanStep, Summary> memo) {
        if (memo.containsKey(step)) return memo.get(step);
        Map<String, BigInteger> need = new LinkedHashMap<>(), delta = new LinkedHashMap<>();
        if (step instanceof PlanStep.Batch batch) {
            GraphRecipe<String> recipe = Objects.requireNonNull(recipes.get(batch.recipe()));
            BigInteger times = BigInteger.valueOf(batch.runs());
            Set<String> keys = new HashSet<>(recipe.inputs().keySet());
            keys.addAll(recipe.outputs().keySet());
            if (times.signum() > 0) for (String key : keys) {
                BigInteger input = BigInteger.valueOf(recipe.inputs().getOrDefault(key, 0L));
                BigInteger change = BigInteger.valueOf(recipe.outputs().getOrDefault(key, 0L)).subtract(input);
                need.put(key, input.add(change.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
                delta.put(key, change.multiply(times));
            }
        } else if (step instanceof PlanStep.Repeat repeat) {
            Summary body = summary(repeat.body(), recipes, memo);
            BigInteger times = BigInteger.valueOf(repeat.times());
            if (times.signum() > 0) for (String key : body.need.keySet()) {
                BigInteger change = body.delta.getOrDefault(key, BigInteger.ZERO);
                need.put(key, body.need.get(key).add(change.negate().max(BigInteger.ZERO).multiply(times.subtract(BigInteger.ONE))));
                delta.put(key, change.multiply(times));
            }
        } else for (PlanStep child : ((PlanStep.Sequence) step).children()) {
            Summary body = summary(child, recipes, memo);
            body.need.forEach((key, value) -> need.merge(key, value.subtract(delta.getOrDefault(key, BigInteger.ZERO)).max(BigInteger.ZERO), BigInteger::max));
            body.delta.forEach((key, value) -> delta.merge(key, value, BigInteger::add));
        }
        Summary result = new Summary(need, delta);
        memo.put(step, result);
        return result;
    }
}
