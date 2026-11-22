package uz.sentinel.ad.ledger;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which holder each serial was last seen in. Main-thread only; {@link #snapshot()} produces an immutable copy for
 * background saving. A serial whose holder is {@code null} is "in transit" (taken out, not yet seen elsewhere).
 */
public final class LedgerStore {
    /** The same serial was sighted in {@code holder} while the ledger already places it in {@code previous}. */
    public record Conflict(String serial, String previous, Sighting sighting) {}

    private static final class Entry {
        String holder;
        long seen;
        Entry(String holder, long seen) { this.holder = holder; this.seen = seen; }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, Set<String>> byHolder = new HashMap<>();
    private final Map<String, Long> savedWorldTimes = new HashMap<>();

    /** World UUID -> world time recorded in the loaded file (empty for files written before 1.2.0). */
    public Map<String, Long> savedWorldTimes() { return Map.copyOf(savedWorldTimes); }

    /**
     * True when a world now has an older time than the ledger recorded for it, i.e. it was restored from a backup or
     * rolled back. {@code toleranceTicks} absorbs the few ticks between the ledger snapshot and the world save.
     */
    public static boolean rolledBack(Long recorded, long current, long toleranceTicks) {
        return recorded != null && current + toleranceTicks < recorded;
    }

    public int size() { return entries.size(); }
    public int holders() { return byHolder.size(); }

    public String holderOf(String serial) {
        Entry e = entries.get(serial);
        return e == null ? null : e.holder;
    }

    public boolean known(String serial) { return entries.containsKey(serial); }

    /** Records a scan. Returns the sightings that clash with a different (or the same) holder. */
    public List<Conflict> update(String holder, List<Sighting> sightings, long now) {
        List<Conflict> conflicts = new ArrayList<>();
        Set<String> seenNow = new HashSet<>();
        for (Sighting s : sightings) {
            if (!seenNow.add(s.serial())) { // two copies in the very same holder
                conflicts.add(new Conflict(s.serial(), holder, s));
                continue;
            }
            Entry e = entries.get(s.serial());
            if (e == null) {
                entries.put(s.serial(), new Entry(holder, now));
                byHolder.computeIfAbsent(holder, k -> new HashSet<>()).add(s.serial());
            } else if (holder.equals(e.holder)) {
                e.seen = now;
            } else if (e.holder == null) {
                assign(s.serial(), e, holder, now);
            } else {
                conflicts.add(new Conflict(s.serial(), e.holder, s));
            }
        }
        Set<String> previous = byHolder.get(holder);
        if (previous != null) {
            for (String serial : new ArrayList<>(previous)) {
                if (seenNow.contains(serial)) continue;
                Entry e = entries.get(serial);
                if (e != null && holder.equals(e.holder)) e.holder = null;
                previous.remove(serial);
            }
            if (previous.isEmpty()) byHolder.remove(holder);
        }
        return conflicts;
    }

    /** Declares {@code holder} the legitimate location of the serial. */
    public void moveTo(String serial, String holder, long now) {
        Entry e = entries.get(serial);
        if (e == null) {
            entries.put(serial, new Entry(holder, now));
            byHolder.computeIfAbsent(holder, k -> new HashSet<>()).add(serial);
        } else {
            assign(serial, e, holder, now);
        }
    }

    public void forget(String serial) {
        Entry e = entries.remove(serial);
        if (e != null && e.holder != null) {
            Set<String> set = byHolder.get(e.holder);
            if (set != null) { set.remove(serial); if (set.isEmpty()) byHolder.remove(e.holder); }
        }
    }

    private void assign(String serial, Entry e, String holder, long now) {
        if (e.holder != null) {
            Set<String> old = byHolder.get(e.holder);
            if (old != null) { old.remove(serial); if (old.isEmpty()) byHolder.remove(e.holder); }
        }
        e.holder = holder;
        e.seen = now;
        byHolder.computeIfAbsent(holder, k -> new HashSet<>()).add(serial);
    }

    /** Drops entries not seen since {@code cutoff}; returns how many. */
    public int prune(long cutoff) {
        List<String> old = new ArrayList<>();
        for (Map.Entry<String, Entry> e : entries.entrySet()) if (e.getValue().seen < cutoff) old.add(e.getKey());
        old.forEach(this::forget);
        return old.size();
    }

    public void clear() { entries.clear(); byHolder.clear(); }

    /** Forgets where everything is (used after an unclean shutdown). Serials stay known. */
    public void forgetHolders() {
        byHolder.clear();
        for (Entry e : entries.values()) e.holder = null;
    }

    // ------------------------------------------------------------------ persistence

    public List<String> snapshot() {
        List<String> lines = new ArrayList<>(entries.size());
        for (Map.Entry<String, Entry> e : entries.entrySet()) {
            lines.add(e.getKey() + "\t" + (e.getValue().holder == null ? "-" : e.getValue().holder) + "\t" + e.getValue().seen);
        }
        return lines;
    }

    public static void write(Path file, List<String> snapshot, boolean clean) throws IOException {
        write(file, snapshot, clean, Map.of());
    }

    /**
     * {@code worldTimes}: world UUID -> world game time when the snapshot was taken. A world that later loads with an
     * older time was restored from a backup or rolled back, so the recorded item locations no longer hold.
     */
    public static void write(Path file, List<String> snapshot, boolean clean, Map<String, Long> worldTimes) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            StringBuilder header = new StringBuilder("#sentinelad-ledger v1 clean=").append(clean);
            if (!worldTimes.isEmpty()) {
                header.append(" worlds=");
                boolean first = true;
                for (Map.Entry<String, Long> e : worldTimes.entrySet()) {
                    if (!first) header.append(',');
                    first = false;
                    header.append(e.getKey()).append('=').append(e.getValue());
                }
            }
            w.write(header.toString());
            w.newLine();
            for (String line : snapshot) { w.write(line); w.newLine(); }
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Loads the file; returns false when it was not written by a clean shutdown (holders are then forgotten). */
    public boolean load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) return true;
        boolean clean = false;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            savedWorldTimes.clear();
            if (line != null && line.startsWith("#sentinelad-ledger")) {
                clean = line.contains("clean=true");
                int at = line.indexOf(" worlds=");
                if (at >= 0) {
                    String list = line.substring(at + 8);
                    int end = list.indexOf(' ');
                    if (end >= 0) list = list.substring(0, end);
                    for (String part : list.split(",")) {
                        int eq = part.lastIndexOf('=');
                        if (eq <= 0) continue;
                        try { savedWorldTimes.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1))); }
                        catch (NumberFormatException ignored) { /* skip a damaged entry */ }
                    }
                }
            }
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                long seen;
                try { seen = Long.parseLong(p[2]); } catch (NumberFormatException e) { continue; }
                String holder = p[1].equals("-") ? null : p[1];
                entries.put(p[0], new Entry(holder, seen));
                if (holder != null) byHolder.computeIfAbsent(holder, k -> new HashSet<>()).add(p[0]);
            }
        }
        if (!clean) forgetHolders();
        return clean;
    }
}
