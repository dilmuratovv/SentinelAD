package uz.sentinel.ad.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;
import uz.sentinel.ad.protection.TransferProtection;

/**
 * Offline inventory check. Nothing can legitimately change a player's inventory or ender chest while they are
 * offline, except an admin tool or a command. Disconnect and kick dupes (the server saving the inventory from before
 * items were dropped or handed over) bring the player back with more than they left with.
 *
 * <p>The watched stackables in inventory, cursor, crafting grid and ender chest are counted when the player quits and
 * again when they join (before other plugins' join handlers run). More than before is taken back (backed up) and
 * flagged. Servers that sync player data between servers (HuskSync and the like) are skipped: the inventory comes from
 * another server there.</p>
 */
public final class OfflineAudit implements Listener {
    private static final List<String> SYNC_PLUGINS = List.of("HuskSync", "MySQLPlayerDataBridge", "MysqlPlayerDataBridge",
            "PlayerDataSQL", "SyncMySQL", "InvSync", "InventorySync", "XConomy-Sync", "CrossServerSync", "PlayerSync");

    private record Snapshot(Map<String, Integer> amounts, long at) {}

    private final SentinelADPlugin plugin;
    private final StackSessions stacks;
    private final CommandActivity commands;
    private final Path file;
    private final Map<UUID, Snapshot> snapshots = new HashMap<>();
    private boolean enabled, remove, dirty;
    private int minGain;
    private CrashJournal journal;
    private NetworkStore network;
    private String serverName;
    private boolean syncEvent, trustDelay;
    private long delayTicks;

    public void journal(CrashJournal journal) { this.journal = journal; }
    private String syncPlugin;

    public OfflineAudit(SentinelADPlugin plugin, StackSessions stacks, CommandActivity commands) {
        this.plugin = plugin;
        this.stacks = stacks;
        this.commands = commands;
        this.file = plugin.getDataFolder().toPath().resolve("players.dat");
    }

