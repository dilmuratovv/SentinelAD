package uz.sentinel.ad.ledger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerCommandEvent;
import uz.sentinel.ad.protection.TransferProtection;

/**
 * Remembers recent item-giving commands (/give, /kit, item editors, clone and rollback commands...). Other plugins can
 * create items or copies of sealed items through such commands, which the ledger and the stackable sessions cannot
 * tell from a dupe. A finding that a recent item command may explain is flagged for staff review, never removed.
 *
 * <ul>
 * <li>A player's command counts only when it exists and the player may run it: typing "/i" on a server without such a
 * command, or without permission, excuses nothing.</li>
 * <li>A player's own command explains at most one finding per {@code trust-cooldown}, so spamming /kit cannot keep
 * removal off.</li>
 * <li>Console, RCON and command blocks, and staff (op or sentinelad.bypass), are trusted without that limit, but only
 * for the players named in the command; a command naming nobody (or a selector such as @a, or a rollback tool)
 * explains findings about everyone. A vote or crate plugin running "give Alice ..." from the console therefore does
 * not hide a dupe by Bob.</li>
 * </ul>
 * Main thread only.
 */
public final class CommandActivity implements Listener {
    public static final List<String> DEFAULT_COMMANDS = List.of(
            "give", "i", "item", "kit", "kits", "dupe", "clone", "copy", "more", "ie", "itemedit", "itemeditor",
            "cmi", "kitpreview", "playerkits", "pk", "kitgui", "item-give", "loot", "invsee", "openinv",
            "restoreinv", "backpack", "bp", "co", "coreprotect", "core", "rollback", "inventoryrollback", "ir",
            "invrollback", "restore");

    /** Who ran a command and how far they are trusted. */
    public enum Access { NONE, PLAYER, STAFF }

    private final Map<UUID, Long> playerCommand = new HashMap<>();
    private final Map<UUID, Long> trustedCommand = new HashMap<>();
    private final Map<UUID, Long> lastExplained = new HashMap<>();
    private long globalCommand = Long.MIN_VALUE;
    /** Last time a trusted (console/staff) item command named each player; kept for weeks (offline edits). */
    private final Map<UUID, Long> namedAt = new HashMap<>();
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
        String line = strip(commandLine);
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

    private static String strip(String commandLine) {
        String line = commandLine.trim();
        return line.startsWith("/") ? line.substring(1) : line;
    }

