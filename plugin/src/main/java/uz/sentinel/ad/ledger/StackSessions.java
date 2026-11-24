package uz.sentinel.ad.ledger;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;
import uz.sentinel.ad.protection.TransferProtection;

/**
 * Stackable items cannot carry a serial, so duplication is caught by conservation: while a player has a chest, barrel,
 * shulker box, hopper, dropper, dispenser or ender chest open, the watched items in player + container must not grow,
 * except by what the player picked up or a hopper moved in. Anything else is created from nothing.
 */
public final class StackSessions implements Listener {
    private static final class Session {
        final UUID player;
        final Inventory top;
        final Set<String> area;
        final EnumMap<Material, Integer> before;
        final EnumMap<Material, Integer> flow = new EnumMap<>(Material.class);
        java.util.Map<String, Integer> topBefore = java.util.Map.of();
        final long opened = System.currentTimeMillis();
        boolean tainted;
        Session(UUID player, Inventory top, Set<String> area, EnumMap<Material, Integer> before) {
            this.player = player; this.top = top; this.area = area; this.before = before;
        }
    }

    private final SentinelADPlugin plugin;
    private final Holders holders;
    private final CommandActivity commands;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private volatile ChunkAudit audit;

    public void audit(ChunkAudit audit) { this.audit = audit; }

    private java.util.Map<String, Integer> topCount(Inventory top) {
        java.util.Map<String, Integer> m = new java.util.TreeMap<>();
        countInto(m, top.getContents());
        return m;
    }
    private volatile boolean enabled;
    private volatile Set<Material> watched = EnumSet.noneOf(Material.class);
    private volatile boolean watchAll;
    /** Lower-case title patterns ("*" wildcard) of plugin menus that store items (backpacks, vaults). */
    private volatile List<String> storageMenus = List.of();

    public StackSessions(SentinelADPlugin plugin, Holders holders, CommandActivity commands) {
        this.plugin = plugin;
        this.holders = holders;
        this.commands = commands;
    }

