package org.gtlcore.aedump;

import org.cgse.core.PlanningBudget;
import java.io.*;
import java.util.*;

public final class Trace {
    public interface Access { Trace aedump$get(); void aedump$set(Trace trace); }
    public final CaptureRecord record;
    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private long sequence, dropped;
    public volatile boolean limited;
    private final long start = System.nanoTime();
    public Trace(CaptureRecord record) { this.record = record; }
    public synchronized void event(PlanningBudget budget, String stage, String detail) {
        var e = new LinkedHashMap<String, Object>();
        e.put("sequence", Long.toString(sequence++)); e.put("elapsed_ns", Long.toString(System.nanoTime() - start));
        e.put("thread", Thread.currentThread().getName()); e.put("stage", stage);
        e.put("work", Long.toString(budget.nodes())); e.put("reserved_bytes", Long.toString(budget.reservedBytes()));
        e.put("phase", budget.phase().name());
        e.put("detail", detail == null ? null : detail.substring(0, Math.min(detail.length(), 4096)));
        if (events.size() == 2048) { events.removeFirst(); dropped++; }
        events.addLast(e);
    }
    public synchronized Map<String, Object> snapshot() {
        return Map.of("events", List.copyOf(events), "dropped_events", Long.toString(dropped), "limited", limited);
    }
    public static Trace of(PlanningBudget budget) { return (Object) budget instanceof Access a ? a.aedump$get() : null; }
    public static String stack(Throwable error) {
        if (error == null) return null;
        StringWriter out = new StringWriter(); error.printStackTrace(new PrintWriter(out));
        String text = out.toString(); return text.substring(0, Math.min(text.length(), 32768));
    }
    public static Map<String, Object> budget(PlanningBudget b) {
        var r = Reflect.scalars(b, "maxBytes", "maxNodes", "timeoutNanos");
        r.put("phase", b.phase().name()); r.put("work", Long.toString(b.nodes()));
        r.put("reserved_bytes", Long.toString(b.reservedBytes())); r.put("peak_bytes", Long.toString(b.peakBytes()));
        r.put("active_ns", Long.toString(b.elapsedNanos())); r.put("wall_ns", Long.toString(b.runningWallNanos()));
        r.put("queue_ns", Long.toString(b.waitingNanos())); r.put("failure_detail", b.failureDetail());
        r.put("diagnostics", b.diagnostics()); return r;
    }
}
