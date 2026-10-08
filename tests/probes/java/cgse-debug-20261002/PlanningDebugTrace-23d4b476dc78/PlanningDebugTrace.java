package org.cgse.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local debug overlay only. Never charge diagnostics to the solver or walk live world objects. */
public final class PlanningDebugTrace {
    private static final AtomicLong IDS = new AtomicLong();
    public final long id = IDS.incrementAndGet();
    public final String purpose;
    public volatile Object attachment;
    private final long began = System.nanoTime();
    private final List<Event> first = new ArrayList<>();
    private final Deque<Event> tail = new ArrayDeque<>();
    private long sequence, dropped, denied;
    private int characters;
    private boolean limited;
    private final Map<String, Long> reservations = new LinkedHashMap<>();

    public record Event(long sequence, long elapsedNanos, String thread, String phase,
                        long work, long reservedBytes, long peakBytes, String kind, String detail) {}

    public PlanningDebugTrace(String purpose) {
        this.purpose = purpose;
    }

    public synchronized void event(PlanningBudget budget, String kind, String detail) {
        if (detail == null) detail = "null";
        if (detail.length() > 32768) detail = detail.substring(0, 32768) + " [DETAIL_TRUNCATED]";
        Event e = new Event(++sequence, System.nanoTime() - began, Thread.currentThread().getName(),
                budget.phase().name(), budget.nodes(), budget.reservedBytes(), budget.peakBytes(), kind, detail);
        // Retain the beginning and the most recent transitions. This is not a per-node trace.
        if (first.size() < 128) first.add(e);
        else {
            tail.addLast(e);
            characters += detail.length() + 160;
            while (tail.size() > 8192 || characters > 1_048_576) {
                Event old = tail.removeFirst();
                characters -= old.detail().length() + 160;
                dropped++;
            }
        }
    }

    public synchronized void reservation(PlanningBudget budget, long bytes, boolean optional) {
        denied++;
        String key = budget.phase() + (optional ? ":optional_workspace_declined" : ":hard_limit");
        reservations.merge(key + ":count", 1L, Long::sum);
        reservations.merge(key + ":max_request", bytes, Math::max);
        // Rejected optional strategies can repeat millions of times. Sample their callers.
        if (!optional || denied <= 16 || (denied & (denied - 1)) == 0)
            event(budget, optional ? "workspace_declined" : "memory_exhausted",
                    "requested_bytes=" + bytes + "; denial_number=" + denied + "\n" + stack(new Throwable("reservation origin")));
    }

    public synchronized void limit(PlanningBudget budget, Throwable error) {
        if (!limited) event(budget, "first_exhaustion", stack(error));
        limited = true;
    }

    public synchronized boolean limited() { return limited; }

    public synchronized Map<String, Object> summary() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("request_id", id);
        map.put("purpose", purpose);
        map.put("events_seen", sequence);
        map.put("events_dropped", dropped);
        map.put("trace_complete", dropped == 0);
        map.put("exhausted", limited);
        map.put("workspace_denials", new LinkedHashMap<>(reservations));
        return map;
    }

    public synchronized List<Event> events() {
        List<Event> result = new ArrayList<>(first.size() + tail.size());
        result.addAll(first);
        result.addAll(tail);
        return result;
    }

    public synchronized void detach() {
        attachment = null;
        first.clear();
        tail.clear();
        characters = 0;
    }

    public static String stack(Throwable error) {
        if (error == null) return "";
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
}
