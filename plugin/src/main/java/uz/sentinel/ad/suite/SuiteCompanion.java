package uz.sentinel.ad.suite;

/**
 * A plugin of the Sentinel suite that SentinelAD knows about.
 *
 * @param pluginName exact Bukkit plugin name
 * @param role       short role, e.g. "anticheat"
 * @param benefit    one line telling the admin why to install it next to SentinelAD
 * @param url        project page
 * @param released   false for plugins that are planned but not published yet (never recommended)
 */
public record SuiteCompanion(String pluginName, String role, String benefit, String url, boolean released) {}
