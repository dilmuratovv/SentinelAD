package uz.sentinel.ad.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class EscalationTest {
    private static final long WINDOW = 60_000;

    @Test
    void triggersAtThresholdThenRestarts() {
        Escalation e = new Escalation();
        UUID p = UUID.randomUUID();
        assertFalse(e.record(p, 0, 3, WINDOW));
        assertFalse(e.record(p, 1000, 3, WINDOW));
        assertTrue(e.record(p, 2000, 3, WINDOW));
        assertFalse(e.record(p, 3000, 3, WINDOW), "counter restarts after a trigger");
    }

    @Test
    void oldHitsExpire() {
        Escalation e = new Escalation();
        UUID p = UUID.randomUUID();
        e.record(p, 0, 2, WINDOW);
        assertFalse(e.record(p, WINDOW + 1, 2, WINDOW));
        assertTrue(e.record(p, WINDOW + 2, 2, WINDOW));
    }

    @Test
    void playersAreIndependent() {
        Escalation e = new Escalation();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        e.record(a, 0, 2, WINDOW);
        assertFalse(e.record(b, 1, 2, WINDOW));
        assertTrue(e.record(a, 2, 2, WINDOW));
    }

    @Test
    void thresholdOneTriggersImmediately() {
        assertTrue(new Escalation().record(UUID.randomUUID(), 0, 1, WINDOW));
    }
}
