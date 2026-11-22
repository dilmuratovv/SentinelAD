package uz.sentinel.ad.diagnostics;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Counts serious findings per player inside a sliding window. Main thread only. */
public final class Escalation {
    private final Map<UUID, ArrayDeque<Long>> hits = new HashMap<>();

    /**
     * Records a hit at {@code now} (milliseconds). Returns true when the player has reached {@code threshold} hits
     * inside the last {@code windowMillis}; the counter then restarts so one burst triggers once.
     */
    public boolean record(UUID player, long now, int threshold, long windowMillis) {
        ArrayDeque<Long> times = hits.computeIfAbsent(player, k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > windowMillis) times.pollFirst();
        times.addLast(now);
        if (times.size() >= Math.max(1, threshold)) {
            hits.remove(player);
            return true;
        }
        return false;
    }

    public void forget(UUID player) { hits.remove(player); }

    public void clear() { hits.clear(); }
}
