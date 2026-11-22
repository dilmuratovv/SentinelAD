package uz.sentinel.ad.protection;

import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/** Reads and writes the unique serial ("seal") that marks a single unstackable item. */
public final class Seal {
    private final NamespacedKey key;

    public Seal(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "serial");
    }

    /** The serial of the item, or null when it has none. Cheap for ordinary stackable items. */
    public String read(ItemStack item) {
        // Only unstackable items can be sealed, so skip the (cloning) meta lookup for everything else.
        if (item == null || item.getType().isAir() || item.getMaxStackSize() != 1 || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        return meta == null ? null : meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    public boolean isSealed(ItemStack item) {
        return read(item) != null;
    }

    /** Gives the item a brand-new serial, replacing any existing one (used for deliberate clones). */
    public String replace(ItemStack item) {
        String serial = UUID.randomUUID().toString();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, serial);
        item.setItemMeta(meta);
        return serial;
    }

    /** Seals the item in place and returns the new serial. The caller must have checked {@link #isSealed}. */
    public String apply(ItemStack item) {
        String serial = UUID.randomUUID().toString();
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, serial);
        item.setItemMeta(meta);
        return serial;
    }
}
