package uz.sentinel.ad.ledger;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import uz.sentinel.ad.protection.Seal;

/** Finds the seals in inventories, optionally giving a fresh seal to every unstackable item that lacks one. */
public final class ItemScanner {
    private static final int MAX_DEPTH = 3;

    private final Seal seal;
    private volatile boolean autoSeal = true;
    private volatile Set<Material> excluded = Set.of();

    public ItemScanner(Seal seal) {
        this.seal = seal;
    }

    public void configure(FileConfiguration config) {
        autoSeal = config.getBoolean("ledger.auto-seal", true);
        Set<Material> set = new HashSet<>();
        for (String name : config.getStringList("ledger.exclude-materials")) {
            Material m = Material.matchMaterial(name.toUpperCase(Locale.ROOT));
            if (m != null) set.add(m);
        }
        excluded = set;
    }

    /** Scans every slot of the inventory. Unsealed unstackable items are sealed when {@code stamp} is set. */
    public void scan(Inventory inventory, boolean stamp, List<Sighting> out) {
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType().isAir()) continue;
            final int slot = i;
            collect(item, slot, stamp, out, () -> inventory.setItem(slot, item));
        }
    }

    /** Scans one item; {@code writeBack} stores it again after sealing (may be null when {@code stamp} is false). */
    public void collect(ItemStack item, int slot, boolean stamp, List<Sighting> out, Runnable writeBack) {
        if (item == null || item.getType().isAir()) return;
        if (item.getMaxStackSize() == 1) {
            String serial = seal.read(item);
            if (serial == null && stamp && autoSeal && writeBack != null && !excluded.contains(item.getType())) {
                serial = seal.apply(item);
                writeBack.run();
            }
            if (serial != null) out.add(new Sighting(serial, slot, false));
        }
        if (isContainerItem(item.getType())) nested(item, slot, out, 1);
    }

    static boolean isContainerItem(Material type) {
        return Tag.SHULKER_BOXES.isTagged(type) || type.name().endsWith("BUNDLE");
    }

    private void nested(ItemStack item, int slot, List<Sighting> out, int depth) {
        if (depth > MAX_DEPTH) return;
        ItemMeta meta = item.getItemMeta();
        ItemStack[] inside = null;
        if (meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox box) {
            inside = box.getInventory().getContents();
        } else if (meta instanceof BundleMeta bundle) {
            inside = bundle.getItems().toArray(new ItemStack[0]);
        }
        if (inside == null) return;
        for (ItemStack in : inside) {
            if (in == null || in.getType().isAir()) continue;
            if (in.getMaxStackSize() == 1) {
                String serial = seal.read(in);
                if (serial != null) out.add(new Sighting(serial, slot, true));
            }
            if (isContainerItem(in.getType())) nested(in, slot, out, depth + 1);
        }
    }
}
