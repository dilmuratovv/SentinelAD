package uz.sentinel.ad.ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.loot.Lootable;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;
import uz.sentinel.ad.protection.Seal;
import uz.sentinel.ad.protection.TransferProtection;

/**
 * The global item ledger. Every unstackable item gets a unique seal; every place an item can be (player, ender chest,
 * container, ground, frame, stand, storage entity) is scanned after it changes, and the ledger remembers where each seal
 * was last seen. The same seal in two places that both verifiably hold it, still true after a short delay, is a duplicate.
 */
public final class LedgerService implements Listener {
    private static final long SUPPRESS_MS = 10 * 60 * 1000L;
    private static final long ROLLBACK_TOLERANCE_TICKS = 100;
    /** World UUID -> last known full time, kept for worlds that are unloaded now. */
    private final Map<String, Long> recordedTimes = new LinkedHashMap<>();

    private final SentinelADPlugin plugin;
    private final Seal seal;
    private final CommandActivity commands;
    private final ItemScanner scanner;
    private final Holders holders;
    private final LedgerStore store = new LedgerStore();
    private final Path file;
    private final Path removedDir;

    private final LinkedHashMap<String, Supplier<Scan>> queue = new LinkedHashMap<>();
    /** Busy holders (a player clicking) wait here a few ticks so one burst of clicks costs one scan. */
    private final LinkedHashMap<String, Long> delayed = new LinkedHashMap<>();
    private long tick;
    private final Set<String> pending = new HashSet<>();
    private final Map<String, Long> suppressed = new HashMap<>();
    private final Map<String, UUID> toucher = new HashMap<>();

    private volatile boolean enabled;
    private boolean dirty;
    private long scans, conflicts, removedCount, restamped;
    private int removedSeq;

    public LedgerService(SentinelADPlugin plugin, Seal seal, CommandActivity commands) {
        this.plugin = plugin;
        this.seal = seal;
        this.commands = commands;
        this.scanner = new ItemScanner(seal);
        this.holders = new Holders(scanner, seal);
        this.file = plugin.getDataFolder().toPath().resolve("ledger.dat");
        this.removedDir = plugin.getDataFolder().toPath().resolve("removed");
    }

    public Holders holders() { return holders; }

    public void start() {
        applyConfig();
        try {
            if (!store.load(file)) plugin.getLogger().warning("Ledger was not closed cleanly last time; item locations are rebuilt from scratch.");
            plugin.getLogger().info("Ledger loaded: " + store.size() + " sealed items.");
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not read ledger.dat: " + e.getMessage());
        }
        recordedTimes.putAll(store.savedWorldTimes());
        for (var world : Bukkit.getWorlds()) checkRollback(world);
        try { // mark the file as "running" so a crash is recognised on the next start
            LedgerStore.write(file, store.snapshot(), false, worldTimes());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not write ledger.dat: " + e.getMessage());
        }
        var scheduler = Bukkit.getScheduler();
        scheduler.runTaskTimer(plugin, this::drain, 1L, 1L);
        scheduler.runTaskTimer(plugin, this::rescanPlayers, 40L, 40L);
        long saveTicks = Math.max(10, plugin.getConfig().getLong("ledger.save-interval-seconds", 60)) * 20L;
        scheduler.runTaskTimer(plugin, this::saveAsync, saveTicks, saveTicks);
        scheduler.runTaskTimer(plugin, this::prune, 20L * 60 * 10, 20L * 60 * 60);
        for (Player p : Bukkit.getOnlinePlayers()) { enqueuePlayer(p); enqueueEnder(p); }
        for (var world : Bukkit.getWorlds()) for (Chunk c : world.getLoadedChunks()) enqueueChunk(c);
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("ledger.enabled", true);
        commands.configure(plugin.getConfig().getConfigurationSection("item-commands"));
        scanner.configure(plugin.getConfig());
        holders.stampContainers(plugin.getConfig().getBoolean("ledger.auto-seal-containers", true));
    }

    public boolean enabled() { return enabled; }

