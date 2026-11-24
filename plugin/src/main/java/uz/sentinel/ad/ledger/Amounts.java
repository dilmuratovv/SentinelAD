package uz.sentinel.ad.ledger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Pure helpers for counted items (material name -> amount) and their text form; no server needed.
 * Used by the chunk rollback guard and the offline inventory check.
 */
public final class Amounts {
    private Amounts() {}

    /** Amounts that grew by at least {@code min} from {@code before} to {@code after}. */
    public static Map<String, Integer> gains(Map<String, Integer> before, Map<String, Integer> after, int min) {
        Map<String, Integer> out = new TreeMap<>();
        for (Map.Entry<String, Integer> e : after.entrySet()) {
            int gain = e.getValue() - before.getOrDefault(e.getKey(), 0);
            if (gain >= Math.max(1, min)) out.put(e.getKey(), gain);
        }
        return out;
    }

    public static Map<String, Integer> sum(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new TreeMap<>(a);
        b.forEach((k, v) -> out.merge(k, v, Integer::sum));
        return out;
    }

    /** "DIAMOND=64,EMERALD=3" (sorted); "-" when empty. */
    public static String format(Map<String, Integer> amounts) {
        if (amounts.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new TreeMap<>(amounts).entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<String, Integer> parse(String text) {
        Map<String, Integer> out = new TreeMap<>();
        if (text == null || text.isEmpty() || text.equals("-")) return out;
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try { out.put(part.substring(0, eq), Integer.parseInt(part.substring(eq + 1))); }
            catch (NumberFormatException ignored) { /* damaged entry */ }
        }
        return out;
    }

    public static String formatTimes(Map<UUID, Long> times) {
        if (times.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<UUID, Long> e : times.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<UUID, Long> parseTimes(String text) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty() || text.equals("-")) return out;
        for (String part : text.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            try { out.put(UUID.fromString(part.substring(0, eq)), Long.parseLong(part.substring(eq + 1))); }
            catch (RuntimeException ignored) { /* damaged entry */ }
        }
        return out;
    }

    /** Human text: "+64 diamond, +3 emerald". */
    public static String describe(Map<String, Integer> gains) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : gains.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append('+').append(e.getValue()).append(' ').append(e.getKey().toLowerCase(java.util.Locale.ROOT));
        }
        return sb.toString();
    }
}
