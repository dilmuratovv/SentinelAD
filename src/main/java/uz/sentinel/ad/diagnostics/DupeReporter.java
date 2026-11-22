package uz.sentinel.ad.diagnostics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import uz.sentinel.ad.api.DupeFlagEvent;
import uz.sentinel.ad.api.DupeType;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Single entry point for every detector: records the finding, logs it, alerts staff and fires
 * {@link DupeFlagEvent}. Safe to call from any thread; the work is moved to the main thread.
 */
public final class DupeReporter {
    private final Plugin plugin;
    private final RecentFindings recent;
    private final Supplier<AuditLog> log;
    private final Supplier<String> prefix;
    private final Supplier<Boolean> alertStaff;
    private final Supplier<Boolean> alertConsole;
    private final Escalation escalation = new Escalation();

    public DupeReporter(Plugin plugin, RecentFindings recent, Supplier<AuditLog> log, Supplier<String> prefix,
                        Supplier<Boolean> alertStaff, Supplier<Boolean> alertConsole) {
        this.plugin = plugin;
        this.recent = recent;
        this.log = log;
        this.prefix = prefix;
        this.alertStaff = alertStaff;
        this.alertConsole = alertConsole;
    }

    public void report(UUID playerId, String playerName, DupeType type, int confidence, String detail, boolean test) {
        Finding finding = new Finding(Instant.now(), playerId, playerName, type,
                Math.max(0, Math.min(100, confidence)), detail, test);
        if (Bukkit.isPrimaryThread()) {
            handle(finding);
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> handle(finding));
        }
    }

    /** A finding with no player to blame (e.g. a duplicate found in a container). Logged and alerted, no event fired. */
    public void reportUnattributed(DupeType type, int confidence, String detail) {
        Finding finding = new Finding(Instant.now(), new UUID(0, 0), "[world]", type,
                Math.max(0, Math.min(100, confidence)), detail, false);
        if (Bukkit.isPrimaryThread()) handle(finding, false);
        else if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, () -> handle(finding, false));
    }

    private void handle(Finding f) { handle(f, true); }

    private void handle(Finding f, boolean fireEvent) {
        recent.add(f);
        AuditLog audit = log.get();
        if (audit != null) audit.write(f);

        String text = prefix.get() + "&c" + f.playerName() + "&7 flagged &e" + f.type().id()
                + "&7 (" + f.confidence() + "%)" + (f.test() ? " &8[test]" : "") + "&7: " + f.detail();
        Component message = LegacyComponentSerializer.legacyAmpersand().deserialize(text);
        if (alertConsole.get()) Bukkit.getConsoleSender().sendMessage(message);
        if (alertStaff.get()) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission("sentinelad.alerts")) online.sendMessage(message);
            }
        }
        if (fireEvent) Bukkit.getPluginManager().callEvent(new DupeFlagEvent(
                f.playerId(), f.playerName(), f.type(), f.confidence(), f.detail(), f.test()));
        if (fireEvent && !f.test()) escalate(f);
    }

    /** Repeat offenders: after N serious findings inside the window, run the configured console commands. */
    private void escalate(Finding f) {
        var config = plugin.getConfig();
        if (!config.getBoolean("escalation.enabled", false) || f.confidence() < config.getInt("escalation.min-confidence", 90)) return;
        long window = Math.max(1, config.getLong("escalation.window-minutes", 30)) * 60_000L;
        if (!escalation.record(f.playerId(), System.currentTimeMillis(), config.getInt("escalation.threshold", 3), window)) return;
        // Names come from the server, but keep the placeholder strictly alphanumeric so it can never inject arguments.
        String name = f.playerName().replaceAll("[^A-Za-z0-9_.]", "");
        for (String command : config.getStringList("escalation.commands")) {
            String line = command.replace("%player%", name).replace("%type%", f.type().id());
            plugin.getLogger().info("Escalation for " + name + ": /" + line);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line);
        }
    }
}
