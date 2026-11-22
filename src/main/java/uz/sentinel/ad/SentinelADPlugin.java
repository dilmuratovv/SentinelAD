package uz.sentinel.ad;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import uz.sentinel.ad.ledger.CommandActivity;
import uz.sentinel.ad.ledger.LedgerService;
import uz.sentinel.ad.ledger.StackSessions;
import uz.sentinel.ad.protection.Seal;
import uz.sentinel.ad.protection.TransferProtection;
import uz.sentinel.ad.commands.SadCommand;
import uz.sentinel.ad.compat.ServerCompatibility;
import uz.sentinel.ad.diagnostics.AuditLog;
import uz.sentinel.ad.diagnostics.DupeReporter;
import uz.sentinel.ad.diagnostics.RecentFindings;
import uz.sentinel.ad.suite.SuiteCompanion;
import uz.sentinel.ad.suite.SuiteManager;

public final class SentinelADPlugin extends JavaPlugin {
    private final RecentFindings recent = new RecentFindings(200);
    private final SuiteManager suite = new SuiteManager();
    private volatile AuditLog auditLog;
    private volatile String prefix = "";
    private volatile boolean alertStaff = true;
    private volatile boolean alertConsole = true;
    private DupeReporter reporter;
    private Seal seal;
    private LedgerService ledger;
    private StackSessions stacks;

    @Override
    public void onEnable() {
        String version = Bukkit.getMinecraftVersion();
        if (!ServerCompatibility.isSupported(version)) {
            getLogger().severe("Minecraft " + version + " is not supported (needs 1.21 to 26.x). Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        applyConfig();
        if (getCommand("sad") == null) getLogger().warning("Command /sad is missing from plugin.yml.");

        reporter = new DupeReporter(this, recent, () -> auditLog, () -> prefix, () -> alertStaff, () -> alertConsole);

        seal = new Seal(this);
        getServer().getPluginManager().registerEvents(new TransferProtection(this, seal), this);
        CommandActivity commands = new CommandActivity();
        ledger = new LedgerService(this, seal, commands);
        stacks = new StackSessions(this, ledger.holders(), commands);
        ledger.start();
        stacks.applyConfig();
        getServer().getPluginManager().registerEvents(commands, this);
        getServer().getPluginManager().registerEvents(ledger, this);
        getServer().getPluginManager().registerEvents(stacks, this);
        // Old logs are otherwise only pruned at startup, which a long-running server never reaches.
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            AuditLog log = auditLog;
            if (log != null) log.cleanup(getConfig().getInt("logging.retention-days", 30));
        }, 20L * 60 * 60, 20L * 60 * 60 * 6);

        PluginCommand command = getCommand("sad");
        if (command != null) {
            SadCommand handler = new SadCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        getLogger().info("SentinelAD " + getPluginMeta().getVersion() + " enabled on Minecraft " + version + ".");
        announceSuite();
    }

    @Override
    public void onDisable() {
        if (ledger != null) ledger.stop();
        AuditLog log = auditLog;
        auditLog = null;
        if (log != null) log.close();
    }

    /** Re-reads config.yml. Called on enable and by {@code /sad reload}. */
    public void reloadSettings() {
        reloadConfig();
        applyConfig();
        if (ledger != null) ledger.applyConfig();
        if (stacks != null) stacks.applyConfig();
    }

    private void applyConfig() {
        // A config.yml written by an older version lacks newer keys; fall back to the bundled defaults.
        var bundled = getResource("config.yml");
        if (bundled != null) {
            getConfig().setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(bundled, StandardCharsets.UTF_8)));
        }
        prefix = getConfig().getString("messages.prefix", "&8[&bSentinelAD&8]&r ");
        alertStaff = getConfig().getBoolean("alerts.staff", true);
        alertConsole = getConfig().getBoolean("alerts.console", true);

        AuditLog previous = auditLog;
        if (getConfig().getBoolean("logging.enabled", true)) {
            if (previous == null) {
                AuditLog created = new AuditLog(getDataFolder().toPath().resolve("logs"), getLogger());
                created.cleanup(getConfig().getInt("logging.retention-days", 30));
                auditLog = created;
            }
        } else {
            auditLog = null;
            if (previous != null) previous.close();
        }
    }

    private void announceSuite() {
        if (!getConfig().getBoolean("suite.recommend-companions", true)) return;
        for (SuiteCompanion companion : suite.missing()) {
            getLogger().info("Tip: " + companion.pluginName() + " (" + companion.role() + ") is not installed. "
                    + companion.benefit() + " " + companion.url());
        }
    }

    public RecentFindings recent() { return recent; }
    public SuiteManager suite() { return suite; }
    public DupeReporter reporter() { return reporter; }
    public Seal seal() { return seal; }
    public LedgerService ledger() { return ledger; }
    public long droppedLogLines() { AuditLog log = auditLog; return log == null ? 0 : log.dropped(); }
    public String prefix() { return prefix; }
}
