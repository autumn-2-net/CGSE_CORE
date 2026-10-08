// Copyright (c) 2026 autumn
// SPDX-License-Identifier: MPL-2.0

package org.gtlcore.aedump;

import java.util.Map;

/** Named diagnostic projection across the host capture and portable request coordinator. */
final class RequestState {

    private RequestState() {}

    static Object solver(Object work) {
        Object planning = Reflect.get(work, "planning");
        return planning == null ? work : planning;
    }

    static Map<String, Object> coordinator(Object work) {
        var state = Reflect.scalars(solver(work), "partialSearch", "directEmission", "unavailableTarget", "tryEstimate",
                "tryNeighbor", "fallbackAttempted", "fallbackMode", "low", "high", "middle", "estimatedAmount");
        state.putAll(Reflect.scalars(work, "snapshotNanos", "snapshotElapsedNanos", "catalogPreparationNanos",
                "catalogParallelBatches", "catalogPreparationElapsed", "catalogParallelNanos"));
        return state;
    }
}
