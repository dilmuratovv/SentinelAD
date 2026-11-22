package uz.sentinel.ad.diagnostics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded in-memory history of the latest findings (newest first when read). */
public final class RecentFindings {
    private final int capacity;
    private final Deque<Finding> items = new ArrayDeque<>();

    public RecentFindings(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    public synchronized void add(Finding finding) {
        if (items.size() >= capacity) items.removeFirst();
        items.addLast(finding);
    }

    /** Up to {@code limit} findings, newest first. */
    public synchronized List<Finding> latest(int limit) {
        List<Finding> out = new ArrayList<>(Math.min(limit, items.size()));
        var it = items.descendingIterator();
        while (it.hasNext() && out.size() < limit) out.add(it.next());
        return out;
    }

    public synchronized int size() {
        return items.size();
    }
}
