package uz.sentinel.ad.ledger;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;

/**
 * Crash dupes. When the server crashes, every chunk goes back to its last save and every player to theirs, and the
 * two are saved at different times: a player who took diamonds out of a chest and logged out (saved) before a crash
 * (the chest's chunk not saved yet) keeps the diamonds and finds them in the chest again. Crash exploits exist for
 * exactly that.
 *
 * <p>The counts the {@link ChunkAudit} keeps in memory are lost in a crash, so every take of watched stackables out of
 * a container or off the ground is appended to {@code journal.log} at once (one short line, flushed to the operating
 * system, so it survives the JVM dying). After an unclean shutdown the journal is replayed: a container that is back
 * to more than it held after the last journaled change, or a ground item whose entity id was picked up and is there
 * again, was rolled back. If the player who took the items still has them, the surplus is removed (from the chunk
 * when the player is already online, otherwise from the player when they join) and the player is flagged.</p>
 *
 * <p>A clean shutdown deletes the journal; lines older than two hours are dropped (the server has autosaved since).
 * Containers that hoppers or droppers fed in the meantime are skipped, since those moves are not journaled.</p>
 */
public final class CrashJournal implements Listener {
    private static final long KEEP_MS = 2L * 60 * 60 * 1000;

    /** A take by one player of one material out of one place. */
    private static final class Take {
        final UUID player; final String material; final int amount; final int playerBefore;
        Take(UUID player, String material, int amount, int playerBefore) {
            this.player = player; this.material = material; this.amount = amount; this.playerBefore = playerBefore;
        }
    }

    private static final class Place {
        String chunk;
        final Map<String, Integer> after = new TreeMap<>(); // latest journaled count per material (containers)
        final List<Take> takes = new ArrayList<>();
        boolean automated;
    }

    private final SentinelADPlugin plugin;
    private final StackSessions stacks;
    private final Path file;
    private BufferedWriter out;
    private boolean enabled, remove;
    /** Replayed after a crash: container block id -> journal state. */
    private final Map<String, Place> containers = new HashMap<>();
    /** Replayed after a crash: picked-up item entity id -> journal state. */
    private final Map<UUID, Place> items = new HashMap<>();
    /** Containers touched by a journaled take in this run (hopper activity marks them). */
    private final Set<String> journaled = new HashSet<>();
    /** Surplus to take back from a player when they join (their chest was already fixed... or not loaded). */
    private final Map<UUID, Map<String, Integer>> owed = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> quits = new HashMap<>();
    private long found;

    public CrashJournal(SentinelADPlugin plugin, StackSessions stacks) {
        this.plugin = plugin;
        this.stacks = stacks;
        this.file = plugin.getDataFolder().toPath().resolve("journal.log");
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("rollback-guard.enabled", true) && plugin.getConfig().getBoolean("stackables.enabled", true);
        remove = plugin.getConfig().getString("rollback-guard.on-gain", "remove").equalsIgnoreCase("remove");
    }

    public long found() { return found; }

    /** A world was restored from a backup: replayed crash state belongs to a world that no longer exists. */
    public void clear() { containers.clear(); items.clear(); owed.clear(); quits.clear(); }

    /** Quit snapshots written before the crash (the server saved those players at quit, so they hold). */
    public Map<UUID, Map<String, Integer>> quitSnapshots() { return quits; }