    public void applyConfig() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("offline-check.enabled", true) && c.getBoolean("stackables.enabled", true)
                && (syncPlugin == null || network != null);
        remove = c.getString("offline-check.on-gain", "remove").equalsIgnoreCase("remove");
        minGain = Math.max(1, c.getInt("offline-check.min-gain", 1));
    }

    public void start(boolean cleanShutdown) {
        for (String name : SYNC_PLUGINS) {
            if (Bukkit.getPluginManager().getPlugin(name) != null) { syncPlugin = name; break; }
        }
        applyConfig();
        if (startNetwork()) return;
        if (syncPlugin != null) {
            plugin.getLogger().warning("Offline inventory check is off: " + syncPlugin + " syncs player data between servers."
                    + " Set network.enabled (a database all servers share) to check dupes between servers.");
            return;
        }
        // After a crash a player who was online may still have an old quit snapshot while their data comes from a
        // later autosave: compare nothing from before the crash.
        if (cleanShutdown) load();
        else {
            if (Files.exists(file)) plugin.getLogger().info("Offline inventory check: last shutdown was not clean, players.dat is not used.");
            // Quit snapshots in the crash journal hold: the server saved those players when they left.
            long at = System.currentTimeMillis() - 1;
            if (journal != null) journal.quitSnapshots().forEach((id, m) -> snapshots.put(id, new Snapshot(m, at)));
        }
        long every = Math.max(10, plugin.getConfig().getLong("ledger.save-interval-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, every, every);
        // A reload with players online: they have no quit snapshot to compare, and none is needed.
        for (Player p : Bukkit.getOnlinePlayers()) snapshots.remove(p.getUniqueId());
    }

    public void stop() {
        if (network != null) {
            // Players still online when the server stops: their data is saved after this, so this is the snapshot.
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (exempt(p)) continue;
                network.put(p.getUniqueId().toString(), new NetworkStore.Row(serverName, System.currentTimeMillis(),
                        Amounts.format(count(p)), String.join(",", serials(p))));
            }
            network.close();
            return;
        }
        if (!enabled) return;
        // Players still online when the server stops quit after plugins are disabled: snapshot them now.
        for (Player p : Bukkit.getOnlinePlayers()) snapshots.put(p.getUniqueId(), new Snapshot(count(p), System.currentTimeMillis()));
        dirty = true;
        save();
    }

    /** A world was restored from a backup: player data may have been restored with it. */
    public void clear() { snapshots.clear(); dirty = true; }

    private Map<String, Integer> count(Player p) {
        Map<String, Integer> m = new TreeMap<>();
        stacks.countInto(m, p.getInventory().getContents());
        stacks.countInto(m, new ItemStack[]{p.getItemOnCursor()});
        stacks.countInto(m, p.getEnderChest().getContents());
        Inventory top = p.getOpenInventory().getTopInventory();
        if (top.getType() == InventoryType.CRAFTING) {
            // The 2x2 grid goes back to the inventory (slot 0 is only the result preview).
            ItemStack[] grid = top.getContents();
            if (grid.length > 1) stacks.countInto(m, java.util.Arrays.copyOfRange(grid, 1, grid.length));
        } else if (!worldStorage(top)) {
            // Work views and plugin menus may hand their items back to the player; counting them can only hide a
            // dupe of that size, never accuse anyone.
            stacks.countInto(m, top.getContents());
        }
        return m;
    }

    /** A chest, barrel, shulker box... in the world, or the ender chest: its items stay there when the player leaves. */
    private static boolean worldStorage(Inventory top) {
        return switch (top.getType()) {
            case ENDER_CHEST -> true;
            case CHEST, BARREL, SHULKER_BOX, HOPPER, DROPPER, DISPENSER -> top.getLocation() != null;
            default -> false;
        };
    }

    private boolean exempt(Player p) {
        return p.getGameMode() == GameMode.CREATIVE || p.hasPermission(TransferProtection.BYPASS);
    }

    /**
     * The server saves the inventory right after the quit handlers, so the snapshot is taken after that save: 1 tick
     * later and again 1 second later, keeping the lower amount. A dupe that takes items out of the player after the
     * save (dropped, handed over) leaves the saved data with more than the snapshot. Items a plugin gives back while
     * the player quits are in both, so they are no reason to flag.
     */
    @EventHandler(priority = EventPriority.MONITOR) public void quit(PlayerQuitEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();
        snapshots.remove(id);
        if (exempt(p)) return;
        long at = System.currentTimeMillis();
        if (network != null) {
            Set<String>[] seals = new Set[1];
            Map<String, Integer>[] first = new Map[1];
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                first[0] = count(p);
                seals[0] = serials(p);
                network.put(id.toString(), new NetworkStore.Row(serverName, at, Amounts.format(first[0]), String.join(",", seals[0])));
            }, 1L);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (first[0] == null) return;
                Map<String, Integer> now = count(p), low = new TreeMap<>();
                first[0].forEach((k, v) -> low.put(k, Math.min(v, now.getOrDefault(k, 0))));
                Set<String> keep = new java.util.TreeSet<>(seals[0]);
                keep.retainAll(serials(p));
                network.refine(id.toString(), at, Amounts.format(low), String.join(",", keep));
            }, 20L);
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (Bukkit.getPlayer(id) != null) return; // already back: nothing to compare
            snapshots.put(id, new Snapshot(count(p), at));
            dirty = true;
        }, 1L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Snapshot first = snapshots.get(id);
            if (first == null || Bukkit.getPlayer(id) != null) return;
            Map<String, Integer> now = count(p), low = new TreeMap<>();
            first.amounts().forEach((k, v) -> low.put(k, Math.min(v, now.getOrDefault(k, 0))));
            snapshots.put(id, new Snapshot(low, at));
            dirty = true;
            if (journal != null) journal.quit(id, low);
        }, 20L);
    }

    /** LOWEST: before other plugins hand out join rewards. */
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (network != null) {
            // The synced data is not in place yet: wait for the sync plugin, or for the configured delay.
            if (!syncEvent) Bukkit.getScheduler().runTaskLater(plugin, () -> networkCheck(p, 0), delayTicks);
            return;
        }
        Snapshot before = snapshots.remove(p.getUniqueId());
        if (before == null) return;
        dirty = true;
        if (exempt(p)) return;
        settle(p, Amounts.gains(before.amounts(), count(p), minGain), Set.of(), before.at(), false, true, "disconnect dupe: ");
    }

    /** Called by the sync plugin's "data applied" event (HuskSync), or after the delay. */
    private void networkCheck(Player p, int attempt) {
        if (!p.isOnline() || network == null) return;
        UUID id = p.getUniqueId();
        network.take(id.toString(), result -> Bukkit.getScheduler().runTask(plugin, () -> {
            NetworkStore.Row row = (NetworkStore.Row) result[0];
            long edit = (Long) result[1];
            if (row == null) {
                // The server the player left may still be writing it (a fast server switch): ask again.
                if (attempt < 3) Bukkit.getScheduler().runTaskLater(plugin, () -> networkCheck(p, attempt + 1), 20L);
                return;
            }
            if (!p.isOnline() || exempt(p)) return;
            Set<String> before = new java.util.HashSet<>(row.serials().isEmpty() ? List.of() : List.of(row.serials().split(",")));
            Set<String> extra = new java.util.TreeSet<>(serials(p));
            extra.removeAll(before);
            Map<String, Integer> gains = Amounts.gains(Amounts.parse(row.amounts()), count(p), minGain);
            boolean excused = edit != Long.MIN_VALUE && edit >= row.at();
            settle(p, gains, extra, row.at(), excused, syncEvent || trustDelay,
                    "network dupe: data loaded on this server is older than what " + row.server() + " saw when they left; ");
        }));
    }

    /** Hooks HuskSync's "sync complete" event without a compile-time dependency. */
    @SuppressWarnings("unchecked")
    private boolean hookSyncEvent() {
        var husk = Bukkit.getPluginManager().getPlugin("HuskSync");
        if (husk == null) return false;
        try {
            Class<?> type = Class.forName("net.william278.husksync.event.BukkitSyncCompleteEvent", true, husk.getClass().getClassLoader());
            Bukkit.getPluginManager().registerEvent((Class<? extends org.bukkit.event.Event>) type, this, EventPriority.LOWEST, (l, ev) -> {
                if (!type.isInstance(ev)) return;
                Player p = syncedPlayer(ev);
                if (p != null && enabled) networkCheck(p, 0);
            }, plugin);
            return true;
        } catch (ClassNotFoundException | RuntimeException e) {
            plugin.getLogger().warning("HuskSync is installed but its sync event was not found (" + e + "); checking after "
                    + delayTicks + " ticks instead.");
            return false;
        }
    }

    private static Player syncedPlayer(Object event) {
        if (event instanceof org.bukkit.event.player.PlayerEvent pe) return pe.getPlayer();
        try { // HuskSync: getUser() -> OnlineUser, which has getPlayer() on Bukkit
            Object user = event.getClass().getMethod("getUser").invoke(event);
            Object player = user.getClass().getMethod("getPlayer").invoke(user);
            return player instanceof Player p ? p : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private boolean startNetwork() {
        var c = plugin.getConfig();
        if (!c.getBoolean("network.enabled", false)) return false;
        String url = c.getString("network.jdbc-url", "");
        if (url.isBlank()) {
            plugin.getLogger().severe("network.enabled is true but network.jdbc-url is empty; dupes between servers are not checked.");
            return false;
        }
        serverName = c.getString("network.server-name", "");
        if (serverName.isBlank()) serverName = new java.io.File(".").getAbsoluteFile().getParentFile().getName();
        delayTicks = Math.max(1, c.getLong("network.check-delay-ticks", 40));
        trustDelay = c.getBoolean("network.remove-after-delay", false);
        NetworkStore store = new NetworkStore(url, c.getString("network.username", ""), c.getString("network.password", ""), plugin.getLogger());
        String error = store.test();
        if (error != null) {
            plugin.getLogger().severe("Network store " + url.replaceAll("password=[^&;]*", "password=***") + " is not reachable ("
                    + error + "); dupes between servers are not checked.");
            store.close();
            return false;
        }
        network = store;
        commands.onNamed(id -> network.edited(id.toString(), System.currentTimeMillis()));
        applyConfig();
        syncEvent = hookSyncEvent();
        plugin.getLogger().info("Network dupe check on (server " + serverName + "): " + (syncEvent ? "after HuskSync applies the data."
                : "after " + delayTicks + " ticks; stackables " + (trustDelay ? "removed." : "flagged only (join rewards may land in that time).")));
        return true;
    }

    /** The seals the player holds (inventory, cursor, work slots, ender chest, also inside shulker boxes and bundles). */
    private Set<String> serials(Player p) {
        Set<String> out = new java.util.TreeSet<>();
        if (plugin.ledger() == null) return out;
        for (Sighting s : plugin.ledger().holders().player(p, false).sightings()) out.add(s.serial());
        for (Sighting s : plugin.ledger().holders().ender(p, false).sightings()) out.add(s.serial());
        return out;
    }

    /**
     * Takes back what the player came back with that they did not have when they left: stackable gains and, between
     * servers, sealed items that had left them after the save (that copy is still on the other server).
     */
    private void settle(Player p, Map<String, Integer> gains, Set<String> extraSerials, long since, boolean excusedRemotely,
                        boolean removeStacks, String prefix) {
        if (gains.isEmpty() && extraSerials.isEmpty()) return;
        long now = System.currentTimeMillis();
        String detail = "came back with " + (gains.isEmpty() ? "" : Amounts.describe(gains))
                + (extraSerials.isEmpty() ? "" : (gains.isEmpty() ? "" : " and ") + extraSerials.size() + " sealed item(s) they no longer had")
                + " more than they had when they left";
        if (excusedRemotely || commands.namedSince(p.getUniqueId(), since, now)) {
            plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, 60,
                    detail + "; an admin or console item command named them while offline (not removed)", false);
            return;
        }
        StringBuilder text = new StringBuilder(prefix).append(detail);
        int confidence = extraSerials.isEmpty() ? 90 : 100;
        if (remove) {
            int left = 0;
            if (removeStacks) for (Map.Entry<String, Integer> g : gains.entrySet()) {
                Material m = Material.matchMaterial(g.getKey());
                if (m == null) continue;
                int wanted = g.getValue();
                int taken = takeFrom(p.getInventory(), m, wanted);
                taken += takeFrom(p.getEnderChest(), m, wanted - taken);
                left += wanted - taken;
                int rest = taken;
                while (rest > 0) {
                    int n = Math.min(rest, m.getMaxStackSize());
                    String id = plugin.ledger() == null ? null : plugin.ledger().backup(new ItemStack(m, n));
                    if (id != null) text.append(" [restore id ").append(id).append(']');
                    rest -= n;
                }
            }
            for (String serial : extraSerials) {
                ItemStack gone = removeSealed(p, serial);
                if (gone == null) { left++; continue; }
                String id = plugin.ledger().backup(gone);
                if (id != null) text.append(" [restore id ").append(id).append(']');
            }
            if (!removeStacks && !gains.isEmpty()) { text.append(" (stackables flagged only)"); confidence = extraSerials.isEmpty() ? 60 : 100; }
            else text.append(left == 0 ? " (removed)" : " (" + left + " not removed: inside bundles or shulker boxes)");
        }
        plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, confidence, text.toString(), false);
    }

    private ItemStack removeSealed(Player p, String serial) {
        Holders h = plugin.ledger().holders();
        for (String holder : new String[]{Holders.playerId(p.getUniqueId()), Holders.enderId(p.getUniqueId())}) {
            Scan scan = h.resolve(holder, false);
            if (scan == null) continue;
            for (Sighting s : scan.sightings()) {
                if (!s.serial().equals(serial)) continue;
                ItemStack gone = s.nested() ? h.removeNested(holder, serial, s.slot()) : h.remove(holder, serial, s.slot());
                if (gone != null) return gone;
            }
        }
        return null;
    }

    private static int takeFrom(Inventory inv, Material m, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getContents();
        for (int i = 0; i < contents.length && left > 0; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != m) continue;
            int n = Math.min(left, item.getAmount());
            left -= n;
            if (n == item.getAmount()) inv.setItem(i, null); else { item.setAmount(item.getAmount() - n); inv.setItem(i, item); }
        }
        return amount - left;
    }

    // ------------------------------------------------------------------ persistence

    private void save() {
        if (!dirty) return;
        dirty = false;
        List<String> lines = new ArrayList<>(snapshots.size() + 1);
        lines.add("#sentinelad-players v1");
        long cutoff = System.currentTimeMillis() - Math.max(1, plugin.getConfig().getLong("ledger.retention-days", 60)) * 86_400_000L;
        snapshots.values().removeIf(s -> s.at() < cutoff);
        for (Map.Entry<UUID, Snapshot> e : snapshots.entrySet()) {
            lines.add(e.getKey() + "\t" + e.getValue().at() + "\t" + Amounts.format(e.getValue().amounts()));
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling("players.dat.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save players.dat: " + ex.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length != 3) continue;
                try {
                    snapshots.put(UUID.fromString(p[0]), new Snapshot(Amounts.parse(p[2]), Long.parseLong(p[1])));
                } catch (RuntimeException ignored) { /* damaged line */ }
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read players.dat: " + ex.getMessage());
        }
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
