package uz.sentinel.ad.protection;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;

/** Reject the transaction before Bukkit transfers ownership. Never remove or rewrite an item. */
public final class TransferProtection implements Listener {
    public static final String BYPASS = "sentinelad.bypass";

    private final SentinelADPlugin plugin;
    private final Seal seal;
    // Main-thread only. Cooldowns stop one client from flooding staff and the log.
    private final Map<UUID, Long> duplicateReports = new HashMap<>();
    private final Map<UUID, Long> oversizeReports = new HashMap<>();

    public TransferProtection(SentinelADPlugin plugin, Seal seal) {
        this.plugin = plugin;
        this.seal = seal;
    }

    // ---------------------------------------------------------------- shulker boxes

    private boolean openShulker(Block block) {
        return Tag.SHULKER_BOXES.isTagged(block.getType())
                && block.getState(false) instanceof ShulkerBox box
                && !box.getInventory().getViewers().isEmpty();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void breakBox(BlockBreakEvent event) {
        if (!plugin.getConfig().getBoolean("protection.open-shulker", true)) return;
        if (event.getPlayer().hasPermission(BYPASS)) return;
        if (openShulker(event.getBlock())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Component.text("Close the shulker box before breaking it."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void entityExplode(EntityExplodeEvent event) {
        keepOpenShulkers(event.blockList().iterator());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void blockExplode(BlockExplodeEvent event) {
        keepOpenShulkers(event.blockList().iterator());
    }

    /** An explosion would destroy an open shulker exactly like a player break; spare it. */
    private void keepOpenShulkers(Iterator<Block> blocks) {
        if (!plugin.getConfig().getBoolean("protection.open-shulker", true)) return;
        while (blocks.hasNext()) {
            if (openShulker(blocks.next())) blocks.remove();
        }
    }

    // ---------------------------------------------------------------- portals

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void portal(EntityPortalEvent event) {
        var entity = event.getEntity();
        if (entity instanceof FallingBlock
                && plugin.getConfig().getBoolean("protection.falling-block-portals", true)) event.setCancelled(true);
        else if (plugin.getConfig().getBoolean("protection.storage-entity-portals", true) && carriesStorage(entity)) {
            event.setCancelled(true);
        }
    }

    /** Chest minecarts, hopper minecarts, chest boats and chested mounts: a classic dimension-change dupe source. */
    private static boolean carriesStorage(org.bukkit.entity.Entity entity) {
        return entity instanceof org.bukkit.entity.minecart.StorageMinecart
                || entity instanceof org.bukkit.entity.minecart.HopperMinecart
                || entity instanceof org.bukkit.entity.ChestBoat
                || entity instanceof org.bukkit.entity.ChestedHorse horse && horse.isCarryingChest();
    }

    // ---------------------------------------------------------------- inventory clicks

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void click(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.hasPermission(BYPASS)) return;
        ItemStack swap = null;
        if (event.getHotbarButton() >= 0) swap = player.getInventory().getItem(event.getHotbarButton());
        else if (event.getClick() == ClickType.SWAP_OFFHAND) swap = player.getInventory().getItemInOffHand();
        for (ItemStack item : new ItemStack[]{event.getCurrentItem(), event.getCursor(), swap}) {
            if (inspect(player, item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void drag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || player.hasPermission(BYPASS)) return;
        if (inspect(player, event.getOldCursor())) event.setCancelled(true);
    }

    /** True when the transfer involving this item must be rejected. */
    private boolean inspect(Player player, ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE && plugin.getConfig().getBoolean("protection.ignore-creative", true)) return false;
        if (duplicate(player, item)) return true;
        if (plugin.getConfig().getBoolean("protection.oversized-stacks", true) && oversized(item)) {
            report(player, item);
            return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- hoppers and pickups

    private int copiesIn(Inventory inventory, String serial) {
        int copies = 0;
        for (ItemStack slot : inventory.getContents()) {
            if (serial.equals(seal.read(slot))) copies += slot.getAmount();
        }
        return copies;
    }

    private boolean conflictsAtDestination(Inventory inventory, ItemStack incoming) {
        if (!plugin.getConfig().getBoolean("protection.sealed-duplicates", true)) return false;
        String id = seal.read(incoming);
        return id != null && (incoming.getAmount() > 1 || copiesIn(inventory, id) > 0);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopperMove(InventoryMoveItemEvent event) {
        if (conflictsAtDestination(event.getDestination(), event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void hopperPickup(InventoryPickupItemEvent event) {
        if (conflictsAtDestination(event.getInventory(), event.getItem().getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void playerPickup(EntityPickupItemEvent event) {
        // A bystander can walk over someone else's item, so no flag is attributed to them.
        if (event.getEntity() instanceof Player player && !player.hasPermission(BYPASS)
                && conflictsAtDestination(player.getInventory(), event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ---------------------------------------------------------------- detection helpers

    private boolean duplicate(Player player, ItemStack item) {
        if (!plugin.getConfig().getBoolean("protection.sealed-duplicates", true)) return false;
        String serial = seal.read(item);
        if (serial == null) return false;
        // Count only independent storage locations. Crafting previews can mirror a real item.
        int copies = copiesIn(player.getInventory(), serial);
        ItemStack cursor = player.getItemOnCursor();
        if (serial.equals(seal.read(cursor))) copies += cursor.getAmount();
        Inventory top = player.getOpenInventory().getTopInventory();
        switch (top.getType()) {
            case CHEST, BARREL, SHULKER_BOX, HOPPER, DROPPER, DISPENSER, ENDER_CHEST -> {
                if (top.getLocation() != null || top.getType() == InventoryType.ENDER_CHEST) copies += copiesIn(top, serial);
            }
            default -> { }
        }
        if (copies <= 1) return false;
        if (cooldownPassed(duplicateReports, player.getUniqueId())) {
            plugin.reporter().report(player.getUniqueId(), player.getName(), DupeType.UUID_DUPLICATE, 100,
                    "Blocked transfer of repeated seal " + serial + " (" + copies + " observed)", false);
        }
        return true;
    }

    private boolean oversized(ItemStack item) {
        return item.getAmount() > item.getMaxStackSize();
    }

    private void report(Player player, ItemStack item) {
        if (!cooldownPassed(oversizeReports, player.getUniqueId())) return;
        // The stack is demonstrably invalid. Its provenance is unknown.
        plugin.reporter().report(player.getUniqueId(), player.getName(), DupeType.STACK_OVERSIZE, 50,
                "Blocked inventory transfer: " + item.getAmount() + " x " + item.getType()
                        + ", item limit " + item.getMaxStackSize(), false);
    }

    /** True (and restarts the cooldown) when enough time has passed since the last report for this player. */
    private boolean cooldownPassed(Map<UUID, Long> reports, UUID id) {
        long window = Math.max(0, plugin.getConfig().getLong("alerts.cooldown-seconds", 30)) * 1_000_000_000L;
        long now = System.nanoTime();
        Long previous = reports.get(id);
        if (previous != null && now - previous < window) return false;
        reports.put(id, now);
        return true;
    }

    @EventHandler
    public void quit(PlayerQuitEvent event) {
        duplicateReports.remove(event.getPlayer().getUniqueId());
        oversizeReports.remove(event.getPlayer().getUniqueId());
    }
}