    public void stop() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) { process(holders.player(p, false)); process(holders.ender(p, false)); }
        try {
            LedgerStore.write(file, store.snapshot(), true, worldTimes());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save ledger.dat: " + e.getMessage());
        }
    }

    /** Current game time (monotonic, unaffected by /time set) of every loaded world plus the recorded time of worlds not loaded now. Main thread. */
    private Map<String, Long> worldTimes() {
        Map<String, Long> times = new LinkedHashMap<>(recordedTimes);
        for (var world : Bukkit.getWorlds()) times.put(world.getUID().toString(), world.getGameTime());
        return times;
    }

    /**
     * A world restored from a backup (or rolled back by a world-management plugin) has an older time than the ledger
     * recorded. Item locations then belong to a world state that no longer exists and would produce false duplicates,
     * so they are forgotten (serials stay known) instead of needing a manual /sad ledger reset.
     */
    private void checkRollback(org.bukkit.World world) {
        String id = world.getUID().toString();
        Long recorded = recordedTimes.get(id);
        if (LedgerStore.rolledBack(recorded, world.getGameTime(), ROLLBACK_TOLERANCE_TICKS)) {
            plugin.getLogger().warning("World " + world.getName() + " is older than the ledger (time " + world.getGameTime()
                    + " < " + recorded + "): it was restored or rolled back. Item locations are rebuilt from scratch.");
            store.forgetHolders();
            dirty = true;
        }
        recordedTimes.put(id, world.getGameTime());
    }

    @EventHandler public void worldLoad(org.bukkit.event.world.WorldLoadEvent e) { if (enabled) checkRollback(e.getWorld()); }

    private void saveAsync() {
        if (!dirty) return;
        dirty = false;
        List<String> snapshot = store.snapshot();
        Map<String, Long> times = worldTimes();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { LedgerStore.write(file, snapshot, false, times); }
            catch (IOException e) { plugin.getLogger().log(Level.WARNING, "Could not save ledger.dat: " + e.getMessage()); }
        });
    }

    private void prune() {
        long days = plugin.getConfig().getLong("ledger.retention-days", 60);
        if (days <= 0) return;
        int n = store.prune(System.currentTimeMillis() - days * 86_400_000L);
        if (n > 0) { dirty = true; plugin.getLogger().info("Ledger pruned " + n + " items not seen for " + days + " days."); }
        suppressed.values().removeIf(t -> System.currentTimeMillis() - t > SUPPRESS_MS);
        if (toucher.size() > 5000) toucher.clear();
        commands.prune(System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ queue

    private void enqueue(String id) {
        delayed.remove(id);
        queue.putIfAbsent(id, () -> holders.resolve(id, true));
    }

    /** Like {@link #enqueue} but coalesces a burst of events into one scan a few ticks later. */
    private void enqueueSoon(String id) {
        if (queue.containsKey(id) || delayed.containsKey(id)) return;
        delayed.put(id, tick + Math.max(1, plugin.getConfig().getLong("ledger.click-debounce-ticks", 10)));
    }

    private void enqueuePlayer(Player p) { enqueue(Holders.playerId(p.getUniqueId())); }
    private void enqueueEnder(Player p) { enqueue(Holders.enderId(p.getUniqueId())); }

    private void enqueuePlayerSoon(Player p) { enqueueSoon(Holders.playerId(p.getUniqueId())); }

    private void enqueueInventorySoon(Inventory inventory, Player who) {
        for (String id : holders.idsOf(inventory)) {
            if (id.startsWith("B:")) toucher.put(id, who.getUniqueId());
            enqueueSoon(id);
        }
    }

    private void enqueueInventory(Inventory inventory, Player who) {
        for (String id : holders.idsOf(inventory)) {
            if (who != null && id.startsWith("B:")) toucher.put(id, who.getUniqueId());
            enqueue(id);
        }
    }

    private void enqueueChunk(Chunk chunk) {
        for (BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Container && !(state instanceof Lootable l && l.hasLootTable())) enqueue(Holders.blockId(state.getBlock()));
        }
    }

    private static boolean interesting(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material t = item.getType();
        return item.getMaxStackSize() == 1 || Tag.SHULKER_BOXES.isTagged(t) || t.name().endsWith("BUNDLE");
    }

    private void drain() {
        tick++;
        if (!delayed.isEmpty()) {
            Iterator<Map.Entry<String, Long>> d = delayed.entrySet().iterator();
            while (d.hasNext()) {
                Map.Entry<String, Long> e = d.next();
                if (e.getValue() <= tick) { d.remove(); queue.putIfAbsent(e.getKey(), () -> holders.resolve(e.getKey(), true)); }
            }
        }
        if (!enabled || queue.isEmpty()) return;
        long budget = Math.max(1, plugin.getConfig().getLong("ledger.scan-budget-ms", 2)) * 1_000_000L;
        long start = System.nanoTime();
        int limit = 400;
        Iterator<Map.Entry<String, Supplier<Scan>>> it = queue.entrySet().iterator();
        while (it.hasNext() && limit-- > 0 && System.nanoTime() - start < budget) {
            Supplier<Scan> task = it.next().getValue();
            it.remove();
            try {
                process(task.get());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Ledger scan failed: " + e);
            }
        }
    }

    private void rescanPlayers() {
        if (!enabled) return;
        for (Player p : Bukkit.getOnlinePlayers()) enqueuePlayer(p);
    }

    public int queued() { return queue.size(); }

    // ------------------------------------------------------------------ core

    private void process(Scan scan) {
        if (scan == null || !enabled) return;
        scans++;
        long now = System.currentTimeMillis();
        List<LedgerStore.Conflict> found = store.update(scan.id(), scan.sightings(), now);
        dirty = true;
        for (LedgerStore.Conflict c : found) handle(c, scan);
    }

    private void handle(LedgerStore.Conflict c, Scan scan) {
        long now = System.currentTimeMillis();
        Long until = suppressed.get(c.serial());
        if (until != null && now - until < SUPPRESS_MS) return;
        String key = c.serial() + "|" + scan.id() + "|" + c.sighting().slot();
        if (pending.contains(key)) return;
        if (!c.previous().equals(scan.id())) {
            Scan old = holders.resolve(c.previous(), false);
            if (old == null) { // cannot check the other place right now
                flag(c.serial(), scan.id(), c.previous(), 60, "possible duplicate: " + describe(c.serial()) + " is in "
                        + holders.describe(scan.id()) + " but was last seen in " + holders.describe(c.previous()) + " (not loaded, unverified)");
                store.moveTo(c.serial(), scan.id(), now);
                return;
            }
            if (!old.contains(c.serial())) { // it simply moved
                store.moveTo(c.serial(), scan.id(), now);
                return;
            }
        }
        conflicts++;
        pending.add(key);
        long delay = Math.max(0, plugin.getConfig().getLong("ledger.confirm-delay-ticks", 40));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(key);
            confirm(c.serial(), scan.id(), c.previous());
        }, delay);
    }

    private void confirm(String serial, String newId, String oldId) {
        if (!enabled) return;
        Scan now = holders.resolve(newId, false);
        if (now == null) return;
        boolean same = oldId.equals(newId);
        if (same ? now.count(serial) < 2 : !now.contains(serial)) return;
        if (!same) {
            Scan old = holders.resolve(oldId, false);
            if (old == null) return;
            if (!old.contains(serial)) { store.moveTo(serial, newId, System.currentTimeMillis()); return; }
        }
        Sighting target = null;
        int seen = 0;
        for (Sighting s : now.sightings()) {
            if (!s.serial().equals(serial)) continue;
            if (same && seen++ == 0) continue; // keep the first copy, act on the second
            target = s;
            break;
        }
        if (target == null) return;
        act(serial, newId, oldId, target);
    }

    private void act(String serial, String newId, String oldId, Sighting target) {
        Player actor = actorFor(newId, oldId);
        boolean ownHolder = actor != null && newId.equals(Holders.playerId(actor.getUniqueId()));
        if (ownHolder && (actor.getGameMode() == GameMode.CREATIVE || actor.hasPermission(TransferProtection.BYPASS))) {
            if (target.nested()) { suppressed.put(serial, System.currentTimeMillis()); return; }
            if (holders.restamp(newId, serial, target.slot())) { restamped++; return; }
        }
        String what = describe(serial);
        String detail = what + " exists in two places: " + holders.describe(newId) + " and " + holders.describe(oldId);
        boolean remove = plugin.getConfig().getString("ledger.on-duplicate", "remove").equalsIgnoreCase("remove");
        if (remove && commands.explains(actor == null ? null : actor.getUniqueId(), System.currentTimeMillis())) {
            // Another plugin's item command (kit, clone, item editor...) may have made this copy: alert, never delete.
            suppressed.put(serial, System.currentTimeMillis());
            report(actor, 50, "possible duplicate right after an item command, not removed: " + detail);
            return;
        }
        boolean done = false;
        if (remove) {
            ItemStack gone = target.nested() ? holders.removeNested(newId, serial, target.slot())
                    : holders.remove(newId, serial, target.slot());
            if (gone != null) {
                removedCount++;
                done = true;
                String id = backup(gone);
                detail = "removed duplicate " + detail + (id == null ? "" : " [restore id " + id + "]");
            } else {
                detail = "could not remove duplicate " + detail;
            }
        } else {
            detail = "duplicate " + detail;
        }
        // After a removal the copy is gone, so a new copy is a new event; otherwise do not repeat the alert for a while.
        if (!done) suppressed.put(serial, System.currentTimeMillis());
        report(actor, 100, detail);
    }

    private void flag(String serial, String newId, String oldId, int confidence, String detail) {
        suppressed.put(serial, System.currentTimeMillis());
        report(actorFor(newId, oldId), confidence, detail);
    }

    private void report(Player actor, int confidence, String detail) {
        if (actor != null) {
            plugin.reporter().report(actor.getUniqueId(), actor.getName(), DupeType.LEDGER_MISMATCH, confidence, detail, false);
        } else {
            plugin.reporter().reportUnattributed(DupeType.LEDGER_MISMATCH, confidence, detail);
        }
    }

    private Player actorFor(String newId, String oldId) {
        if (newId.startsWith("P:")) return Bukkit.getPlayer(UUID.fromString(newId.substring(2)));
        UUID t = toucher.get(newId);
        if (t == null) t = toucher.get(oldId);
        if (t != null) return Bukkit.getPlayer(t);
        if (oldId.startsWith("P:")) return Bukkit.getPlayer(UUID.fromString(oldId.substring(2)));
        return null;
    }

    private String describe(String serial) {
        return "sealed item " + serial.substring(0, Math.min(8, serial.length()));
    }

    /** Stores an item the plugin took away so that {@code /sad restore <id>} can give it back. Returns the id or null. */
    public String backup(ItemStack item) {
        try {
            Files.createDirectories(removedDir);
            String id = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + (++removedSeq);
            Files.write(removedDir.resolve(id + ".bin"), item.serializeAsBytes());
            return id;
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Could not save the removed item: " + e.getMessage());
            return null;
        }
    }

    /** Returns a previously removed item, or null. */
    public ItemStack loadRemoved(String id) {
        if (!id.matches("[0-9]{8}-[0-9]{6}-[0-9]+")) return null;
        try {
            return ItemStack.deserializeBytes(Files.readAllBytes(removedDir.resolve(id + ".bin")));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ commands support

    public String locate(ItemStack item) {
        String serial = seal.read(item);
        if (serial == null) return "This item has no seal.";
        String holder = store.holderOf(serial);
        return "Seal " + serial + (store.known(serial)
                ? (holder == null ? ": in transit" : ": last seen in " + holders.describe(holder)) : ": not in the ledger yet");
    }

    public String stats() {
        return "sealed items tracked: " + store.size() + ", holders: " + store.holders() + ", queued scans: " + queue.size()
                + ", scans: " + scans + ", confirmed duplicates: " + conflicts + ", removed: " + removedCount
                + ", re-sealed clones: " + restamped;
    }

    public void reset() {
        store.clear();
        queue.clear();
        delayed.clear();
        pending.clear();
        suppressed.clear();
        dirty = true;
        for (Player p : Bukkit.getOnlinePlayers()) { enqueuePlayer(p); enqueueEnder(p); }
    }

    // ------------------------------------------------------------------ events

    @EventHandler public void join(PlayerJoinEvent e) { if (enabled) { enqueuePlayer(e.getPlayer()); enqueueEnder(e.getPlayer()); } }

    @EventHandler public void quit(PlayerQuitEvent e) {
        if (!enabled) return;
        process(holders.player(e.getPlayer(), true));
        process(holders.ender(e.getPlayer(), true));
    }

    @EventHandler(priority = EventPriority.MONITOR) public void open(InventoryOpenEvent e) {
        if (enabled && e.getPlayer() instanceof Player p) { enqueuePlayer(p); enqueueInventory(e.getInventory(), p); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void close(InventoryCloseEvent e) {
        if (enabled && e.getPlayer() instanceof Player p) {
            enqueuePlayer(p);
            enqueueEnder(p);
            enqueueInventory(e.getInventory(), p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void click(InventoryClickEvent e) {
        if (enabled && e.getWhoClicked() instanceof Player p) { enqueuePlayerSoon(p); enqueueInventorySoon(e.getInventory(), p); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void drag(InventoryDragEvent e) {
        if (enabled && e.getWhoClicked() instanceof Player p) { enqueuePlayerSoon(p); enqueueInventorySoon(e.getInventory(), p); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void pickup(EntityPickupItemEvent e) {
        if (enabled && e.getEntity() instanceof Player p) enqueuePlayerSoon(p);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void drop(PlayerDropItemEvent e) {
        if (enabled) { enqueuePlayer(e.getPlayer()); enqueue(Holders.entityId(e.getItemDrop().getUniqueId())); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void spawn(ItemSpawnEvent e) {
        if (enabled && interesting(e.getEntity().getItemStack())) enqueue(Holders.entityId(e.getEntity().getUniqueId()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void move(InventoryMoveItemEvent e) {
        if (!enabled || !interesting(e.getItem())) return;
        enqueueInventory(e.getSource(), null);
        enqueueInventory(e.getDestination(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void hopperPickup(InventoryPickupItemEvent e) {
        if (!enabled || !interesting(e.getItem().getItemStack())) return;
        enqueueInventory(e.getInventory(), null);
        enqueue(Holders.entityId(e.getItem().getUniqueId()));
    }

    @EventHandler public void chunkLoad(ChunkLoadEvent e) { if (enabled) enqueueChunk(e.getChunk()); }

    @EventHandler public void chunkUnload(ChunkUnloadEvent e) {
        if (!enabled) return;
        for (BlockState state : e.getChunk().getTileEntities(false)) {
            if (state instanceof Container && !(state instanceof Lootable l && l.hasLootTable())) process(holders.block(state.getBlock(), false));
        }
    }

    /** Armor stands, frames and storage entities are checked right after they appear and whenever a player changes them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void entitySpawn(EntitySpawnEvent e) {
        Entity en = e.getEntity();
        if (!enabled || !(en instanceof ArmorStand || en instanceof ItemFrame || (en instanceof InventoryHolder && !(en instanceof Player)))) return;
        Bukkit.getScheduler().runTask(plugin, () -> { if (en.isValid()) enqueue(Holders.entityId(en.getUniqueId())); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void standChange(PlayerArmorStandManipulateEvent e) {
        if (!enabled) return;
        toucher.put(Holders.entityId(e.getRightClicked().getUniqueId()), e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> { enqueuePlayer(e.getPlayer()); enqueue(Holders.entityId(e.getRightClicked().getUniqueId())); });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void frameChange(PlayerItemFrameChangeEvent e) {
        if (!enabled) return;
        toucher.put(Holders.entityId(e.getItemFrame().getUniqueId()), e.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> { enqueuePlayer(e.getPlayer()); enqueue(Holders.entityId(e.getItemFrame().getUniqueId())); });
    }

    @EventHandler public void entitiesLoad(EntitiesLoadEvent e) {
        if (!enabled) return;
        for (Entity en : e.getEntities()) {
            if (en instanceof Item || en instanceof ItemFrame || en instanceof ArmorStand
                    || (en instanceof InventoryHolder && !(en instanceof Player))) enqueue(Holders.entityId(en.getUniqueId()));
        }
    }
}
