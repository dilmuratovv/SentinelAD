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
    private String syncPlugin;

    public OfflineAudit(SentinelADPlugin plugin, StackSessions stacks, CommandActivity commands) {
        this.plugin = plugin;
        this.stacks = stacks;
        this.commands = commands;
        this.file = plugin.getDataFolder().toPath().resolve("players.dat");
    }

    public void applyConfig() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("offline-check.enabled", true) && c.getBoolean("stackables.enabled", true) && syncPlugin == null;
        remove = c.getString("offline-check.on-gain", "remove").equalsIgnoreCase("remove");
        minGain = Math.max(1, c.getInt("offline-check.min-gain", 1));
    }

    public void start(boolean cleanShutdown) {
        for (String name : SYNC_PLUGINS) {
            if (Bukkit.getPluginManager().getPlugin(name) != null) { syncPlugin = name; break; }
        }
        applyConfig();
        if (syncPlugin != null) {
            plugin.getLogger().info("Offline inventory check is off: " + syncPlugin + " syncs player data between servers.");
            return;
        }
        // After a crash a player who was online may still have an old quit snapshot while their data comes from a
        // later autosave: compare nothing from before the crash.
        if (cleanShutdown) load();
        else if (Files.exists(file)) plugin.getLogger().info("Offline inventory check: last shutdown was not clean, saved snapshots are not used.");
        long every = Math.max(10, plugin.getConfig().getLong("ledger.save-interval-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, every, every);
        // A reload with players online: they have no quit snapshot to compare, and none is needed.
        for (Player p : Bukkit.getOnlinePlayers()) snapshots.remove(p.getUniqueId());
    }

    public void stop() {
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
        }, 20L);
    }

    /** LOWEST: before other plugins hand out join rewards. */
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        Snapshot before = snapshots.remove(p.getUniqueId());
        if (before == null) return;
        dirty = true;
        if (exempt(p)) return;
        Map<String, Integer> gains = Amounts.gains(before.amounts(), count(p), minGain);
        if (gains.isEmpty()) return;
        long now = System.currentTimeMillis();
        String detail = "came back with " + Amounts.describe(gains) + " more than they had when they left";
        if (commands.namedSince(p.getUniqueId(), before.at(), now)) {
            plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, 60,
                    detail + "; an admin or console item command named them while offline (not removed)", false);
            return;
        }
        StringBuilder text = new StringBuilder("disconnect dupe: ").append(detail);
        if (remove) {
            int left = 0;
            for (Map.Entry<String, Integer> g : gains.entrySet()) {
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
            text.append(left == 0 ? " (removed)" : " (" + left + " not removed: inside bundles or shulker boxes)");
        }
        plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, 90, text.toString(), false);
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
