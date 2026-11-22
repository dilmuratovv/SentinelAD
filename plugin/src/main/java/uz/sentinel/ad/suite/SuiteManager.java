package uz.sentinel.ad.suite;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.List;

/** Detects the other Sentinel plugins and lists the ones worth installing. */
public final class SuiteManager {
    private static final List<SuiteCompanion> COMPANIONS = List.of(
            new SuiteCompanion("SentinelAC", "anticheat",
                    "Movement, combat and packet checks next to dupe protection.",
                    "https://github.com/dilmuratovv/SentinelAC", true),
            new SuiteCompanion("SentinelGuard", "moderation",
                    "Adds players flagged by SentinelAD to /sus and gives you bans, mutes and history.",
                    "https://github.com/dilmuratovv/SentinelGuard", true),
            new SuiteCompanion("SentinelAX-ray", "anti-xray",
                    "Native Paper ore concealment.", "https://github.com/dilmuratovv/SentinelAX-ray", true),
            new SuiteCompanion("SentinelAE", "anti-ESP",
                    "Per-viewer player and entity concealment.", "https://github.com/dilmuratovv/SentinelAE", true));

    public List<SuiteCompanion> companions() {
        return COMPANIONS;
    }

    public boolean isInstalled(SuiteCompanion companion) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(companion.pluginName());
        return plugin != null && plugin.isEnabled();
    }

    /**
     * Released companions that are not installed. Checks presence, not "enabled": at SentinelAD's startup a companion
     * that loads later (SentinelGuard) is installed but not enabled yet.
     */
    public List<SuiteCompanion> missing() {
        return COMPANIONS.stream()
                .filter(c -> c.released() && Bukkit.getPluginManager().getPlugin(c.pluginName()) == null).toList();
    }
}
