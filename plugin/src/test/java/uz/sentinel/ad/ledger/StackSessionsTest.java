package uz.sentinel.ad.ledger;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.Test;

class StackSessionsTest {
    @Test
    void realContainersAreWatched() {
        for (InventoryType t : new InventoryType[]{InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
                InventoryType.HOPPER, InventoryType.DROPPER, InventoryType.DISPENSER}) {
            assertTrue(StackSessions.allowed(t, true), t.name());
        }
        assertTrue(StackSessions.allowed(InventoryType.ENDER_CHEST, false), "ender chest has no world location");
    }

    @Test
    void pluginMenusAreIgnored() {
        // Shop / auction / crate GUIs are CHEST inventories without a location; they hand out items by design.
        assertFalse(StackSessions.allowed(InventoryType.CHEST, false));
        assertFalse(StackSessions.allowed(InventoryType.HOPPER, false));
        assertFalse(StackSessions.allowed(InventoryType.DISPENSER, false));
    }

    @Test
    void otherInventoriesAreIgnored() {
        assertFalse(StackSessions.allowed(InventoryType.FURNACE, true));
        assertFalse(StackSessions.allowed(InventoryType.WORKBENCH, true));
        assertFalse(StackSessions.allowed(InventoryType.MERCHANT, false));
    }
}
