package uz.sentinel.ad.ledger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.loot.Lootable;
import uz.sentinel.ad.protection.Seal;

/** Knows how to find, scan, edit and describe every kind of place an item can be. Main thread only. */
public final class Holders {
    /** A handle on one item position inside a holder. */
    public interface Ref {
        ItemStack get();
        void set(ItemStack item);
    }

    /** Every equipment slot of this server version (1.21 has BODY, 26.x also SADDLE). */
    private static final EquipmentSlot[] EQUIPMENT = EquipmentSlot.values();
    /** Slot numbers of equipment in holders that also have an inventory (allay, horse...): 1000 + slot ordinal. */
    static final int EQUIPMENT_BASE = 1000;
    /** Slot numbers of a player's transient work view (crafting grid, anvil, smithing...): 2000 + raw slot. */
    static final int VIEW_BASE = 2000;
    /** The seal of the block itself (a placed shulker box): reported, never removed. */
    static final int BLOCK_SELF = -2;

    private final ItemScanner scanner;
    private final Seal seal;
    private volatile boolean stampContainers = true;

    public Holders(ItemScanner scanner, Seal seal) {
        this.scanner = scanner;
        this.seal = seal;
    }

    public void stampContainers(boolean value) { stampContainers = value; }

    // ------------------------------------------------------------------ ids