    /**
     * The players a command names, resolved with {@code online} (name -> id, null when nobody of that name is
     * online). Null means "everyone": a selector, or no player named at all.
     */
    static Set<UUID> targets(String commandLine, Function<String, UUID> online) {
        String[] words = strip(commandLine).split("\\s+");
        Set<UUID> ids = new HashSet<>();
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            if (w.startsWith("@")) return null;
            if (w.length() < 3 || w.length() > 16 || !w.matches("[A-Za-z0-9_]+")) continue;
            UUID id = online.apply(w);
            if (id != null) ids.add(id);
        }
        return ids.isEmpty() ? null : ids;
    }

    public boolean isItemCommand(String commandLine) { return commands.contains(label(commandLine)); }

    /** A player's own command that exists and that the player may run (tests and simple callers). */
    public void recordPlayer(UUID player, String commandLine, long now) {
        recordPlayer(player, commandLine, now, Access.PLAYER, name -> null);
    }

    public void recordPlayer(UUID player, String commandLine, long now, Access access, Function<String, UUID> online) {
        if (!enabled || access == Access.NONE || !isItemCommand(commandLine)) return;
        if (access == Access.PLAYER) {
            playerCommand.put(player, now);
            return;
        }
        Set<UUID> named = targets(commandLine, online);
        trustedCommand.put(player, now);
        if (named == null && !(commandLine.contains("@") || looksGlobal(commandLine))) {
            UUID[] byName = offlineTargets(commandLine);
            for (UUID id : byName) namedAt.put(id, now);
        }
        if (named == null) {
            // Staff running a command for themselves ("/kit", "/i diamond_sword") only explains their own inventory.
            // A selector or a rollback tool affects others without naming them.
            if (commandLine.contains("@") || looksGlobal(commandLine)) globalCommand = now;
            return;
        }
        for (UUID id : named) { trustedCommand.put(id, now); namedAt.put(id, now); }
    }

    /** Offline players named by a command (an admin editing an offline inventory); resolved lazily, may be empty. */
    private UUID[] offlineTargets(String commandLine) {
        if (offlineResolver == null) return new UUID[0];
        List<UUID> out = new java.util.ArrayList<>();
        String[] words = strip(commandLine).split("\\s+");
        for (int i = 1; i < words.length; i++) {
            String w = words[i];
            if (w.length() < 3 || w.length() > 16 || !w.matches("[A-Za-z0-9_]+")) continue;
            UUID id = offlineResolver.apply(w);
            if (id != null) out.add(id);
        }
        return out.toArray(new UUID[0]);
    }

    private Function<String, UUID> offlineResolver;

    /** Name -> UUID of players who have played here (cached by the server); null in tests. */
    public void offlineResolver(Function<String, UUID> resolver) { this.offlineResolver = resolver; }

    /**
     * True when a trusted item command named this player, or affected everyone, after {@code since}: admin tools
     * (OpenInv, rollbacks) edit offline inventories that way.
     */
    public boolean namedSince(UUID player, long since, long now) {
        if (!enabled) return false;
        if (globalCommand != Long.MIN_VALUE && globalCommand >= since) return true;
        Long t = namedAt.get(player);
        return t != null && t >= since;
    }

    /** Rollback and restore tools act on the world or on other players without naming an online player. */
    private static boolean looksGlobal(String commandLine) {
        String label = label(commandLine);
        return switch (label) {
            case "co", "coreprotect", "core", "rollback", "restore", "inventoryrollback", "ir", "invrollback", "restoreinv" -> true;
            default -> false;
        };
    }

    /** Console, RCON or command block (tests and simple callers: nobody resolvable, so it explains everyone). */
    public void recordConsole(String commandLine, long now) { recordConsole(commandLine, now, name -> null); }

    public void recordConsole(String commandLine, long now, Function<String, UUID> online) {
        if (!enabled || !isItemCommand(commandLine)) return;
        Set<UUID> named = targets(commandLine, online);
        if (named == null) {
            UUID[] byName = offlineTargets(commandLine);
            // A command for an offline player ("give Bob ..." while Bob is away) explains nothing about players online.
            if (byName.length > 0 && !commandLine.contains("@")) { for (UUID id : byName) namedAt.put(id, now); return; }
            globalCommand = now;
            return;
        }
        for (UUID id : named) { trustedCommand.put(id, now); namedAt.put(id, now); }
    }

    /** Why a finding may be explained: not at all, by the player's own command, or by a trusted one (console, staff). */
    public enum Excuse { NONE, OWN, TRUSTED }

    /**
     * True when a recent item command may explain a finding about {@code player} (null: nobody to blame). Consumes the
     * player's trust when it is the player's own command that explains it.
     */
    public boolean explains(UUID player, long now) { return excuse(player, now, now) != Excuse.NONE; }

    /** Like {@link #explains(UUID, long)}, also counting commands since {@code since} (e.g. when a container opened). */
    public boolean explains(UUID player, long since, long now) { return excuse(player, since, now) != Excuse.NONE; }

    public Excuse excuse(UUID player, long since, long now) {
        if (!enabled) return Excuse.NONE;
        long from = Math.min(since, now - windowMillis);
        if (globalCommand != Long.MIN_VALUE && globalCommand >= from) return Excuse.TRUSTED;
        if (player == null) return Excuse.NONE;
        Long trusted = trustedCommand.get(player);
        if (trusted != null && trusted >= from) return Excuse.TRUSTED;
        Long t = playerCommand.get(player);
        if (t == null || t < from) return Excuse.NONE;
        Long last = lastExplained.get(player);
        if (last != null && now - last < trustCooldownMillis) return Excuse.NONE;
        lastExplained.put(player, now);
        return Excuse.OWN;
    }

    private static UUID online(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p == null ? null : p.getUniqueId();
    }

    /** Whether the command exists for this player and they may run it; staff are op or have the bypass permission. */
    static Access access(Player player, String commandLine) {
        String line = strip(commandLine);
        int space = line.indexOf(' ');
        String typed = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        Command command;
        try { command = Bukkit.getCommandMap().getCommand(typed); } catch (RuntimeException e) { command = null; }
        if (command == null || !command.testPermissionSilent(player)) return Access.NONE;
        return player.isOp() || player.hasPermission(TransferProtection.BYPASS) ? Access.STAFF : Access.PLAYER;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void player(PlayerCommandPreprocessEvent e) {
        if (!enabled || !isItemCommand(e.getMessage())) return;
        recordPlayer(e.getPlayer().getUniqueId(), e.getMessage(), System.currentTimeMillis(), access(e.getPlayer(), e.getMessage()),
                CommandActivity::online);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void console(ServerCommandEvent e) { // console, RCON and command blocks
        recordConsole(e.getCommand(), System.currentTimeMillis(), CommandActivity::online);
    }

    @EventHandler public void quit(PlayerQuitEvent e) { playerCommand.remove(e.getPlayer().getUniqueId()); }

    /** Drops stale entries (called from the ledger's prune task). */
    public void prune(long now) {
        playerCommand.values().removeIf(t -> now - t > windowMillis);
        trustedCommand.values().removeIf(t -> now - t > windowMillis);
        lastExplained.values().removeIf(t -> now - t > trustCooldownMillis);
        namedAt.values().removeIf(t -> now - t > 60L * 86_400_000L);
    }
}
