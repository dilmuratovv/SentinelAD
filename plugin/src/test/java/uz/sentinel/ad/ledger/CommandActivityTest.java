package uz.sentinel.ad.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class CommandActivityTest {
    private final UUID a = UUID.randomUUID(), b = UUID.randomUUID();

    @Test
    void labelStripsSlashNamespaceAndArguments() {
        assertEquals("give", CommandActivity.label("/minecraft:give Steve diamond 64"));
        assertEquals("kit", CommandActivity.label("/KIT tools"));
        assertEquals("give", CommandActivity.label("give Steve dirt"));
        assertEquals("item", CommandActivity.label("execute as Steve at @s run minecraft:item replace entity @s hotbar.1 from entity @s hotbar.0"));
        assertEquals("execute", CommandActivity.label("execute as Steve"));
    }

    @Test
    void ownItemCommandExplainsOnceThenTrustCoolsDown() {
        CommandActivity c = new CommandActivity();
        c.recordPlayer(a, "/kit pvp", 1_000);
        assertTrue(c.explains(a, 5_000));
        c.recordPlayer(a, "/kit pvp", 6_000);
        assertFalse(c.explains(a, 7_000), "spamming /kit must not keep removal off");
        c.recordPlayer(a, "/kit pvp", 1_000 + 11 * 60_000);
        assertTrue(c.explains(a, 1_000 + 11 * 60_000 + 500));
    }

    @Test
    void otherCommandsAndOtherPlayersDoNotExplain() {
        CommandActivity c = new CommandActivity();
        c.recordPlayer(a, "/spawn", 1_000);
        assertFalse(c.explains(a, 2_000));
        c.recordPlayer(b, "/give b diamond", 1_000);
        assertFalse(c.explains(a, 2_000));
        assertFalse(c.explains(null, 2_000));
    }

    @Test
    void windowExpires() {
        CommandActivity c = new CommandActivity();
        c.recordPlayer(a, "/give a diamond", 1_000);
        assertFalse(c.explains(a, 1_000 + 10_001));
    }

    @Test
    void consoleCommandExplainsEveryoneWithoutTrustLimit() {
        CommandActivity c = new CommandActivity();
        c.recordConsole("give a diamond 64", 1_000);
        assertTrue(c.explains(a, 2_000));
        assertTrue(c.explains(a, 3_000));
        assertTrue(c.explains(null, 3_000));
    }

    @Test
    void commandSinceSessionStartCountsBeyondWindow() {
        CommandActivity c = new CommandActivity();
        c.recordPlayer(a, "/kit tools", 1_000);
        assertTrue(c.explains(a, 500, 60_000), "kit taken while the chest was open");
        CommandActivity d = new CommandActivity();
        d.recordPlayer(a, "/kit tools", 1_000);
        assertFalse(d.explains(a, 2_000, 60_000), "kit before the chest opened");
    }

    @Test
    void configuredListAndDisable() {
        CommandActivity c = new CommandActivity();
        YamlConfiguration y = new YamlConfiguration();
        y.set("commands", java.util.List.of("/mykit"));
        c.configure(y);
        c.recordPlayer(a, "/give a x", 1_000);
        assertFalse(c.explains(a, 1_500));
        c.recordPlayer(a, "/mykit", 2_000);
        assertTrue(c.explains(a, 2_500));
        y.set("enabled", false);
        c.configure(y);
        c.recordConsole("give a x", 3_000);
        assertFalse(c.explains(a, 3_500));
    }
}
