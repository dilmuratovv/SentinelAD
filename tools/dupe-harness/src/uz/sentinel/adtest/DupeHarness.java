package uz.sentinel.adtest;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.java.JavaPlugin;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.diagnostics.Finding;

/**
 * Test-only plugin: simulates duplications (copies made through the API, which is what a server bug amounts to) and
 * legitimate moves with two connected bot players, and checks what SentinelAD does. Never install on a real server.
 */
public final class DupeHarness extends JavaPlugin implements Listener {
    private record Step(String name, Runnable action, int waitTicks, Supplier<String> verify) {}

    private final List<Step> steps = new ArrayList<>();
    private final List<String> results = new ArrayList<>();
    private SentinelADPlugin ad;
    private Player a, b;
    private World w;
    private int findingsBefore;
    private boolean started;
    private final ItemStack[] keep = new ItemStack[8];
    private boolean addOnQuit;
    private Inventory returnOnQuit;

    /** Runs after SentinelAD's quit handler (same priority, registered later): the saved inventory differs. */
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR)
    public void quit(org.bukkit.event.player.PlayerQuitEvent e) {
        if (returnOnQuit != null && e.getPlayer().getName().equals("Alice")) {
            // Like a plugin GUI that gives its input back when the viewer leaves.
            for (ItemStack it : returnOnQuit.getContents()) if (it != null) e.getPlayer().getInventory().addItem(it);
            returnOnQuit.clear();
            returnOnQuit = null;
        }
        if (addOnQuit && e.getPlayer().getName().equals("Alice")) {
            // The disconnect dupe: the inventory is already saved with 32 diamonds, then they leave the player and
            // land on the ground. After the rejoin they exist twice.
            addOnQuit = false;
            Player gone = e.getPlayer();
            Location at = gone.getLocation();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                gone.getInventory().removeItem(new ItemStack(Material.DIAMOND, 32));
                at.getWorld().dropItem(at.clone().add(0, 0, 4), new ItemStack(Material.DIAMOND, 32));
            }, 3L);
        }
    }

    @Override public void onEnable() {
        ad = (SentinelADPlugin) Bukkit.getPluginManager().getPlugin("SentinelAD");
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (started) return;
            a = Bukkit.getPlayerExact("Alice");
            b = Bukkit.getPlayerExact("Bob");
            if (a != null && b != null && a.isValid() && b.isValid()) { started = true; Bukkit.getScheduler().runTaskLater(this, this::begin, 60); }
        }, 20, 20);
    }

    // ------------------------------------------------------------------ helpers

    private List<Finding> newFindings() {
        List<Finding> all = ad.recent().latest(500);
        int n = ad.recent().size() - findingsBefore;
        return all.subList(0, Math.max(0, Math.min(n, all.size())));
    }

    private String findings() {
        StringBuilder sb = new StringBuilder();
        for (Finding f : newFindings()) sb.append("[").append(f.playerName()).append(' ').append(f.type().id()).append(' ')
                .append(f.confidence()).append("% ").append(f.detail()).append("] ");
        return sb.toString();
    }

    private boolean flagged(String who, int minConfidence) {
        for (Finding f : newFindings()) if ((who == null || f.playerName().equals(who)) && f.confidence() >= minConfidence) return true;
        return false;
    }

    private String serial(ItemStack i) { return ad.seal().read(i); }

    private int count(Inventory inv, String serial) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && serial.equals(serial(i))) n++;
        return n;
    }

    private Block block(int dx, int dz, Material type) {
        Location l = a.getLocation();
        Block blk = w.getBlockAt(l.getBlockX() + dx, l.getBlockY(), l.getBlockZ() + dz);
        blk.setType(type);
        return blk;
    }

    private Inventory chest(int dx, int dz) {
        return ((Chest) block(dx, dz, Material.CHEST).getState(false)).getBlockInventory();
    }

    private void reopen(Player p, Inventory inv) { p.openInventory(inv); p.closeInventory(); }

    private int diamonds(Inventory inv) {
        int n = 0;
        for (ItemStack i : inv.getContents()) if (i != null && i.getType() == Material.DIAMOND) n += i.getAmount();
        return n;
    }

    // ------------------------------------------------------------------ scenarios

    private String crashPhase() {
        try {
            java.nio.file.Path f = getDataFolder().toPath().resolve("crash-phase");
            return Files.exists(f) ? Files.readString(f).trim() : "";
        } catch (Exception e) { return ""; }
    }

    private Block crashChest(int dx, Material fill, int amount, boolean create) {
        int cx = 30, cz = 30;
        org.bukkit.Chunk ch = w.getChunkAt(cx, cz);
        ch.addPluginChunkTicket(this);
        Block b = w.getBlockAt((cx << 4) + 4 + dx, -60, (cz << 4) + 4);
        if (create) {
            b.setType(Material.CHEST);
            ((Chest) b.getState(false)).getBlockInventory().addItem(new ItemStack(fill, amount));
        }
        return b;
    }

    /** Phase 1: take items, let Alice log out (saved), then the probe kills the server before the chunks save. */
    private void crashSetup() {
        w = a.getWorld();
        a.getInventory().clear(); b.getInventory().clear();
        Block c1 = crashChest(0, Material.DIAMOND, 64, true), c2 = crashChest(3, Material.GOLD_INGOT, 32, true);
        Item drop = w.dropItem(a.getLocation().add(0, 0.5, 0), new ItemStack(Material.EMERALD, 16));
        drop.setPickupDelay(60);
        w.save(); // chests full and the emeralds on the ground are on disk
        getLogger().info("CRASH saved world, emerald entity " + drop.getUniqueId());
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Inventory i1 = ((Chest) c1.getState(false)).getBlockInventory();
            a.openInventory(i1); i1.remove(Material.DIAMOND); a.getInventory().addItem(new ItemStack(Material.DIAMOND, 64)); a.closeInventory();
            Inventory i2 = ((Chest) c2.getState(false)).getBlockInventory();
            b.openInventory(i2); i2.remove(Material.GOLD_INGOT); b.getInventory().addItem(new ItemStack(Material.GOLD_INGOT, 32)); b.closeInventory();
            getLogger().info("CRASH took: alice diamonds=" + diamonds(a.getInventory()) + " emeralds="
                    + a.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
            a.kick(net.kyori.adventure.text.Component.text("harness: log out before the crash"));
            Bukkit.getScheduler().runTaskLater(this, () -> getLogger().info("READY TO CRASH"), 40);
        }, 100); // the emeralds are picked up in the meantime
    }

    /** Phase 2, after the restart: the journal must have settled the rollback. */
    private void crashCheck() {
        w = a.getWorld();
        Block c1 = crashChest(0, null, 0, false), c2 = crashChest(3, null, 0, false);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            int chest = diamonds(((Chest) c1.getState(false)).getBlockInventory());
            int alice = diamonds(a.getInventory());
            int gold = ((Chest) c2.getState(false)).getBlockInventory().all(Material.GOLD_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum();
            int bobGold = b.getInventory().all(Material.GOLD_INGOT).values().stream().mapToInt(ItemStack::getAmount).sum();
            int emeralds = a.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum();
            int groundEmeralds = 0;
            for (Item it : w.getEntitiesByClass(Item.class)) if (it.getItemStack().getType() == Material.EMERALD) groundEmeralds += it.getItemStack().getAmount();
            boolean aliceFlag = false, bobFlag = false;
            for (Finding f : ad.recent().latest(100)) {
                if (f.detail().contains("crash dupe") && f.playerName().equals("Alice")) aliceFlag = true;
                if (f.playerName().equals("Bob")) bobFlag = true;
            }
            String state = "chestDiamonds=" + chest + " aliceDiamonds=" + alice + " aliceEmeralds=" + emeralds + " groundEmeralds="
                    + groundEmeralds + " chestGold=" + gold + " bobGold=" + bobGold + " aliceFlag=" + aliceFlag + " bobFlag=" + bobFlag;
            boolean ok = chest + alice == 64 && emeralds + groundEmeralds == 16 && aliceFlag && !bobFlag && gold + bobGold == 32;
            results.add((ok ? "PASS" : "FAIL") + " crash-rollback-dupe: " + state);
            getLogger().info(results.get(results.size() - 1));
            finish();
        }, 160);
    }

    private void begin() {
        String phase = crashPhase();
        if (phase.equals("1")) { crashSetup(); return; }
        if (phase.equals("2")) { crashCheck(); return; }
        w = a.getWorld();
        a.getInventory().clear();
        b.getInventory().clear();
        b.teleport(a.getLocation().clone().add(0, 0, 6));
        a.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        a.getInventory().setItem(1, new ItemStack(Material.NETHERITE_SWORD));
        a.getInventory().setItem(2, new ItemStack(Material.MUSIC_DISC_CAT));
        a.getInventory().setItem(3, new ItemStack(Material.WATER_BUCKET));
        a.getInventory().setItem(4, new ItemStack(Material.SHULKER_BOX));
        a.getInventory().setItem(5, new ItemStack(Material.IRON_SWORD));
        a.getInventory().setItem(6, new ItemStack(Material.GOLDEN_SWORD));
        a.getInventory().setItem(7, new ItemStack(Material.STONE_SWORD));
        Bukkit.getScheduler().runTaskLater(this, this::plan, 100); // let the ledger seal everything
    }

    private void plan() {
        for (int i = 0; i < 8; i++) keep[i] = a.getInventory().getItem(i).clone();
        String sword = serial(keep[0]), neth = serial(keep[1]), disc = serial(keep[2]);
        if (sword == null || neth == null || disc == null) { results.add("FAIL setup: items were not sealed"); finish(); return; }

        steps.add(new Step("trivial-not-sealed", () -> {}, 1,
                () -> serial(a.getInventory().getItem(3)) == null ? null : "water bucket got a seal"));

        Inventory c1 = chest(2, 0);
        steps.add(new Step("legit-move-to-chest", () -> {
            a.openInventory(c1);
            c1.setItem(0, a.getInventory().getItem(0)); a.getInventory().setItem(0, null);
            a.closeInventory();
        }, 120, () -> flagged(null, 1) ? "flagged a legitimate move: " + findings() : count(c1, sword) == 1 ? null : "sword lost"));

        Inventory c2 = chest(-2, 0);
        steps.add(new Step("dupe-into-chest", () -> { c2.setItem(0, keep[0].clone()); reopen(a, c2); }, 120,
                () -> flagged("Alice", 100) && count(c1, sword) + count(c2, sword) == 1 ? null
                        : "copies=" + (count(c1, sword) + count(c2, sword)) + " " + findings()));

        steps.add(new Step("dupe-into-crafting-grid", () -> {
            a.openWorkbench(null, true);
            a.getOpenInventory().getTopInventory().setItem(1, keep[1].clone());
        }, 140, () -> {
            int copies = count(a.getInventory(), neth) + count(a.getOpenInventory().getTopInventory(), neth);
            a.closeInventory();
            return flagged("Alice", 100) && copies == 1 ? null : "copies=" + copies + " " + findings();
        }));

        Allay[] allay = new Allay[1];
        steps.add(new Step("dupe-into-allay-hand", () -> allay[0] = w.spawn(a.getLocation().add(3, 1, 3), Allay.class, al -> {
            al.setAI(false);
            al.getEquipment().setItemInMainHand(keep[1].clone());
        }), 120, () -> {
            boolean gone = allay[0].getEquipment().getItemInMainHand().getType().isAir();
            return gone && count(a.getInventory(), neth) == 1 ? null : "allay still holds the copy: " + findings();
        }));

        Block[] juke = new Block[1];
        steps.add(new Step("dupe-into-jukebox", () -> {
            juke[0] = block(0, 3, Material.JUKEBOX);
            Jukebox j = (Jukebox) juke[0].getState(false);
            j.getInventory().setItem(0, keep[2].clone());
            Bukkit.getPluginManager().callEvent(new PlayerInteractEvent(a, Action.RIGHT_CLICK_BLOCK, null, juke[0], BlockFace.UP));
        }, 120, () -> {
            Jukebox j = (Jukebox) juke[0].getState(false);
            int copies = count(a.getInventory(), disc) + count(j.getInventory(), disc);
            return copies == 1 ? null : "copies=" + copies + " " + findings();
        }));

        steps.add(new Step("fake-/i-does-not-excuse", () -> {
            Bukkit.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(a, "/i diamond_sword"));
            a.getInventory().setItem(10, keep[1].clone());
        }, 120, () -> flagged("Alice", 100) && count(a.getInventory(), neth) == 1 ? null
                : "copies=" + count(a.getInventory(), neth) + " " + findings()));

        steps.add(new Step("forbidden-/give-does-not-excuse", () -> {
            Bukkit.getPluginManager().callEvent(new PlayerCommandPreprocessEvent(a, "/give Alice netherite_sword"));
            a.getInventory().setItem(11, keep[1].clone());
        }, 120, () -> flagged("Alice", 100) && count(a.getInventory(), neth) == 1 ? null
                : "copies=" + count(a.getInventory(), neth) + " " + findings()));

        steps.add(new Step("console-give-for-bob-does-not-excuse-alice", () -> {
            Bukkit.getPluginManager().callEvent(new ServerCommandEvent(Bukkit.getConsoleSender(), "give Bob diamond 1"));
            a.getInventory().setItem(12, keep[1].clone());
        }, 120, () -> flagged("Alice", 100) && count(a.getInventory(), neth) == 1 ? null
                : "copies=" + count(a.getInventory(), neth) + " " + findings()));

        String iron = serial(keep[5]);
        steps.add(new Step("console-give-for-alice-reseals", () -> {
            Bukkit.getPluginManager().callEvent(new ServerCommandEvent(Bukkit.getConsoleSender(), "give Alice iron_sword"));
            a.getInventory().setItem(13, keep[5].clone());
        }, 120, () -> {
            ItemStack s13 = a.getInventory().getItem(13), s5 = a.getInventory().getItem(5);
            boolean both = s13 != null && s5 != null && s13.getType() == Material.IRON_SWORD;
            boolean distinct = both && serial(s13) != null && !serial(s13).equals(serial(s5));
            return both && distinct && !flagged("Alice", 100) && flagged("Alice", 60) ? null
                    : "both=" + both + " distinct=" + distinct + " " + findings();
        }));

        Inventory c3 = chest(0, -2);
        steps.add(new Step("bundle-unpack-with-chest-open-is-legit", () -> {
            ItemStack bundle = new ItemStack(Material.BUNDLE);
            BundleMeta m = (BundleMeta) bundle.getItemMeta();
            m.addItem(new ItemStack(Material.DIAMOND, 10));
            bundle.setItemMeta(m);
            a.getInventory().setItem(20, bundle);
            Bukkit.getScheduler().runTaskLater(this, () -> {
                a.openInventory(c3);
                ItemStack in = a.getInventory().getItem(20);
                BundleMeta bm = (BundleMeta) in.getItemMeta();
                bm.setItems(List.of());
                in.setItemMeta(bm);
                a.getInventory().setItem(20, in);
                a.getInventory().setItem(21, new ItemStack(Material.DIAMOND, 10));
                a.closeInventory();
            }, 40);
        }, 100, () -> !flagged("Alice", 1) && diamonds(a.getInventory()) == 10 ? null
                : "diamonds=" + diamonds(a.getInventory()) + " " + findings()));

        steps.add(new Step("stackable-from-nothing-is-removed", () -> {
            a.openInventory(c3);
            c3.addItem(new ItemStack(Material.DIAMOND, 5));
            a.closeInventory();
        }, 60, () -> flagged("Alice", 90) && diamonds(c3) + diamonds(a.getInventory()) == 10 ? null
                : "total=" + (diamonds(c3) + diamonds(a.getInventory())) + " " + findings()));

        Inventory backpack = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text("Backpack"));
        int[] before = new int[1];
        steps.add(new Step("backpack-menu-dupe-is-removed", () -> {
            backpack.addItem(new ItemStack(Material.DIAMOND, 10));
            before[0] = diamonds(a.getInventory());
            a.openInventory(backpack);
            a.getInventory().addItem(new ItemStack(Material.DIAMOND, 10)); // took them out, the menu kept them
            a.closeInventory();
        }, 60, () -> flagged("Alice", 90) && diamonds(a.getInventory()) == before[0] ? null
                : "diamonds=" + diamonds(a.getInventory()) + " before=" + before[0] + " " + findings()));

        Inventory shop = Bukkit.createInventory(null, 27, net.kyori.adventure.text.Component.text("Backpack Shop"));
        steps.add(new Step("shop-menu-purchase-is-legit", () -> {
            shop.setItem(13, new ItemStack(Material.DIAMOND, 5));
            before[0] = diamonds(a.getInventory());
            a.openInventory(shop);
            a.getInventory().addItem(new ItemStack(Material.DIAMOND, 5)); // bought
            a.closeInventory();
        }, 60, () -> !flagged(null, 1) && diamonds(a.getInventory()) == before[0] + 5 ? null : findings()));

        int cx = 20, cz = 20; // 320 blocks away from the players: only loaded when the harness asks
        Block[] far = new Block[1];
        steps.add(new Step("chunk-reload-without-rollback-is-clean", () -> {
            org.bukkit.Chunk ch = w.getChunkAt(cx, cz);
            ch.addPluginChunkTicket(this);
            far[0] = w.getBlockAt((cx << 4) + 8, -60, (cz << 4) + 8);
            far[0].setType(Material.CHEST);
            ((Chest) far[0].getState(false)).getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 64), new ItemStack(Material.EMERALD, 20));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                w.save(); // on disk with the chest full (1.21.1 does not save on unloadChunk(save=true) reliably)
                ch.removePluginChunkTicket(this);
                w.unloadChunk(cx, cz, true);
                Bukkit.getScheduler().runTaskLater(this, () -> w.getChunkAt(cx, cz).addPluginChunkTicket(this), 40);
            }, 20);
        }, 140, () -> !flagged(null, 1) && diamonds(((Chest) far[0].getState(false)).getBlockInventory()) == 64 ? null : findings()));

        steps.add(new Step("chunk-rollback-dupe-is-removed", () -> {
            Inventory chestInv = ((Chest) far[0].getState(false)).getBlockInventory();
            before[0] = diamonds(a.getInventory());
            a.openInventory(chestInv);
            chestInv.remove(Material.DIAMOND);
            a.getInventory().addItem(new ItemStack(Material.DIAMOND, 64)); // Alice takes all 64
            a.closeInventory();
            Bukkit.getScheduler().runTaskLater(this, () -> {
                w.getChunkAt(cx, cz).removePluginChunkTicket(this);
                boolean unloaded = w.unloadChunk(cx, cz, false); // NOT saved: the disk still has the full chest
                getLogger().info("rollback unload=" + unloaded);
                Bukkit.getScheduler().runTaskLater(this, () -> w.getChunkAt(cx, cz).addPluginChunkTicket(this), 40);
            }, 20);
        }, 160, () -> {
            int inChest = diamonds(((Chest) far[0].getState(false)).getBlockInventory());
            int alice = diamonds(a.getInventory());
            if (!flagged(null, 1) && inChest == 0 && alice == before[0] + 64) {
                return "SKIP: this Paper saved the chunk anyway on unloadChunk(save=false), so no rollback happened (the SIGKILL crash test covers real rollbacks)";
            }
            return flagged("Alice", 90) && inChest == 0 && alice == before[0] + 64 ? null
                    : "chest=" + inChest + " alice=" + alice + " (before " + before[0] + ") " + findings();
        }));

        steps.add(new Step("plugin-menu-returning-items-on-quit-is-legit", () -> {
            Inventory anvil = Bukkit.createInventory(null, org.bukkit.event.inventory.InventoryType.ANVIL, net.kyori.adventure.text.Component.text("Repair"));
            anvil.setItem(0, new ItemStack(Material.DIAMOND, 7));
            before[0] = diamonds(a.getInventory()) + 7;
            a.openInventory(anvil);
            returnOnQuit = anvil;
            a.kick(net.kyori.adventure.text.Component.text("harness: quit with a plugin menu open"));
        }, 300, () -> {
            Player back = Bukkit.getPlayerExact("Alice");
            if (back == null) return "Alice did not come back " + findings();
            a = back;
            return !flagged(null, 1) && diamonds(a.getInventory()) == before[0] ? null
                    : "diamonds=" + diamonds(a.getInventory()) + " expected=" + before[0] + " " + findings();
        }));

        steps.add(new Step("disconnect-dupe-is-removed", () -> {
            a.getInventory().addItem(new ItemStack(Material.DIAMOND, 32));
            before[0] = diamonds(a.getInventory()) - 32; // after the rejoin only the copy on the ground may remain
            addOnQuit = true;
            a.kick(net.kyori.adventure.text.Component.text("harness: disconnect dupe"));
        }, 300, () -> {
            Player back = Bukkit.getPlayerExact("Alice");
            if (back == null) return "Alice did not come back " + findings();
            a = back;
            return flagged("Alice", 90) && diamonds(a.getInventory()) == before[0] ? null
                    : "diamonds=" + diamonds(a.getInventory()) + " before=" + before[0] + " " + findings();
        }));

        String gold = serial(keep[6]);
        steps.add(new Step("handover-copy-blames-giver", () -> {
            Item drop = w.dropItem(b.getLocation(), keep[6].clone());
            drop.setThrower(a.getUniqueId());
            drop.setPickupDelay(0);
        }, 160, () -> {
            boolean alice = flagged("Alice", 100), bob = flagged("Bob", 1);
            int copies = count(a.getInventory(), gold) + count(b.getInventory(), gold);
            boolean bobKeeps = count(b.getInventory(), gold) == 1;
            return alice && !bob && copies == 1 && bobKeeps ? null
                    : "alice=" + alice + " bob=" + bob + " copies=" + copies + " bobKeeps=" + bobKeeps + " " + findings();
        }));

        steps.add(new Step("plugin-anvil-menu-showing-item-is-not-a-dupe", () -> {
            Inventory menu = Bukkit.createInventory(null, org.bukkit.event.inventory.InventoryType.ANVIL, net.kyori.adventure.text.Component.text("Rename"));
            menu.setItem(0, a.getInventory().getItem(1).clone()); // a rename GUI shows the held item
            a.openInventory(menu);
        }, 120, () -> {
            boolean clean = !flagged(null, 1);
            a.closeInventory();
            return clean && count(a.getInventory(), serial(keep[1])) == 1 ? null : findings();
        }));

        Allay[] unarmed = new Allay[1];
        steps.add(new Step("mob-gear-is-not-sealed", () -> unarmed[0] = w.spawn(a.getLocation().add(-3, 1, 3), Allay.class, al -> {
            al.setAI(false);
            al.getEquipment().setItemInMainHand(new ItemStack(Material.BOW));
        }), 80, () -> serial(unarmed[0].getEquipment().getItemInMainHand()) == null ? null : "the mob's bow got a seal"));

        String stone = serial(keep[7]);
        steps.add(new Step("legit-handover", () -> {
            ItemStack real = a.getInventory().getItem(7);
            a.getInventory().setItem(7, null);
            Item drop = w.dropItem(b.getLocation(), real);
            drop.setThrower(a.getUniqueId());
            drop.setPickupDelay(0);
        }, 160, () -> !flagged(null, 1) && count(b.getInventory(), stone) == 1 ? null
                : "bob has " + count(b.getInventory(), stone) + " " + findings()));

        boolean[] cancelled = new boolean[1];
        steps.add(new Step("placing-duplicated-shulker-is-refused", () -> {
            ItemStack copy = keep[4].clone();
            a.getInventory().setItem(30, copy);
            Block at = a.getLocation().add(-3, 0, -3).getBlock();
            BlockPlaceEvent e = new BlockPlaceEvent(at, at.getState(), at.getRelative(BlockFace.DOWN), copy, a, true, EquipmentSlot.HAND);
            Bukkit.getPluginManager().callEvent(e);
            cancelled[0] = e.isCancelled();
        }, 120, () -> cancelled[0] ? null : "placement was allowed " + findings()));

        String neth2 = neth;
        steps.add(new Step("offline-copy-checked-on-rejoin", () -> {
            a.kick(net.kyori.adventure.text.Component.text("harness: rejoin"));
            Bukkit.getScheduler().runTaskLater(this, () -> b.getInventory().setItem(5, keep[1].clone()), 20);
        }, 400, () -> {
            Player back = Bukkit.getPlayerExact("Alice");
            if (back == null) return "Alice did not come back " + findings();
            a = back;
            int copies = count(a.getInventory(), neth2) + count(b.getInventory(), neth2);
            boolean unverified = false, confirmed = false;
            for (Finding f : newFindings()) {
                if (f.confidence() == 60 && f.detail().contains("not loaded")) unverified = true;
                if (f.confidence() == 100) confirmed = true;
            }
            return unverified && confirmed && copies == 1 ? null
                    : "unverified=" + unverified + " confirmed=" + confirmed + " copies=" + copies + " " + findings();
        }));

        run(0);
    }

    private void run(int i) {
        if (i >= steps.size()) { finish(); return; }
        Step s = steps.get(i);
        findingsBefore = ad.recent().size();
        getLogger().info("STEP " + s.name());
        try { s.action().run(); } catch (RuntimeException e) { results.add("FAIL " + s.name() + ": " + e); run(i + 1); return; }
        Bukkit.getScheduler().runTaskLater(this, () -> {
            String why;
            try { why = s.verify().get(); } catch (RuntimeException e) { why = "verify threw " + e; }
            boolean skip = why != null && why.startsWith("SKIP");
            results.add((why == null ? "PASS " : skip ? "SKIP " : "FAIL ") + s.name() + (why == null ? "" : ": " + (skip ? why.substring(6) : why)));
            getLogger().info(results.get(results.size() - 1));
            Bukkit.getScheduler().runTaskLater(this, () -> run(i + 1), 20);
        }, s.waitTicks());
    }

    private void finish() {
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(getDataFolder().toPath().resolve("results.txt"), results);
        } catch (Exception e) { getLogger().warning(e.toString()); }
        getLogger().info("HARNESS DONE " + results.stream().filter(r -> r.startsWith("PASS")).count() + "/"
                + results.stream().filter(r -> !r.startsWith("SKIP")).count()
                + " (skipped " + results.stream().filter(r -> r.startsWith("SKIP")).count() + ")");
        getLogger().info("LEDGER " + ad.ledger().stats());
    }
}