    public void applyConfig() {
        enabled = plugin.getConfig().getBoolean("stackables.enabled", true);
        Set<Material> set = EnumSet.noneOf(Material.class);
        boolean all = false;
        for (String name : plugin.getConfig().getStringList("stackables.materials")) {
            if (name.trim().equals("*")) { all = true; continue; }
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null) set.add(m);
        }
        watched = set;
        watchAll = all;
        List<String> menus = new java.util.ArrayList<>();
        for (String t : plugin.getConfig().getStringList("stackables.storage-menus")) {
            if (!t.isBlank()) menus.add(t.trim().toLowerCase(Locale.ROOT));
        }
        storageMenus = menus;
    }

    public boolean watching(Material m) {
        return watched.contains(m) || (watchAll && !m.isAir() && m.isItem() && m.getMaxStackSize() > 1);
    }

    /**
     * Only real storage: a block or storage entity in the world (it has a location) or the player's ender chest.
     * Plugin menus (shops, auction houses, crates, kit menus) are also CHEST inventories, but they hand out items
     * by design, so counting them would take legitimately bought or claimed items away.
     */
    /** Glob match with "*" (any text); case is already lowered. */
    static boolean titleMatches(String title, List<String> patterns) {
        String t = title.toLowerCase(Locale.ROOT).trim();
        for (String p : patterns) {
            String regex = java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q");
            if (t.matches(regex)) return true;
        }
        return false;
    }

    static boolean allowed(InventoryType type, boolean inWorld) {
        return switch (type) {
            case ENDER_CHEST -> true;
            case CHEST, BARREL, SHULKER_BOX, HOPPER, DROPPER, DISPENSER -> inWorld;
            default -> false;
        };
    }

    private static boolean inWorld(Inventory inventory) {
        try { return inventory.getLocation() != null; } catch (RuntimeException e) { return false; }
    }

    private EnumMap<Material, Integer> count(Player p, Inventory top) {
        EnumMap<Material, Integer> totals = new EnumMap<>(Material.class);
        add(totals, p.getInventory().getContents());
        add(totals, new ItemStack[]{p.getItemOnCursor()});
        add(totals, top.getContents());
        return totals;
    }

    private void add(EnumMap<Material, Integer> totals, ItemStack[] items) { add(totals, items, 0); }

    /** Adds the watched items (also inside bundles and shulker boxes) to {@code totals}, keyed by material name. */
    public void countInto(java.util.Map<String, Integer> totals, ItemStack[] items) {
        EnumMap<Material, Integer> m = new EnumMap<>(Material.class);
        add(m, items, 0);
        m.forEach((k, v) -> totals.merge(k.name(), v, Integer::sum));
    }

    /**
     * Counts watched items, including those inside bundles and shulker boxes: taking diamonds out of a bundle while a
     * chest is open moves them, it does not create them.
     */
    private void add(EnumMap<Material, Integer> totals, ItemStack[] items, int depth) {
        for (ItemStack i : items) {
            if (i == null || i.getType().isAir()) continue;
            if (watching(i.getType())) totals.merge(i.getType(), i.getAmount(), Integer::sum);
            if (depth < 3) {
                ItemStack[] inside = ItemScanner.contents(i);
                if (inside != null) add(totals, inside, depth + 1);
            }
        }
    }

    private Set<String> areaOf(Inventory inventory, Player viewer) {
        Set<String> ids = new HashSet<>(holders.idsOf(inventory));
        if (inventory.getType() == InventoryType.ENDER_CHEST) ids.add(Holders.enderId(viewer.getUniqueId()));
        return ids;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void open(InventoryOpenEvent e) {
        if (!enabled || !(e.getPlayer() instanceof Player p)) return;
        Inventory top = e.getInventory();
        boolean storageMenu = false;
        if (!allowed(top.getType(), inWorld(top)) && !inWorld(top) && !storageMenus.isEmpty()) {
            // A plugin storage menu (backpack, vault): items only move between it and the player, like a chest.
            String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.getView().title());
            storageMenu = titleMatches(title, storageMenus);
        }
        if ((!allowed(top.getType(), inWorld(top)) && !storageMenu) || p.getGameMode() == GameMode.CREATIVE
                || p.hasPermission(TransferProtection.BYPASS)) return;
        Set<String> area = areaOf(top, p);
        // Someone else already in the same container makes the arithmetic meaningless for both.
        for (Session s : sessions.values()) {
            for (String id : area) if (s.area.contains(id)) { s.tainted = true; }
        }
        Session s = new Session(p.getUniqueId(), top, area, count(p, top));
        s.topBefore = topCount(top);
        for (Session other : sessions.values()) {
            if (!java.util.Collections.disjoint(other.area, area) || other.top == top) { s.tainted = true; other.tainted = true; }
        }
        sessions.put(p.getUniqueId(), s);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void close(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        Session s = sessions.remove(p.getUniqueId());
        if (s == null || !enabled) return;
        // A container in the world that lost watched items to this player: remember who emptied that chunk.
        ChunkAudit a = audit;
        if (a != null && s.top.getLocation() != null && !Amounts.gains(topCount(s.top), s.topBefore, 1).isEmpty()) {
            a.took(s.top.getLocation().getChunk(), p.getUniqueId());
        }
        if (s.tainted) return;
        EnumMap<Material, Integer> after = count(p, s.top);
        int min = Math.max(1, plugin.getConfig().getInt("stackables.min-gain", 1));
        StringBuilder detail = new StringBuilder();
        EnumMap<Material, Integer> gains = new EnumMap<>(Material.class);
        Set<Material> seen = EnumSet.noneOf(Material.class);
        seen.addAll(after.keySet());
        seen.addAll(s.before.keySet());
        for (Material m : seen) {
            int gain = after.getOrDefault(m, 0) - s.before.getOrDefault(m, 0) - s.flow.getOrDefault(m, 0);
            if (gain >= min) {
                gains.put(m, gain);
                if (detail.length() > 0) detail.append(", ");
                detail.append('+').append(gain).append(' ').append(m.name().toLowerCase(Locale.ROOT));
            }
        }
        if (gains.isEmpty()) return;
        boolean remove = plugin.getConfig().getString("stackables.on-gain", "remove").equalsIgnoreCase("remove");
        if (commands.explains(p.getUniqueId(), s.opened, System.currentTimeMillis())) {
            // /give, /kit and similar while the container was open add items the arithmetic cannot see: alert only.
            plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, 50,
                    "items appeared while a container was open, right after an item command (not removed): " + detail, false);
            return;
        }
        int left = 0;
        if (remove) {
            for (var g : gains.entrySet()) {
                int[] untaken = new int[1];
                String id = take(p, s.top, g.getKey(), g.getValue(), untaken);
                left += untaken[0];
                if (id != null) detail.append(" [restore id ").append(id).append(']');
            }
        }
        String outcome = !remove ? "" : left == 0 ? " (removed)"
                : " (" + left + " not removed: inside a bundle or shulker box, or already moved)";
        plugin.reporter().report(p.getUniqueId(), p.getName(), DupeType.STACK_GAIN, 90,
                "items appeared from nothing while a container was open: " + detail + outcome, false);
    }

    /**
     * Takes the unexplained amount back, first from the player and then from the container that was open (hoppers and
     * redstone machines write there). Returns the restore id of the backed-up items, or null.
     */
    private String take(Player p, Inventory top, Material m, int amount, int[] untaken) {
        int wanted = amount;
        amount -= takeFrom(p.getInventory(), m, amount);
        if (amount > 0) amount -= takeFrom(top, m, amount);
        untaken[0] = amount;
        int taken = wanted - amount;
        if (taken <= 0 || plugin.ledger() == null) return null;
        return plugin.ledger().backup(new ItemStack(m, taken));
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        Session s = sessions.get(p.getUniqueId());
        if (s == null) return;
        ItemStack stack = e.getItem().getItemStack();
        if (watching(stack.getType())) s.flow.merge(stack.getType(), Math.max(0, stack.getAmount() - e.getRemaining()), Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void drop(PlayerDropItemEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null) return;
        ItemStack stack = e.getItemDrop().getItemStack();
        if (watching(stack.getType())) s.flow.merge(stack.getType(), -stack.getAmount(), Integer::sum);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void move(InventoryMoveItemEvent e) {
        if (sessions.isEmpty() || !watching(e.getItem().getType())) return;
        int amount = e.getItem().getAmount();
        for (Session s : sessions.values()) {
            Player p = org.bukkit.Bukkit.getPlayer(s.player);
            if (p == null) continue;
            boolean into = !java.util.Collections.disjoint(s.area, areaOf(e.getDestination(), p));
            boolean out = !java.util.Collections.disjoint(s.area, areaOf(e.getSource(), p));
            if (into) s.flow.merge(e.getItem().getType(), amount, Integer::sum);
            if (out) s.flow.merge(e.getItem().getType(), -amount, Integer::sum);
        }
    }

    @EventHandler public void quit(PlayerQuitEvent e) { sessions.remove(e.getPlayer().getUniqueId()); }
    @EventHandler public void death(PlayerDeathEvent e) { sessions.remove(e.getEntity().getUniqueId()); }
}
