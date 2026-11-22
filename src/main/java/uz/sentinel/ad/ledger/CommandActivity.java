package uz.sentinel.ad.ledger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;

/**
 * Remembers recent item-giving commands (/give, /kit, item editors, clone commands...). Other plugins can create items
 * or copies of sealed items through such commands, which the ledger and the stackable sessions cannot tell from a dupe.
 * A finding that a recent item command may explain is only flagged (lower confidence), never removed.
 *
 * <p>A player's own command explains at most one finding per {@code trust-cooldown}, so a cheater cannot keep removal
 * off by spamming /kit. Console, RCON and command-block commands (which players cannot run) have no such limit.
 * Main thread only.</p>
 */
public final class CommandActivity implements Listener {
    public static final List<String> DEFAULT_COMMANDS = List.of(
            "give", "i", "item", "kit", "kits", "dupe", "clone", "copy", "more", "ie", "itemedit", "itemeditor",
            "cmi", "kitpreview", "playerkits", "pk", "kitgui", "item-give", "loot", "invsee", "openinv",
            "restoreinv", "backpack", "bp");

    private final Map<UUID, Long> playerCommand = new HashMap<>();
    private final Map<UUID, Long> lastExplained = new HashMap<>();
    private long consoleCommand = Long.MIN_VALUE;
    private Set<String> commands = new HashSet<>(DEFAULT_COMMANDS);
    private long windowMillis = 10_000;
    private long trustCooldownMillis = 10 * 60_000L;
    private boolean enabled = true;

    public void configure(ConfigurationSection c) {
        if (c == null) return;
        enabled = c.getBoolean("enabled", true);
        windowMillis = Math.max(0, c.getLong("window-seconds", 10)) * 1000L;
        trustCooldownMillis = Math.max(0, c.getLong("player-trust-cooldown-minutes", 10)) * 60_000L;
        List<String> list = c.isList("commands") ? c.getStringList("commands") : DEFAULT_COMMANDS;
        Set<String> set = new HashSet<>();
        for (String s : list) set.add(s.trim().toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        commands = set;
    }

    /** The command label without slash and namespace, lower case ("/essentials:give x" -> "give"). */
    static String label(String commandLine) {
        String line = commandLine.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        String label = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        // "/execute as x at y run give ..." runs "give": look at the command after the last " run ".
        if (label.equals("execute")) {
            int run = line.toLowerCase(Locale.ROOT).lastIndexOf(" run ");
            if (run >= 0) return label(line.substring(run + 5));
        }
        return label;
    }

    public boolean isItemCommand(String commandLine) { return commands.contains(label(commandLine)); }

    public void recordPlayer(UUID player, String commandLine, long now) {
        if (enabled && isItemCommand(commandLine)) playerCommand.put(player, now);
    }

    public void recordConsole(String commandLine, long now) {
        if (enabled && isItemCommand(commandLine)) consoleCommand = now;
    }

    /**
     * True when a recent item command may explain a finding about {@code player} (null: nobody to blame). Consumes the
     * player's trust when it is the player's own command that explains it.
     */
    public boolean explains(UUID player, long now) { return explains(player, now, now); }

    /** Like {@link #explains(UUID, long)}, also counting commands since {@code since} (e.g. when a container opened). */
    public boolean explains(UUID player, long since, long now) {
        if (!enabled) return false;
        long from = Math.min(since, now - windowMillis);
        if (consoleCommand != Long.MIN_VALUE && consoleCommand >= from) return true;
        if (player == null) return false;
        Long t = playerCommand.get(player);
        if (t == null || t < from) return false;
        Long last = lastExplained.get(player);
        if (last != null && now - last < trustCooldownMillis) return false;
        lastExplained.put(player, now);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void player(PlayerCommandPreprocessEvent e) {
        recordPlayer(e.getPlayer().getUniqueId(), e.getMessage(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void console(ServerCommandEvent e) { // console, RCON and command blocks
        recordConsole(e.getCommand(), System.currentTimeMillis());
    }

    @EventHandler public void quit(PlayerQuitEvent e) { playerCommand.remove(e.getPlayer().getUniqueId()); }

    /** Drops stale entries (called from the ledger's prune task). */
    public void prune(long now) {
        playerCommand.values().removeIf(t -> now - t > windowMillis);
        lastExplained.values().removeIf(t -> now - t > trustCooldownMillis);
    }
}
