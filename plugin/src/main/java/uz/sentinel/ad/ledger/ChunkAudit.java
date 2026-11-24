package uz.sentinel.ad.ledger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;

/**
 * Chunk rollback guard. A chunk that is not saved after a player took items out of it (a crash, or an exploit that
 * stops the chunk from saving) comes back with those items while the player keeps them: the classic chunk dupe.
 *
 * <p>When a chunk unloads (and when the server stops) the watched stackables in its containers, storage entities,
 * item frames and ground items are counted. When it loads again it is counted once more: nothing can change in an
 * unloaded chunk, so more than before means the chunk was rolled back. The surplus is taken out of the chunk (backed
 * up, {@code /sad restore}) and the players who took watched items out of that chunk are flagged.</p>
 *
 * <p>Unstackable items need none of this: their seal shows up in two places and the ledger removes the copy.</p>
 */
public final class ChunkAudit implements Listener {
    private static final long TAKER_MS = 24L * 60 * 60 * 1000;

    private static final class Record {
        Map<String, Integer> blocks, entities;     // null = that part was not counted at unload
        final Map<UUID, Long> takers = new HashMap<>();
    }

    private final SentinelADPlugin plugin;
    private final StackSessions stacks;
    private final CommandActivity commands;
    private final Path file;
    /** Chunk key -> counts recorded at unload. Removed once the chunk is loaded and checked. */
    private final Map<String, Record> unloaded = new HashMap<>();
    /** Chunk key -> players who took watched items out of the loaded chunk (moved into the record on unload). */
    private final Map<String, Map<UUID, Long>> takers = new HashMap<>();
    private boolean enabled, dirty, remove, started, forgotten;
    private int minGain;
    private long found;

    public ChunkAudit(SentinelADPlugin plugin, StackSessions stacks, CommandActivity commands) {
        this.plugin = plugin;
        this.stacks = stacks;
        this.commands = commands;
        this.file = plugin.getDataFolder().toPath().resolve("chunks.dat");
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("rollback-guard.enabled", true)
                && plugin.getConfig().getBoolean("stackables.enabled", true);
        remove = plugin.getConfig().getString("rollback-guard.on-gain", "remove").equalsIgnoreCase("remove");
        minGain = Math.max(1, plugin.getConfig().getInt("rollback-guard.min-gain", 1));
    }

    static String key(World w, int x, int z) { return w.getUID() + ":" + x + ":" + z; }
    static String key(Chunk c) { return key(c.getWorld(), c.getX(), c.getZ()); }

