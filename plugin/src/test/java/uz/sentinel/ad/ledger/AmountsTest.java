package uz.sentinel.ad.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AmountsTest {
    @Test
    void gainsOnlyCountGrowthAboveTheMinimum() {
        Map<String, Integer> before = Map.of("DIAMOND", 64, "EMERALD", 10);
        Map<String, Integer> after = Map.of("DIAMOND", 128, "EMERALD", 9, "NETHERITE_INGOT", 2);
        assertEquals(Map.of("DIAMOND", 64, "NETHERITE_INGOT", 2), Amounts.gains(before, after, 1));
        assertEquals(Map.of("DIAMOND", 64), Amounts.gains(before, after, 3));
        assertTrue(Amounts.gains(after, before, 1).keySet().contains("EMERALD"));
    }

    @Test
    void movingItemsBetweenPartsOfAChunkIsNoGain() {
        // A rollback restores chest and ground together: 10 diamonds that moved from the chest to the ground.
        Map<String, Integer> blocksBefore = Map.of("DIAMOND", 10), entitiesBefore = Map.of();
        Map<String, Integer> blocksNow = Map.of(), entitiesNow = Map.of("DIAMOND", 10);
        assertTrue(Amounts.gains(Amounts.sum(blocksBefore, entitiesBefore), Amounts.sum(blocksNow, entitiesNow), 1).isEmpty());
    }

    @Test
    void textRoundTrip() {
        Map<String, Integer> m = Map.of("DIAMOND", 64, "TOTEM_OF_UNDYING", 3);
        assertEquals("DIAMOND=64,TOTEM_OF_UNDYING=3", Amounts.format(m));
        assertEquals(m, Amounts.parse(Amounts.format(m)));
        assertEquals("-", Amounts.format(Map.of()));
        assertTrue(Amounts.parse("-").isEmpty());
        assertEquals(Map.of("DIAMOND", 1), Amounts.parse("DIAMOND=1,BROKEN,X=y"));
        UUID id = UUID.randomUUID();
        assertEquals(Map.of(id, 42L), Amounts.parseTimes(Amounts.formatTimes(Map.of(id, 42L))));
        assertEquals("+64 diamond, +3 totem_of_undying", Amounts.describe(new java.util.TreeMap<>(m)));
    }
}
