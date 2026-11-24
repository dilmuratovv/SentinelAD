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
import org.bukkit.block.Block;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.entity.LivingEntity;
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
    /** Serial -> who threw it on the ground for another player to pick up (trades), so a buyer is not blamed. */
    private final Map<String, Handover> handovers = new HashMap<>();
    /** Serial -> when an unverifiable possible duplicate was last reported (alert cooldown, not a suppression). */
    private final Map<String, Long> unverifiedReported = new HashMap<>();

    private record Handover(UUID from, long at) {}
    /** Block position "world:x:y:z" -> seal of the shulker box placed there (also read back on chunk load). */
    private final Map<String, String> placedSeals = new HashMap<>();

    private static String pos(org.bukkit.World w, int x, int y, int z) { return w.getUID() + ":" + x + ":" + y + ":" + z; }
    private static final long HANDOVER_MS = 30 * 60 * 1000L;

    private volatile boolean enabled;
    private boolean lastClean = true;
    private final List<Runnable> onForget = new java.util.ArrayList<>();

    /** Whether ledger.dat was written by a clean shutdown (read in {@link #start()}). */
    public boolean lastShutdownClean() { return lastClean; }

    /** Runs when item locations are forgotten (world rollback, /sad ledger reset). */
    public void onForget(Runnable r) { onForget.add(r); }
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
            lastClean = store.load(file);
            if (!lastClean) plugin.getLogger().warning("Ledger was not closed cleanly last time; item locations are rebuilt from scratch.");
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
            onForget.forEach(Runnable::run);
            dirty = true;
        }
        recordedTimes.put(id, world.getGameTime());
    }

    @EventHandler public void worldLoad(org.bukkit.event.world.WorldLoadEvent e) { if (enabled) checkRollback(e.getWorld()); }

    private void saveAsync() {
        if (!dirty) return;
        dirty = false;
        // Nothing is copied on the main thread: the background task walks the (concurrent) ledger itself.
        Map<String, Long> times = worldTimes();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try { LedgerStore.write(file, store.lines(), false, times); }
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
        long nowMs = System.currentTimeMillis();
        handovers.values().removeIf(h -> nowMs - h.at() > HANDOVER_MS);
        unverifiedReported.values().removeIf(t -> nowMs - t > SUPPRESS_MS);
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
            if (Holders.storesItems(state)) enqueue(Holders.blockId(state.getBlock()));
            String own = seal.read(state);
            if (own != null) placedSeals.put(pos(state.getWorld(), state.getX(), state.getY(), state.getZ()), own);
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
            if (old == null) { // cannot check the other place right now (offline player, unloaded chunk)
                // Nothing can change in an offline inventory or an unloaded chunk, so the copy there is checked again
                // as soon as it loads: the ledger now points here, and the old place's next scan clashes with it.
                // No suppression, so that check can confirm and remove.
                Long last = unverifiedReported.get(c.serial());
                if (last == null || now - last > SUPPRESS_MS) {
                    unverifiedReported.put(c.serial(), now);
                    report(actorFor(scan.id(), c.previous()), 60, "possible duplicate: " + describe(c.serial()) + " is in "
                            + holders.describe(scan.id()) + " but was last seen in " + holders.describe(c.previous())
                            + " (not loaded; checked again when it loads)");
                }
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
        Handover handed = handovers.get(serial);
        if (handed != null && System.currentTimeMillis() - handed.at() < HANDOVER_MS && actor != null
                && !actor.getUniqueId().equals(handed.from()) && newId.equals(Holders.playerId(actor.getUniqueId()))) {
            // The player picked this up from someone else's drop (a trade). The giver still has the other copy: take
            // that one and blame the giver, so the receiver keeps what they traded for.
            Player giver = Bukkit.getPlayer(handed.from());
            if (giver != null && oldId.equals(Holders.playerId(giver.getUniqueId()))) {
                Scan old = holders.resolve(oldId, false);
                Sighting theirs = null;
                if (old != null) for (Sighting s : old.sightings()) {
                    if (s.serial().equals(serial) && (theirs == null || theirs.nested() && !s.nested())) theirs = s;
                }
                if (theirs != null) { actOn(serial, oldId, newId, theirs, giver, null); return; }
            }
            actOn(serial, newId, oldId, target, null, handed.from());
            return;
        }
        actOn(serial, newId, oldId, target, actor, null);
    }

    private void actOn(String serial, String newId, String oldId, Sighting target, Player actor, UUID giver) {
        boolean ownHolder = actor != null && newId.equals(Holders.playerId(actor.getUniqueId()));
        if (giver == null && ownHolder && (actor.getGameMode() == GameMode.CREATIVE || actor.hasPermission(TransferProtection.BYPASS))) {
            if (target.nested()) { suppressed.put(serial, System.currentTimeMillis()); return; }
            if (holders.restamp(newId, serial, target.slot())) { restamped++; return; }
        }
        String what = describe(serial);
        String detail = what + " exists in two places: " + holders.describe(newId) + " and " + holders.describe(oldId);
        boolean remove = plugin.getConfig().getString("ledger.on-duplicate", "remove").equalsIgnoreCase("remove");
        long nowMs = System.currentTimeMillis();
        UUID blamed = giver != null ? giver : actor == null ? null : actor.getUniqueId();
        CommandActivity.Excuse excuse = remove ? commands.excuse(blamed, nowMs, nowMs)
                : CommandActivity.Excuse.NONE;
        if (excuse == CommandActivity.Excuse.TRUSTED) {
            // Console or staff ran an item command for this player (kit with saved items, rollback, /give): the copy is
            // intended, so it becomes an item of its own. Staff still see it in /sus.
            if (!target.nested() && holders.restamp(newId, serial, target.slot())) restamped++;
            else suppressed.put(serial, nowMs);
            report(actor, 60, "copy right after a console/staff item command, re-sealed as a new item: " + detail);
            return;
        }
        if (excuse == CommandActivity.Excuse.OWN) {
            // The player's own kit/item command may have made this copy: not removed now. Checked again once the
            // suppression ends; the player's trust is used up by then, so a real duplicate is removed.
            suppressed.put(serial, nowMs);
            report(actor, 60, "possible duplicate right after the player's own item command, not removed yet: " + detail);
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
        if (giver != null) {
            org.bukkit.OfflinePlayer g = Bukkit.getOfflinePlayer(giver);
            plugin.reporter().report(giver, g.getName() == null ? giver.toString() : g.getName(), DupeType.LEDGER_MISMATCH, 100,
                    detail + " (the copy was handed over by this player)", false);
            return;
        }
        report(actor, 100, detail);
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
        onForget.forEach(Runnable::run);
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
        if (!enabled) return;
        if (e.getEntity() instanceof Player p) {
            UUID thrower = e.getItem().getThrower();
            if (thrower != null && !thrower.equals(p.getUniqueId())) {
                java.util.List<Sighting> got = new java.util.ArrayList<>();
                scanner.collect(e.getItem().getItemStack(), 0, false, got, null);
                long now = System.currentTimeMillis();
                for (Sighting s : got) handovers.put(s.serial(), new Handover(thrower, now));
            }
            enqueuePlayerSoon(p);
        } else if (interesting(e.getItem().getItemStack())) {
            // A mob (zombie, fox, allay, piglin...) picked it up: it now holds the item.
            Entity mob = e.getEntity();
            Bukkit.getScheduler().runTask(plugin, () -> { if (mob.isValid()) enqueue(Holders.entityId(mob.getUniqueId())); });
        }
    }

    /** Giving an item to an allay, putting armor on a horse, a disc into a jukebox, a book onto a lectern or shelf... */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void interactEntity(PlayerInteractEntityEvent e) {
        if (!enabled || e.getRightClicked() instanceof Player || !(e.getRightClicked() instanceof LivingEntity)) return;
        String id = Holders.entityId(e.getRightClicked().getUniqueId());
        toucher.put(id, e.getPlayer().getUniqueId());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { enqueuePlayer(p); enqueue(id); });
    }

    @EventHandler(priority = EventPriority.MONITOR) public void interactBlock(PlayerInteractEvent e) {
        if (!enabled || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null
                || e.useInteractedBlock() == Event.Result.DENY) return;
        Block b = e.getClickedBlock();
        // Chests and the like are handled by the inventory events; these blocks take items without an inventory view.
        switch (b.getType().name()) {
            case "JUKEBOX", "LECTERN", "CHISELED_BOOKSHELF", "DECORATED_POT" -> { }
            default -> { return; }
        }
        String id = Holders.blockId(b);
        toucher.put(id, e.getPlayer().getUniqueId());
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> { enqueuePlayer(p); enqueue(id); });
    }

    /**
     * The game does not carry an item's custom data onto a placed shulker box, so placing and breaking it would give
     * it a new seal (and launder a copy). The seal is written onto the block and given back to the dropped item.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void placed(BlockPlaceEvent e) {
        if (!enabled) return;
        String serial = seal.read(e.getItemInHand());
        if (serial == null || !Tag.SHULKER_BOXES.isTagged(e.getItemInHand().getType())) return;
        Block b = e.getBlockPlaced();
        String key = pos(b.getWorld(), b.getX(), b.getY(), b.getZ());
        placedSeals.put(key, serial);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!(b.getState(false) instanceof org.bukkit.block.TileState tile) || !Tag.SHULKER_BOXES.isTagged(b.getType())) return;
            tile.getPersistentDataContainer().set(seal.key(), org.bukkit.persistence.PersistentDataType.STRING, serial);
            enqueue(Holders.blockId(b));
        });
    }

    /** A shulker box item dropping where a sealed one stood gets its seal back (broken by hand, explosion, piston). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void reseal(ItemSpawnEvent e) {
        if (!enabled || placedSeals.isEmpty()) return;
        ItemStack stack = e.getEntity().getItemStack();
        if (!Tag.SHULKER_BOXES.isTagged(stack.getType()) || seal.read(stack) != null) return;
        org.bukkit.Location l = e.getLocation();
        String key = pos(l.getWorld(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
        String serial = placedSeals.get(key);
        if (serial == null || Tag.SHULKER_BOXES.isTagged(l.getBlock().getType())) return;
        placedSeals.remove(key);
        seal.restore(stack, serial);
        e.getEntity().setItemStack(stack);
    }

    /**
     * Placing a sealed block item (a shulker box) whose other copy still exists elsewhere is refused at once: a
     * duplicated shulker placed as a block would otherwise be emptied before the ledger removes the item.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true) public void place(BlockPlaceEvent e) {
        if (!enabled) return;
        String serial = seal.read(e.getItemInHand());
        if (serial == null) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE || p.hasPermission(TransferProtection.BYPASS)) return;
        String own = Holders.playerId(p.getUniqueId());
        Scan mine = holders.player(p, false);
        boolean duplicate = mine.count(serial) > 1;
        String elsewhere = null;
        if (!duplicate) {
            String holder = store.holderOf(serial);
            if (holder != null && !holder.equals(own)) {
                Scan other = holders.resolve(holder, false);
                if (other != null && other.contains(serial)) { duplicate = true; elsewhere = holder; }
            }
        }
        if (!duplicate) return;
        e.setCancelled(true);
        enqueue(own);
        Long until = suppressed.get(serial);
        if (until != null && System.currentTimeMillis() - until < SUPPRESS_MS) return;
        report(p, 100, "blocked placing a duplicated " + describe(serial) + " (another copy is in "
                + (elsewhere == null ? "the same inventory" : holders.describe(elsewhere)) + ")");
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
            if (Holders.storesItems(state)) process(holders.block(state.getBlock(), false));
            placedSeals.remove(pos(state.getWorld(), state.getX(), state.getY(), state.getZ()));
        }
    }

    /** Armor stands, frames and storage entities are checked right after they appear and whenever a player changes them. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void entitySpawn(EntitySpawnEvent e) {
        Entity en = e.getEntity();
        if (!enabled || !(en instanceof ArmorStand || en instanceof ItemFrame || (en instanceof InventoryHolder && !(en instanceof Player))
                || en instanceof LivingEntity l && !(en instanceof Player) && holders.holdsItems(l))) return;
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
                    || (en instanceof InventoryHolder && !(en instanceof Player))
                    || (en instanceof LivingEntity l && !(en instanceof Player) && holders.holdsItems(l))) enqueue(Holders.entityId(en.getUniqueId()));
        }
    }
}