    /** Loads the counts saved by the last run; after a crash they may be older than the chunks, so they are dropped. */
    public void start(boolean cleanShutdown) {
        applyConfig();
        started = true;
        if (forgotten) plugin.getLogger().info("Chunk rollback guard: a world was rolled back, saved chunk counts are not used.");
        else if (cleanShutdown) load();
        else if (Files.exists(file)) plugin.getLogger().info("Chunk rollback guard: last shutdown was not clean, saved chunk counts are not used.");
        long every = Math.max(10, plugin.getConfig().getLong("ledger.save-interval-seconds", 60)) * 20L;
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, every, every);
        // Worlds load before plugins: check the chunks that are already there.
        for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) scheduleCheck(c, 0);
    }

    /** Records every loaded chunk (the server is stopping: they unload after plugins are disabled). */
    public void stop() {
        if (enabled) for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) {
            Record r = record(c);
            r.blocks = countBlocks(c);
            if (c.isEntitiesLoaded()) r.entities = countEntities(List.of(c.getEntities()));
            forgetIfEmpty(key(c), r);
        }
        dirty = true;
        save();
    }

    /** A world was restored from a backup: the counts belong to a state that no longer exists. */
    public void clear() { unloaded.clear(); takers.clear(); dirty = true; if (!started) forgotten = true; }

    public long found() { return found; }

    private Record record(Chunk c) {
        Record r = unloaded.computeIfAbsent(key(c), k -> new Record());
        Map<UUID, Long> t = takers.remove(key(c));
        if (t != null) r.takers.putAll(t);
        return r;
    }

    // ------------------------------------------------------------------ counting

    private Map<String, Integer> countBlocks(Chunk c) {
        Map<String, Integer> totals = new TreeMap<>();
        for (BlockState state : c.getTileEntities(false)) {
            Inventory inv = Holders.containerInventory(state);
            if (inv != null) stacks.countInto(totals, inv.getContents());
        }
        return totals;
    }

    private Map<String, Integer> countEntities(List<Entity> entities) {
        Map<String, Integer> totals = new TreeMap<>();
        for (Entity e : entities) {
            if (e instanceof Item item) stacks.countInto(totals, new ItemStack[]{item.getItemStack()});
            else if (e instanceof ItemFrame frame) stacks.countInto(totals, new ItemStack[]{frame.getItem()});
            else if (e instanceof InventoryHolder h && !(e instanceof Player)) stacks.countInto(totals, h.getInventory().getContents());
        }
        return totals;
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR) public void chunkUnload(ChunkUnloadEvent e) {
        if (!enabled) return;
        Record r = record(e.getChunk());
        r.blocks = countBlocks(e.getChunk());
        forgetIfEmpty(key(e.getChunk()), r);
        dirty = true;
    }

    @EventHandler(priority = EventPriority.MONITOR) public void entitiesUnload(EntitiesUnloadEvent e) {
        if (!enabled) return;
        Record r = record(e.getChunk());
        r.entities = countEntities(e.getEntities());
        forgetIfEmpty(key(e.getChunk()), r);
        dirty = true;
    }

    /** Most chunks hold nothing watched and nobody took anything from them: no need to remember them. */
    private void forgetIfEmpty(String k, Record r) {
        boolean blocks = r.blocks == null || r.blocks.isEmpty(), entities = r.entities == null || r.entities.isEmpty();
        if (blocks && entities && r.takers.isEmpty()) unloaded.remove(k);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void chunkLoad(ChunkLoadEvent e) { if (enabled) scheduleCheck(e.getChunk(), 1); }

    @EventHandler(priority = EventPriority.MONITOR) public void entitiesLoad(EntitiesLoadEvent e) { if (enabled) scheduleCheck(e.getChunk(), 1); }

    /** Someone takes watched items off the ground of this chunk. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) {
        if (!enabled || !(e.getEntity() instanceof Player p) || !stacks.watching(e.getItem().getItemStack().getType())) return;
        took(e.getItem().getLocation().getChunk(), p.getUniqueId());
    }

    /** Called by the stackable sessions when a player's container lost watched items to the player. */
    public void took(Chunk chunk, UUID player) {
        if (!enabled) return;
        takers.computeIfAbsent(key(chunk), k -> new HashMap<>()).put(player, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ check

    private void scheduleCheck(Chunk c, int attempt) {
        if (!unloaded.containsKey(key(c))) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> check(c, attempt), attempt == 0 ? 1 : 2);
    }

    private void check(Chunk c, int attempt) {
        String k = key(c);
        Record r = unloaded.get(k);
        if (r == null || !c.isLoaded()) return;
        if (!c.isEntitiesLoaded() && attempt < 50) { // entities load a little after the blocks
            Bukkit.getScheduler().runTaskLater(plugin, () -> check(c, attempt + 1), 2);
            return;
        }
        unloaded.remove(k);
        dirty = true;
        boolean withEntities = r.entities != null && c.isEntitiesLoaded();
        Map<String, Integer> before = new TreeMap<>(), now = new TreeMap<>();
        if (r.blocks != null) { before = Amounts.sum(before, r.blocks); now = Amounts.sum(now, countBlocks(c)); }
        if (withEntities) { before = Amounts.sum(before, r.entities); now = Amounts.sum(now, countEntities(List.of(c.getEntities()))); }
        Map<String, Integer> gains = Amounts.gains(before, now, minGain);
        // Takers stay attached to the loaded chunk so a later rollback still finds them.
        long cutoff = System.currentTimeMillis() - TAKER_MS;
        r.takers.values().removeIf(t -> t < cutoff);
        if (!r.takers.isEmpty()) takers.computeIfAbsent(k, x -> new HashMap<>()).putAll(r.takers);
        if (gains.isEmpty()) return;
        found++;
        String where = c.getWorld().getName() + " chunk " + c.getX() + "," + c.getZ() + " (blocks " + (c.getX() << 4) + ","
                + (c.getZ() << 4) + ")";
        if (commands.explains(null, System.currentTimeMillis())) {
            plugin.reporter().reportUnattributed(DupeType.STACK_GAIN, 60, "chunk came back with more items right after an item command, not removed: "
                    + Amounts.describe(gains) + " in " + where);
            return;
        }
        StringBuilder detail = new StringBuilder("chunk rollback: ").append(where).append(" came back with ")
                .append(Amounts.describe(gains)).append(" more than when it unloaded");
        if (remove) {
            int left = 0;
            List<String> ids = new ArrayList<>();
            for (Map.Entry<String, Integer> g : gains.entrySet()) {
                Material m = Material.matchMaterial(g.getKey());
                if (m == null) continue;
                int[] untaken = {g.getValue()};
                String id = takeFromChunk(c, m, untaken, withEntities);
                if (id != null) ids.add(id);
                left += untaken[0];
            }
            detail.append(left == 0 ? " (removed" : " (partly removed, " + left + " left inside bundles or shulker boxes");
            for (String id : ids) detail.append(", restore id ").append(id);
            detail.append(')');
        }
        if (r.takers.isEmpty()) {
            plugin.reporter().reportUnattributed(DupeType.STACK_GAIN, 90, detail.toString());
            return;
        }
        for (UUID t : r.takers.keySet()) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(t);
            plugin.reporter().report(t, op.getName() == null ? t.toString() : op.getName(), DupeType.STACK_GAIN, 90,
                    detail + "; this player took watched items out of that chunk before it unloaded", false);
        }
    }

    /** Takes {@code left[0]} items of {@code m} out of the chunk's containers, then its ground items. Returns a restore id. */
    private String takeFromChunk(Chunk c, Material m, int[] left, boolean entities) {
        int wanted = left[0];
        for (BlockState state : c.getTileEntities(false)) {
            if (left[0] <= 0) break;
            Inventory inv = Holders.containerInventory(state);
            if (inv != null) left[0] -= takeFrom(inv, m, left[0]);
        }
        if (entities) for (Entity e : c.getEntities()) {
            if (left[0] <= 0) break;
            if (e instanceof Item item && item.getItemStack().getType() == m) {
                ItemStack s = item.getItemStack();
                int n = Math.min(left[0], s.getAmount());
                left[0] -= n;
                if (n == s.getAmount()) item.remove(); else { s.setAmount(s.getAmount() - n); item.setItemStack(s); }
            } else if (e instanceof InventoryHolder h && !(e instanceof Player)) {
                left[0] -= takeFrom(h.getInventory(), m, left[0]);
            }
        }
        int taken = wanted - left[0];
        if (taken <= 0 || plugin.ledger() == null) return null;
        List<String> ids = new ArrayList<>();
        int rest = taken;
        while (rest > 0) { // back up in stacks the item format accepts
            int n = Math.min(rest, m.getMaxStackSize());
            String id = plugin.ledger().backup(new ItemStack(m, n));
            if (id != null) ids.add(id);
            rest -= n;
        }
        return ids.isEmpty() ? null : String.join(" ", ids);
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
        long cutoff = System.currentTimeMillis() - TAKER_MS;
        takers.values().forEach(m -> m.values().removeIf(t -> t < cutoff));
        takers.values().removeIf(Map::isEmpty);
        if (!dirty) return;
        dirty = false;
        List<String> lines = new ArrayList<>(unloaded.size() + 1);
        lines.add("#sentinelad-chunks v1");
        for (Map.Entry<String, Record> e : unloaded.entrySet()) {
            Record r = e.getValue();
            lines.add(e.getKey() + "\t" + (r.blocks == null ? "?" : Amounts.format(r.blocks)) + "\t"
                    + (r.entities == null ? "?" : Amounts.format(r.entities)) + "\t" + Amounts.formatTimes(r.takers));
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling("chunks.dat.tmp");
            Files.write(tmp, lines, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save chunks.dat: " + ex.getMessage());
        }
    }

    private void load() {
        if (!Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length != 4) continue;
                Record r = new Record();
                r.blocks = p[1].equals("?") ? null : Amounts.parse(p[1]);
                r.entities = p[2].equals("?") ? null : Amounts.parse(p[2]);
                r.takers.putAll(Amounts.parseTimes(p[3]));
                unloaded.put(p[0], r);
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read chunks.dat: " + ex.getMessage());
        }
    }
}
