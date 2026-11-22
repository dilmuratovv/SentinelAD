package uz.sentinel.ad.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LedgerStoreTest {
    private static List<Sighting> s(String... serials) {
        return java.util.Arrays.stream(serials).map(x -> new Sighting(x, 0, false)).toList();
    }

    @Test
    void firstSightingRegisters() {
        LedgerStore st = new LedgerStore();
        assertTrue(st.update("P:a", s("x"), 1).isEmpty());
        assertEquals("P:a", st.holderOf("x"));
    }

    @Test
    void sameSerialInTwoHoldersConflicts() {
        LedgerStore st = new LedgerStore();
        st.update("P:a", s("x"), 1);
        var c = st.update("B:w:0:0:0", s("x"), 2);
        assertEquals(1, c.size());
        assertEquals("P:a", c.get(0).previous());
        assertEquals("P:a", st.holderOf("x")); // the first holder stays the original
    }

    @Test
    void movingItemIsNotAConflict() {
        LedgerStore st = new LedgerStore();
        st.update("B:c", s("x"), 1);
        assertTrue(st.update("B:c", List.of(), 2).isEmpty()); // taken out: in transit
        assertNull(st.holderOf("x"));
        assertTrue(st.update("P:a", s("x"), 3).isEmpty());
        assertEquals("P:a", st.holderOf("x"));
    }

    @Test
    void twoCopiesInOneHolderConflict() {
        LedgerStore st = new LedgerStore();
        var c = st.update("P:a", s("x", "x"), 1);
        assertEquals(1, c.size());
        assertEquals("P:a", c.get(0).previous());
    }

    @Test
    void moveToAndForget() {
        LedgerStore st = new LedgerStore();
        st.update("P:a", s("x"), 1);
        st.moveTo("x", "P:b", 2);
        assertEquals("P:b", st.holderOf("x"));
        st.forget("x");
        assertFalse(st.known("x"));
        assertEquals(0, st.holders());
    }

    @Test
    void pruneRemovesOldEntries() {
        LedgerStore st = new LedgerStore();
        st.update("P:a", s("old"), 10);
        st.update("P:a", s("old", "new"), 10);
        st.update("P:a", s("new"), 10);
        st.moveTo("new", "P:a", 1000);
        assertEquals(1, st.prune(500));
        assertTrue(st.known("new"));
        assertFalse(st.known("old"));
    }

    @Test
    void persistenceRoundTripAndUncleanShutdown(@TempDir Path dir) throws Exception {
        LedgerStore a = new LedgerStore();
        a.update("P:a", s("x", "y"), 5);
        a.update("P:a", s("x"), 6);
        Path f = dir.resolve("ledger.dat");
        LedgerStore.write(f, a.snapshot(), true);
        LedgerStore b = new LedgerStore();
        assertTrue(b.load(f));
        assertEquals("P:a", b.holderOf("x"));
        assertNull(b.holderOf("y"));
        assertTrue(b.known("y"));

        LedgerStore.write(f, a.snapshot(), false);
        LedgerStore c = new LedgerStore();
        assertFalse(c.load(f));
        assertNull(c.holderOf("x"));
        assertTrue(c.known("x"));
    }

    @Test
    void worldTimesRoundTripAndOldFilesStillLoad(@TempDir Path dir) throws Exception {
        LedgerStore a = new LedgerStore();
        a.update("P:a", s("x"), 5);
        Path f = dir.resolve("ledger.dat");
        var times = new java.util.LinkedHashMap<String, Long>();
        times.put("11111111-1111-1111-1111-111111111111", 72_000L);
        times.put("22222222-2222-2222-2222-222222222222", 5L);
        LedgerStore.write(f, a.snapshot(), true, times);
        LedgerStore b = new LedgerStore();
        assertTrue(b.load(f), "clean flag still read with the world list in the header");
        assertEquals(times, b.savedWorldTimes());
        assertEquals("P:a", b.holderOf("x"));

        LedgerStore.write(f, a.snapshot(), true); // pre-1.2.0 header
        LedgerStore c = new LedgerStore();
        assertTrue(c.load(f));
        assertTrue(c.savedWorldTimes().isEmpty());
    }

    @Test
    void rollbackDetection() {
        assertFalse(LedgerStore.rolledBack(null, 0, 100), "unknown world");
        assertFalse(LedgerStore.rolledBack(1_000L, 1_000, 100), "clean restart");
        assertFalse(LedgerStore.rolledBack(1_000L, 950, 100), "within tolerance");
        assertFalse(LedgerStore.rolledBack(1_000L, 5_000, 100), "time moved on");
        assertTrue(LedgerStore.rolledBack(72_000L, 1_000, 100), "restored from an older backup");
    }
}
