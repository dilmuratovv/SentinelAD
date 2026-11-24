package uz.sentinel.adtest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.william278.husksync.event.BukkitSyncCompleteEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only stand-in for HuskSync (named "HuskSync" so SentinelAD treats it as one): saves the inventory to a shared
 * folder on quit and applies it on join a little later, then fires the sync-complete event. A "leak" marker makes
 * the next quit lose items AFTER the save (they land on the ground): the classic cross-server dupe.
 * Never install on a real server.
 */
public final class FakeSync extends JavaPlugin implements Listener {
    private Path shared;

    @Override public void onEnable() {
        shared = Path.of(System.getProperty("fakesync.dir", "fakesync"));
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    private static byte[] pack(ItemStack[] items) throws java.io.IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        var out = new java.io.DataOutputStream(bytes);
        out.writeInt(items.length);
        for (ItemStack it : items) {
            byte[] b = it == null || it.getType().isAir() ? new byte[0] : it.serializeAsBytes();
            out.writeInt(b.length); out.write(b);
        }
        return bytes.toByteArray();
    }

    private static ItemStack[] unpack(byte[] data) throws java.io.IOException {
        var in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
        ItemStack[] items = new ItemStack[in.readInt()];
        for (int i = 0; i < items.length; i++) {
            byte[] b = new byte[in.readInt()];
            in.readFully(b);
            items[i] = b.length == 0 ? null : ItemStack.deserializeBytes(b);
        }
        return items;
    }

    @EventHandler(priority = EventPriority.NORMAL) public void quit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        try {
            Files.createDirectories(shared);
            Files.write(shared.resolve(p.getUniqueId() + ".inv"), pack(p.getInventory().getContents()));
            Files.write(shared.resolve(p.getUniqueId() + ".end"), pack(p.getEnderChest().getContents()));
            Path leak = shared.resolve("leak-" + p.getName());
            if (Files.exists(leak)) {
                Files.delete(leak);
                Location at = p.getLocation();
                Bukkit.getScheduler().runTaskLater(this, () -> { // after the save: the items leave the player
                    for (ItemStack it : p.getInventory().getContents()) {
                        if (it != null && (it.getType() == Material.DIAMOND || it.getType() == Material.DIAMOND_SWORD)) {
                            at.getWorld().dropItem(at.clone().add(0, 0, 3), it.clone());
                            p.getInventory().remove(it);
                        }
                    }
                    getLogger().info("LEAKED items of " + p.getName() + " after the save");
                }, 3L);
            }
        } catch (Exception ex) { getLogger().warning(ex.toString()); }
    }

    @EventHandler(priority = EventPriority.MONITOR) public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                Path inv = shared.resolve(p.getUniqueId() + ".inv");
                if (Files.exists(inv)) {
                    p.getInventory().setContents(unpack(Files.readAllBytes(inv)));
                    p.getEnderChest().setContents(unpack(Files.readAllBytes(shared.resolve(p.getUniqueId() + ".end"))));
                }
            } catch (Exception ex) { getLogger().warning(ex.toString()); }
            getLogger().info("SYNCED " + p.getName());
            Bukkit.getPluginManager().callEvent(new BukkitSyncCompleteEvent(p));
        }, 10L);
    }
}