    public void start(boolean cleanShutdown) {
        applyConfig();
        if (!cleanShutdown && Files.isRegularFile(file)) replay();
        try {
            Files.deleteIfExists(file);
            Files.createDirectories(file.toAbsolutePath().getParent());
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            // Replayed state not settled yet is written again, so a second crash before it is settled keeps it.
            for (Map.Entry<String, Place> e : containers.entrySet()) rewrite("C", e.getKey(), e.getValue());
            for (Map.Entry<UUID, Place> e : items.entrySet()) rewrite("I", e.getKey().toString(), e.getValue());
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not open journal.log: " + ex.getMessage());
        }
        Bukkit.getScheduler().runTaskTimer(plugin, this::compact, 20L * 60 * 30, 20L * 60 * 30);
        if (!containers.isEmpty() || !items.isEmpty()) {
            plugin.getLogger().warning("The server did not shut down cleanly: checking " + containers.size() + " containers and "
                    + items.size() + " ground items from the crash journal for rolled-back items.");
            for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) { checkChunk(c); checkEntities(c.getEntities()); }
        }
    }

    /** Clean shutdown: everything is saved, nothing to replay. */
    public void stop() {
        try {
            if (out != null) out.close();
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not delete journal.log: " + ex.getMessage());
        }
        out = null;
    }

    // ------------------------------------------------------------------ writing

    private void write(String line) {
        if (out == null) return;
        try {
            out.write(System.currentTimeMillis() + "\t" + line);
            out.newLine();
            out.flush(); // to the operating system: survives the JVM crashing
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not write journal.log: " + ex.getMessage());
        }
    }

    private void rewrite(String kind, String id, Place p) {
        StringBuilder sb = new StringBuilder("R\t").append(kind).append('\t').append(id).append('\t').append(p.chunk).append('\t')
                .append(Amounts.format(p.after)).append('\t').append(p.automated);
        for (Take t : p.takes) sb.append('\t').append(t.player).append(',').append(t.material).append(',').append(t.amount).append(',').append(t.playerBefore);
        write(sb.toString());
    }

    static String blockKey(Location l) {
        return l.getWorld().getUID() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ();
    }

    /** A player took {@code amount} of a material out of a container; {@code containerAfter}: what the container holds now. */
    public void tookFromContainer(Location where, UUID player, String material, int amount, int containerAfter, int playerBefore) {
        if (!enabled) return;
        String id = blockKey(where);
        journaled.add(id);
        write("T\t" + id + "\t" + ChunkAudit.key(where.getWorld(), where.getBlockX() >> 4, where.getBlockZ() >> 4) + "\t" + player
                + "\t" + material + "\t" + amount + "\t" + containerAfter + "\t" + playerBefore);
    }

    /** A container's count changed for another reason the journal knows (a player put items in). */
    public void containerNow(Location where, String material, int count) {
        if (!enabled) return;
        String id = blockKey(where);
        if (!journaled.contains(id)) return;
        write("P\t" + id + "\t" + material + "\t" + count);
    }

    public void quit(UUID player, Map<String, Integer> amounts) {
        if (enabled) write("Q\t" + player + "\t" + Amounts.format(amounts));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void joined(PlayerJoinEvent e) {
        if (enabled) write("J\t" + e.getPlayer().getUniqueId());
    }

    /** Hoppers and droppers change journaled containers without the journal seeing the amounts. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void move(InventoryMoveItemEvent e) {
        if (!enabled || journaled.isEmpty() || !stacks.watching(e.getItem().getType())) return;
        for (Inventory inv : new Inventory[]{e.getSource(), e.getDestination()}) {
            Location l;
            try { l = inv.getLocation(); } catch (RuntimeException ex) { l = null; }
            if (l == null) continue;
            String id = blockKey(l);
            if (journaled.remove(id)) write("A\t" + id);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p)) return;
        ItemStack s = e.getItem().getItemStack();
        if (!stacks.watching(s.getType())) return;
        int taken = Math.max(0, s.getAmount() - e.getRemaining());
        if (taken <= 0) return;
        Location l = e.getItem().getLocation();
        write("G\t" + e.getItem().getUniqueId() + "\t" + ChunkAudit.key(l.getWorld(), l.getBlockX() >> 4, l.getBlockZ() >> 4) + "\t"
                + p.getUniqueId() + "\t" + s.getType().name() + "\t" + taken + "\t" + count(p).getOrDefault(s.getType().name(), 0));
    }

    // ------------------------------------------------------------------ replay after a crash

    private void replay() {
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length < 2) continue;
                long at;
                try { at = Long.parseLong(f[0]); } catch (NumberFormatException e) { continue; }
                try {
                    switch (f[1]) {
                        case "T" -> {
                            if (at < cutoff) continue;
                            Place p = containers.computeIfAbsent(f[2], k -> new Place());
                            p.chunk = f[3];
                            p.after.put(f[5], Integer.parseInt(f[7]));
                            p.takes.add(new Take(UUID.fromString(f[4]), f[5], Integer.parseInt(f[6]), Integer.parseInt(f[8])));
                        }
                        case "P" -> { Place p = containers.get(f[2]); if (p != null) p.after.put(f[3], Integer.parseInt(f[4])); }
                        case "A" -> { Place p = containers.get(f[2]); if (p != null) p.automated = true; }
                        case "G" -> {
                            if (at < cutoff) continue;
                            Place p = items.computeIfAbsent(UUID.fromString(f[2]), k -> new Place());
                            p.chunk = f[3];
                            p.takes.add(new Take(UUID.fromString(f[4]), f[5], Integer.parseInt(f[6]), Integer.parseInt(f[7])));
                        }
                        case "Q" -> quits.put(UUID.fromString(f[2]), Amounts.parse(f.length > 3 ? f[3] : "-"));
                        case "J" -> quits.remove(UUID.fromString(f[2])); // back online: the quit snapshot no longer holds
                        case "R" -> { // state carried over from an earlier crash
                            Place p = new Place();
                            p.chunk = f[4];
                            p.after.putAll(Amounts.parse(f[5]));
                            p.automated = Boolean.parseBoolean(f[6]);
                            for (int i = 7; i < f.length; i++) {
                                String[] t = f[i].split(",");
                                p.takes.add(new Take(UUID.fromString(t[0]), t[1], Integer.parseInt(t[2]), Integer.parseInt(t[3])));
                            }
                            if (f[2].equals("C")) containers.put(f[3], p); else items.put(UUID.fromString(f[3]), p);
                        }
                        default -> { }
                    }
                } catch (RuntimeException ignored) { /* a line cut off by the crash */ }
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read journal.log: " + ex.getMessage());
        }
        containers.values().removeIf(p -> p.automated || p.takes.isEmpty());
    }

    private void compact() {
        // Two hours after a take the server has autosaved both the chunk and the player: drop the old lines.
        if (out == null) return;
        try {
            out.close();
            long cutoff = System.currentTimeMillis() - KEEP_MS;
            List<String> keep = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                try { if (tab > 0 && Long.parseLong(line.substring(0, tab)) >= cutoff) keep.add(line); }
                catch (NumberFormatException ignored) { }
            }
            Files.write(file, keep, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not compact journal.log: " + ex.getMessage());
        } finally {
            try { out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (IOException ex) { out = null; }
        }
    }

    private Map<String, Integer> count(Player p) {
        Map<String, Integer> m = new TreeMap<>();
        stacks.countInto(m, p.getInventory().getContents());
        stacks.countInto(m, new ItemStack[]{p.getItemOnCursor()});
        stacks.countInto(m, p.getEnderChest().getContents());
        return m;
    }

    private static Inventory fullInventory(Block b) {
        BlockState state = b.getState(false);
        if (state instanceof Chest c) return c.getInventory(); // both halves of a double chest
        return Holders.containerInventory(state);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void chunkLoad(ChunkLoadEvent e) {
        if (!containers.isEmpty()) checkChunk(e.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR) public void entitiesLoad(EntitiesLoadEvent e) {
        if (!items.isEmpty()) checkEntities(e.getEntities().toArray(new Entity[0]));
    }

    private void checkChunk(Chunk c) {
        if (containers.isEmpty()) return;
        String ck = ChunkAudit.key(c);
        for (var it = containers.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            Place p = e.getValue();
            if (!ck.equals(p.chunk)) continue;
            it.remove();
            String[] k = e.getKey().split(":");
            World w = Bukkit.getWorld(UUID.fromString(k[0]));
            if (w == null) continue;
            Block b = w.getBlockAt(Integer.parseInt(k[1]), Integer.parseInt(k[2]), Integer.parseInt(k[3]));
            Inventory inv = fullInventory(b);
            if (inv == null) continue;
            Map<String, Integer> now = new TreeMap<>();
            stacks.countInto(now, inv.getContents());
            for (Map.Entry<String, Integer> g : Amounts.gains(p.after, now, 1).entrySet()) {
                settle(g.getKey(), g.getValue(), p.takes, inv, null, b.getLocation());
            }
        }
    }

    private void checkEntities(Entity[] entities) {
        for (Entity en : entities) {
            if (!(en instanceof Item item)) continue;
            Place p = items.remove(item.getUniqueId());
            if (p == null) continue;
            // The very entity a player picked up is back: its chunk was rolled back.
            for (Take t : p.takes) settle(t.material, Math.min(t.amount, item.getItemStack().getAmount()), List.of(t), null, item, item.getLocation());
        }
    }

    /**
     * {@code gain} items of a material are back in a container or on the ground. For each player who took them: if
     * they still have them, take the surplus back (from the chunk when the player is online, else when they join).
     */
    private void settle(String material, int gain, List<Take> takes, Inventory container, Item ground, Location where) {
        Material m = Material.matchMaterial(material);
        if (m == null) return;
        for (Take t : takes) {
            if (gain <= 0) break;
            if (!t.material.equals(material)) continue;
            int n = Math.min(gain, t.amount);
            gain -= n;
            Player online = Bukkit.getPlayer(t.player);
            if (online == null) { // check when they come back
                owed.computeIfAbsent(t.player, k -> new TreeMap<>()).merge(material, n, Integer::sum);
                owedBefore.computeIfAbsent(t.player, k -> new TreeMap<>()).merge(material, t.playerBefore, Math::min);
                owedWhere.put(t.player, describe(where));
                continue;
            }
            int kept = Math.min(n, count(online).getOrDefault(material, 0) - t.playerBefore);
            if (kept <= 0) continue; // the player's data went back too: no dupe
            int removed = 0;
            if (remove) removed = container != null ? takeFrom(container, m, kept) : takeFromGround(ground, kept);
            report(t.player, "+" + kept + " " + material.toLowerCase(java.util.Locale.ROOT) + " came back at " + describe(where)
                    + " after a crash while this player kept the ones they had taken" + outcome(removed, kept, m));
        }
    }

    private final Map<UUID, Map<String, Integer>> owedBefore = new HashMap<>();
    private final Map<UUID, String> owedWhere = new HashMap<>();

    /** After the other plugins' join handling of {@link OfflineAudit} (LOWEST) but still before rewards. */
    @EventHandler(priority = EventPriority.LOW) public void join(PlayerJoinEvent e) {
        Map<String, Integer> due = owed.remove(e.getPlayer().getUniqueId());
        if (due == null) return;
        Map<String, Integer> before = owedBefore.remove(e.getPlayer().getUniqueId());
        String where = owedWhere.remove(e.getPlayer().getUniqueId());
        Player p = e.getPlayer();
        Map<String, Integer> now = count(p);
        for (Map.Entry<String, Integer> d : due.entrySet()) {
            Material m = Material.matchMaterial(d.getKey());
            if (m == null) continue;
            int kept = Math.min(d.getValue(), now.getOrDefault(d.getKey(), 0) - (before == null ? 0 : before.getOrDefault(d.getKey(), 0)));
            if (kept <= 0) continue;
            int removed = 0;
            if (remove) {
                removed = takeFrom(p.getInventory(), m, kept);
                removed += takeFrom(p.getEnderChest(), m, kept - removed);
            }
            report(p.getUniqueId(), "+" + kept + " " + d.getKey().toLowerCase(java.util.Locale.ROOT) + " they took came back at " + where
                    + " after a crash while they kept them" + outcome(removed, kept, m));
        }
    }

    private String outcome(int removed, int kept, Material m) {
        if (!remove) return "";
        StringBuilder sb = new StringBuilder(removed >= kept ? " (removed" : " (" + (kept - removed) + " not removed");
        int rest = removed;
        while (rest > 0 && plugin.ledger() != null) {
            int n = Math.min(rest, m.getMaxStackSize());
            String id = plugin.ledger().backup(new ItemStack(m, n));
            if (id != null) sb.append(", restore id ").append(id);
            rest -= n;
        }
        return sb.append(')').toString();
    }

    private void report(UUID player, String detail) {
        found++;
        OfflinePlayer op = Bukkit.getOfflinePlayer(player);
        plugin.reporter().report(player, op.getName() == null ? player.toString() : op.getName(), DupeType.STACK_GAIN, 90,
                "crash dupe: " + detail, false);
    }

    private static String describe(Location l) {
        return l.getWorld().getName() + " " + l.getBlockX() + "," + l.getBlockY() + "," + l.getBlockZ();
    }

    private static int takeFromGround(Item item, int amount) {
        ItemStack s = item.getItemStack();
        int n = Math.min(amount, s.getAmount());
        if (n == s.getAmount()) item.remove(); else { s.setAmount(s.getAmount() - n); item.setItemStack(s); }
        return n;
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
}
