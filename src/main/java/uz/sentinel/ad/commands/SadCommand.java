package uz.sentinel.ad.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import uz.sentinel.ad.SentinelADPlugin;
import uz.sentinel.ad.api.DupeType;
import uz.sentinel.ad.diagnostics.Finding;
import uz.sentinel.ad.suite.SuiteCompanion;

import java.util.List;
import java.util.Locale;

/** {@code /sad}: help, info, suite, seal, recent, reload, testflag. */
public final class SadCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBCOMMANDS = List.of("help", "info", "suite", "recent", "reload", "testflag", "seal", "ledger", "inspect", "restore");

    private final SentinelADPlugin plugin;

    public SadCommand(SentinelADPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!SUBCOMMANDS.contains(sub)) sub = "help";
        if (!sender.hasPermission("sentinelad." + sub)) {
            send(sender, "&cYou do not have permission to do that.");
            return true;
        }
        switch (sub) {
            case "seal" -> seal(sender);
            case "ledger" -> ledger(sender, args);
            case "inspect" -> inspect(sender);
            case "restore" -> restore(sender, args);
            case "info" -> info(sender);
            case "suite" -> suite(sender);
            case "recent" -> recent(sender, args);
            case "reload" -> {
                plugin.reloadSettings();
                send(sender, "&aConfiguration reloaded.");
            }
            case "testflag" -> testFlag(sender, args);
            default -> help(sender);
        }
        return true;
    }

    private void help(CommandSender sender) {
        send(sender, "&bSentinelAD&7 commands:");
        raw(sender, "&7/sad info &8- version and status");
        raw(sender, "&7/sad suite &8- other Sentinel plugins");
        raw(sender, "&7/sad seal &8- assign a unique serial to one unstackable item in your hand");
        raw(sender, "&7/sad inspect &8- where the ledger thinks the held item is");
        raw(sender, "&7/sad ledger [stats|reset] &8- global item ledger");
        raw(sender, "&7/sad restore <id> &8- give back an item the ledger removed");
        raw(sender, "&7/sad recent [n] &8- latest flags");
        raw(sender, "&7/sad reload &8- reload config.yml");
        raw(sender, "&7/sad testflag <player> &8- fire a test flag (no punishment)");
    }

    private void seal(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&7Only a player can seal an item. Hold it in your main hand.");
            return;
        }
        var item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || item.getAmount() != 1 || item.getMaxStackSize() != 1) {
            send(sender, "&7Hold one non-stackable item.");
            return;
        }
        if (plugin.seal().isSealed(item)) {
            send(sender, "&7This item already has a seal: " + plugin.seal().read(item));
            return;
        }
        String serial = plugin.seal().apply(item);
        player.getInventory().setItemInMainHand(item);
        send(sender, "&aSealed item: " + serial);
    }

    private void ledger(CommandSender sender, String[] args) {
        if (args.length > 1 && args[1].equalsIgnoreCase("reset")) {
            plugin.ledger().reset();
            send(sender, "&aLedger cleared; item locations are being rebuilt.");
            return;
        }
        send(sender, "&7Ledger " + (plugin.ledger().enabled() ? "&aon" : "&coff") + "&7: " + plugin.ledger().stats());
    }

    private void inspect(CommandSender sender) {
        if (!(sender instanceof Player player)) { send(sender, "&7Only a player can inspect an item."); return; }
        send(sender, "&7" + plugin.ledger().locate(player.getInventory().getItemInMainHand()));
    }

    private void restore(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { send(sender, "&7Only a player can receive the item."); return; }
        if (args.length < 2) { send(sender, "&7Usage: /sad restore <id>"); return; }
        var item = plugin.ledger().loadRemoved(args[1]);
        if (item == null) { send(sender, "&cNo removed item with that id."); return; }
        plugin.seal().replace(item); // the original is gone, so the returned item must not clash with a stale serial
        var left = player.getInventory().addItem(item);
        left.values().forEach(i -> player.getWorld().dropItemNaturally(player.getLocation(), i));
        send(sender, "&aRestored the item (it has a new seal).");
    }

    private void info(CommandSender sender) {
        send(sender, "&bSentinelAD&7 " + plugin.getPluginMeta().getVersion()
                + " on Minecraft " + Bukkit.getMinecraftVersion() + ", flags in memory: " + plugin.recent().size()
                + (plugin.droppedLogLines() > 0 ? ", log lines dropped: " + plugin.droppedLogLines() : ""));
    }

    private void suite(CommandSender sender) {
        send(sender, "&bSentinel suite&7:");
        for (SuiteCompanion c : plugin.suite().companions()) {
            String state = plugin.suite().isInstalled(c) ? "&ainstalled"
                    : c.released() ? "&enot installed" : "&8planned";
            raw(sender, "&7" + c.pluginName() + " &8(" + c.role() + ") " + state);
            if (c.released() && !plugin.suite().isInstalled(c)) raw(sender, "  &8" + c.benefit() + " " + c.url());
        }
    }

    private void recent(CommandSender sender, String[] args) {
        int limit = 10;
        if (args.length > 1) {
            try {
                limit = Math.max(1, Math.min(50, Integer.parseInt(args[1])));
            } catch (NumberFormatException ignored) {
                // keep the default
            }
        }
        List<Finding> findings = plugin.recent().latest(limit);
        if (findings.isEmpty()) {
            send(sender, "&7No flags recorded since the server started.");
            return;
        }
        send(sender, "&7Latest " + findings.size() + " flag(s):");
        for (Finding f : findings) {
            raw(sender, "&8" + f.time().truncatedTo(java.time.temporal.ChronoUnit.SECONDS) + " &c" + f.playerName() + " &e" + f.type().id()
                    + " &7" + f.confidence() + "%" + (f.test() ? " &8[test]" : ""));
        }
    }

    private void testFlag(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&7Usage: /sad testflag <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, "&cPlayer not found online.");
            return;
        }
        plugin.reporter().report(target.getUniqueId(), target.getName(), DupeType.OTHER, 50,
                "test flag from " + sender.getName(), true);
        send(sender, "&7Test flag sent for " + target.getName() + ".");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                    .filter(s -> s.startsWith(typed) && sender.hasPermission("sentinelad." + s)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ledger")) return List.of("stats", "reset");
        if (args.length == 2 && args[0].equalsIgnoreCase("testflag") && sender.hasPermission("sentinelad.testflag")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        return List.of();
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(component(plugin.prefix() + text));
    }

    private void raw(CommandSender sender, String text) {
        sender.sendMessage(component(text));
    }

    private static Component component(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