    public static String playerId(UUID id) { return "P:" + id; }
    public static String enderId(UUID id) { return "N:" + id; }
    public static String entityId(UUID id) { return "X:" + id; }
    public static String blockId(Block b) { return "B:" + b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    /** Holder ids covered by a Bukkit inventory (a double chest has two). Empty for virtual GUIs. */
    public List<String> idsOf(Inventory inventory) {
        List<String> ids = new ArrayList<>(2);
        InventoryHolder holder;
        try { holder = inventory.getHolder(false); } catch (RuntimeException e) { holder = null; }
        if (holder instanceof DoubleChest dc) {
            if (dc.getLeftSide(false) instanceof Chest l) ids.add(blockId(l.getBlock()));
            if (dc.getRightSide(false) instanceof Chest r) ids.add(blockId(r.getBlock()));
        } else if (holder instanceof BlockState state) {
            ids.add(blockId(state.getBlock()));
        } else if (holder instanceof Player p) {
            ids.add(playerId(p.getUniqueId()));
        } else if (holder instanceof Entity e) {
            ids.add(entityId(e.getUniqueId()));
        } else if (inventory.getType() == InventoryType.ENDER_CHEST && inventory.getLocation() == null) {
            // owner unknown here; callers add the viewer's ender chest themselves
        }
        return ids;
    }

    // ------------------------------------------------------------------ scanning

    public Scan player(Player p, boolean stamp) {
        List<Sighting> out = new ArrayList<>();
        scanner.scan(p.getInventory(), stamp, out);
        scanner.collect(p.getItemOnCursor(), -1, false, out, null);
        // Items in a crafting grid, anvil, smithing table... belong to the player until the view closes; a copy hidden
        // there must not be invisible. Result slots only preview an item (an anvil result carries the input's seal).
        InventoryView view = p.getOpenInventory();
        Inventory top = view.getTopInventory();
        if (workView(top.getType()) && ownView(top, p)) {
            for (int raw = 0; raw < top.getSize(); raw++) {
                if (view.getSlotType(raw) == InventoryType.SlotType.RESULT) continue;
                scanner.collect(top.getItem(raw), VIEW_BASE + raw, false, out, null);
            }
        }
        return new Scan(playerId(p.getUniqueId()), out);
    }

    /** Player-owned work views whose items go back to the player when the view closes. */
    static boolean workView(InventoryType type) {
        return switch (type) {
            case CRAFTING, WORKBENCH, ANVIL, SMITHING, GRINDSTONE, STONECUTTER, LOOM, CARTOGRAPHY, ENCHANTING, BEACON,
                 MERCHANT -> true;
            default -> false;
        };
    }

    /**
     * A real work view: the player's own crafting grid, a block in the world (crafting table, anvil...) or a villager.
     * Plugin menus that only look like an anvil or a crafting table (rename and text-input GUIs) often show a copy of
     * the player's item, which is not a duplicate.
     */
    static boolean ownView(Inventory top, Player p) {
        try {
            if (top.getLocation() != null) return true;
            InventoryHolder h = top.getHolder(false);
            return h == p || h instanceof org.bukkit.inventory.Merchant;
        } catch (RuntimeException e) {
            return false;
        }
    }

    public Scan ender(Player p, boolean stamp) {
        List<Sighting> out = new ArrayList<>();
        scanner.scan(p.getEnderChest(), stamp, out);
        return new Scan(enderId(p.getUniqueId()), out);
    }

    /**
     * Inventory of any block that stores items: containers (chest, barrel, furnace, brewing stand, crafter...) and
     * jukebox, lectern, chiseled bookshelf, decorated pot. Null for other blocks and unopened loot containers.
     */
    static Inventory containerInventory(BlockState state) {
        if (!(state instanceof BlockInventoryHolder c)) return null;
        if (state instanceof Lootable l && l.hasLootTable()) return null; // never trigger loot generation
        return c instanceof Chest ch ? ch.getBlockInventory() : c.getInventory();
    }

    /** True for block states that {@link #containerInventory} can read. */
    public static boolean storesItems(BlockState state) {
        return state instanceof BlockInventoryHolder && !(state instanceof Lootable l && l.hasLootTable());
    }

    private boolean stampable(Inventory inv) {
        return switch (inv.getType()) {
            case CHEST, BARREL, SHULKER_BOX, HOPPER, DROPPER, DISPENSER -> true;
            default -> false;
        };
    }

    public Scan block(Block b, boolean stamp) {
        List<Sighting> out = new ArrayList<>();
        BlockState state = b.getState(false);
        Inventory inv = containerInventory(state);
        if (inv != null) scanner.scan(inv, stamp && stampContainers && stampable(inv), out);
        // A placed shulker box keeps its item's seal on the block (see LedgerService#placed); it is part of this place.
        String own = seal.read(state);
        if (own != null) out.add(new Sighting(own, BLOCK_SELF, true));
        return new Scan(blockId(b), out);
    }

    public Scan entity(Entity e, boolean stamp) {
        List<Sighting> out = new ArrayList<>();
        if (e instanceof Item it) {
            ItemStack s = it.getItemStack();
            scanner.collect(s, 0, stamp, out, () -> it.setItemStack(s));
        } else if (e instanceof ItemFrame frame) {
            ItemStack s = frame.getItem();
            scanner.collect(s, 0, stamp, out, () -> frame.setItem(s, false));
        } else if (!(e instanceof Player)) {
            if (e instanceof InventoryHolder h) scanner.scan(h.getInventory(), stamp, out);
            // Armor stands, and mobs holding or wearing items (zombie with a sword, allay, fox, horse armor...).
            if (e instanceof LivingEntity living && living.getEquipment() != null) {
                EntityEquipment eq = living.getEquipment();
                boolean hasInventory = e instanceof InventoryHolder;
                for (EquipmentSlot slot : EQUIPMENT) {
                    ItemStack s = equipment(eq, slot);
                    // Mob gear is never sealed (every skeleton's bow would fill the ledger); a copy put there is
                    // already sealed, which is what matters. Armor stands are decoration players fill: sealed as before.
                    boolean seal = stamp && e instanceof org.bukkit.entity.ArmorStand;
                    if (s == null) continue;
                    if (!hasInventory) { scanner.collect(s, EQUIPMENT_BASE + slot.ordinal(), seal, out, () -> eq.setItem(slot, s)); continue; }
                    // A horse's saddle and armor are in its inventory and in its equipment (26.x: SADDLE, BODY): one
                    // item seen through two views is not two items, so an item the inventory scan already found
                    // in this holder is not counted again from the equipment.
                    List<Sighting> view = new ArrayList<>();
                    scanner.collect(s, EQUIPMENT_BASE + slot.ordinal(), seal, view, () -> eq.setItem(slot, s));
                    for (Sighting x : view) {
                        boolean mirrored = false;
                        for (Sighting seen : out) if (seen.slot() < EQUIPMENT_BASE && seen.serial().equals(x.serial())) { mirrored = true; break; }
                        if (!mirrored) out.add(x);
                    }
                }
            }
        }
        return new Scan(entityId(e.getUniqueId()), out);
    }

    private static ItemStack equipment(EntityEquipment eq, EquipmentSlot slot) {
        try { return eq.getItem(slot); } catch (RuntimeException e) { return null; } // slot this entity does not have
    }

    /** True when a mob or stand wears or holds something worth tracking. */
    /** True when a mob or stand holds or wears a sealed item, or a shulker box or bundle that may contain one. */
    public boolean holdsItems(LivingEntity living) {
        if (living instanceof org.bukkit.entity.ArmorStand) return true;
        EntityEquipment eq = living.getEquipment();
        if (eq == null) return false;
        for (EquipmentSlot slot : EQUIPMENT) {
            ItemStack s = equipment(eq, slot);
            if (s == null || s.getType().isAir()) continue;
            if (ItemScanner.isContainerItem(s.getType()) || seal.read(s) != null) return true;
        }
        return false;
    }

    /**
     * Live look at a holder without sealing anything. Returns null when it cannot be checked right now
     * (offline player, unloaded chunk). A vanished entity counts as empty.
     */
    public Scan resolve(String id) { return resolve(id, false); }

    /** Like {@link #resolve(String)}; with {@code stamp} unsealed unstackable items get a seal on the way. */
    public Scan resolve(String id, boolean stamp) {
        try {
            String[] p = id.split(":");
            switch (p[0]) {
                case "P": { Player pl = Bukkit.getPlayer(UUID.fromString(p[1])); return pl == null ? null : player(pl, stamp); }
                case "N": { Player pl = Bukkit.getPlayer(UUID.fromString(p[1])); return pl == null ? null : ender(pl, stamp); }
                case "B": {
                    World w = Bukkit.getWorld(UUID.fromString(p[1]));
                    int x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]), z = Integer.parseInt(p[4]);
                    if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
                    return block(w.getBlockAt(x, y, z), stamp);
                }
                case "X": {
                    Entity e = Bukkit.getEntity(UUID.fromString(p[1]));
                    if (e == null || !e.isValid()) return new Scan(id, List.of());
                    return entity(e, stamp);
                }
                default: return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ editing

    public Ref ref(String id, int slot) {
        try {
            String[] p = id.split(":");
            switch (p[0]) {
                case "P": {
                    Player pl = Bukkit.getPlayer(UUID.fromString(p[1]));
                    if (pl == null) return null;
                    if (slot == -1) return new Ref() {
                        public ItemStack get() { return pl.getItemOnCursor(); }
                        public void set(ItemStack i) { pl.setItemOnCursor(i); }
                    };
                    if (slot >= VIEW_BASE) {
                        Inventory top = pl.getOpenInventory().getTopInventory();
                        return workView(top.getType()) && ownView(top, pl) ? inv(top, slot - VIEW_BASE) : null;
                    }
                    return inv(pl.getInventory(), slot);
                }
                case "N": { Player pl = Bukkit.getPlayer(UUID.fromString(p[1])); return pl == null ? null : inv(pl.getEnderChest(), slot); }
                case "B": {
                    World w = Bukkit.getWorld(UUID.fromString(p[1]));
                    int x = Integer.parseInt(p[2]), y = Integer.parseInt(p[3]), z = Integer.parseInt(p[4]);
                    if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
                    Inventory inv = containerInventory(w.getBlockAt(x, y, z).getState(false));
                    return inv == null ? null : inv(inv, slot);
                }
                case "X": {
                    Entity e = Bukkit.getEntity(UUID.fromString(p[1]));
                    if (e == null || !e.isValid()) return null;
                    if (e instanceof Item it) return new Ref() {
                        public ItemStack get() { return it.getItemStack(); }
                        public void set(ItemStack i) { if (i == null || i.getType().isAir()) it.remove(); else it.setItemStack(i); }
                    };
                    if (e instanceof ItemFrame f) return new Ref() {
                        public ItemStack get() { return f.getItem(); }
                        public void set(ItemStack i) { f.setItem(i, false); }
                    };
                    if (slot >= EQUIPMENT_BASE && slot - EQUIPMENT_BASE < EQUIPMENT.length && e instanceof LivingEntity l
                            && l.getEquipment() != null) {
                        EquipmentSlot es = EQUIPMENT[slot - EQUIPMENT_BASE];
                        EntityEquipment eq = l.getEquipment();
                        return new Ref() {
                            public ItemStack get() { return equipment(eq, es); }
                            public void set(ItemStack i) { eq.setItem(es, i); }
                        };
                    }
                    if (e instanceof InventoryHolder h) return inv(h.getInventory(), slot);
                    return null;
                }
                default: return null;
            }
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Ref inv(Inventory inventory, int slot) {
        if (slot < 0 || slot >= inventory.getSize()) return null;
        return new Ref() {
            public ItemStack get() { return inventory.getItem(slot); }
            public void set(ItemStack i) { inventory.setItem(slot, i); }
        };
    }

    /** Removes the item with this serial from the slot. Returns a copy of what was removed, or null. */
    public ItemStack remove(String id, String serial, int slot) {
        Ref ref = ref(id, slot);
        if (ref == null) return null;
        ItemStack item = ref.get();
        if (item == null || !serial.equals(seal.read(item))) return null;
        ItemStack copy = item.clone();
        ref.set(null);
        return copy;
    }


    /**
     * Removes a sealed item that sits inside a shulker box or bundle held in this slot, leaving the rest of the
     * container intact. Returns a copy of what was removed, or null.
     */
    public ItemStack removeNested(String id, String serial, int slot) {
        Ref ref = ref(id, slot);
        if (ref == null) return null;
        ItemStack outer = ref.get();
        if (outer == null || outer.getType().isAir()) return null;
        ItemStack[] removed = new ItemStack[1];
        ItemStack updated = strip(outer.clone(), serial, removed, 1);
        if (removed[0] == null) return null;
        ref.set(updated);
        return removed[0];
    }

    private ItemStack strip(ItemStack container, String serial, ItemStack[] removed, int depth) {
        if (depth > 3 || !ItemScanner.isContainerItem(container.getType())) return container;
        ItemMeta meta = container.getItemMeta();
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            Inventory inv = box.getInventory();
            for (int i = 0; i < inv.getSize() && removed[0] == null; i++) {
                ItemStack in = inv.getItem(i);
                if (in == null || in.getType().isAir()) continue;
                if (serial.equals(seal.read(in))) {
                    removed[0] = in.clone();
                    inv.setItem(i, null);
                } else {
                    ItemStack inner = strip(in.clone(), serial, removed, depth + 1);
                    if (removed[0] != null) inv.setItem(i, inner);
                }
            }
            if (removed[0] != null) { bsm.setBlockState(box); container.setItemMeta(bsm); }
        } else if (meta instanceof BundleMeta bundle) {
            List<ItemStack> items = new ArrayList<>(bundle.getItems());
            for (int i = 0; i < items.size() && removed[0] == null; i++) {
                ItemStack in = items.get(i);
                if (in == null || in.getType().isAir()) continue;
                if (serial.equals(seal.read(in))) {
                    removed[0] = in.clone();
                    items.remove(i);
                } else {
                    ItemStack inner = strip(in.clone(), serial, removed, depth + 1);
                    if (removed[0] != null) items.set(i, inner);
                }
            }
            if (removed[0] != null) { bundle.setItems(items); container.setItemMeta(bundle); }
        }
        return container;
    }

    /** Gives the item in this slot a new serial (a deliberate clone, e.g. creative mode). */
    public boolean restamp(String id, String serial, int slot) {
        Ref ref = ref(id, slot);
        if (ref == null) return false;
        ItemStack item = ref.get();
        if (item == null || !serial.equals(seal.read(item))) return false;
        seal.replace(item);
        ref.set(item);
        return true;
    }

    public String describe(String id) {
        try {
            String[] p = id.split(":");
            switch (p[0]) {
                case "P": return "player " + name(UUID.fromString(p[1]));
                case "N": return "ender chest of " + name(UUID.fromString(p[1]));
                case "B": {
                    World w = Bukkit.getWorld(UUID.fromString(p[1]));
                    return "container at " + (w == null ? "?" : w.getName()) + " " + p[2] + "," + p[3] + "," + p[4];
                }
                case "X": {
                    Entity e = Bukkit.getEntity(UUID.fromString(p[1]));
                    return e == null ? "entity " + p[1] : e.getType().name().toLowerCase() + " at " + e.getWorld().getName()
                            + " " + e.getLocation().getBlockX() + "," + e.getLocation().getBlockY() + "," + e.getLocation().getBlockZ();
                }
                default: return id;
            }
        } catch (RuntimeException e) {
            return id;
        }
    }

    private static String name(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString() : n;
    }
}
