package uz.sentinel.ad.diagnostics;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.sentinel.ad.api.DupeType;

class DiagnosticsTest {
    private static Finding finding(String detail) {
        return new Finding(Instant.parse("2026-11-16T00:00:00Z"), UUID.randomUUID(), "Steve",
                DupeType.UUID_DUPLICATE, 100, detail, false);
    }

    @Test
    void typeIdsAreStableKebabCase() {
        assertEquals("uuid-duplicate", DupeType.UUID_DUPLICATE.id());
        assertEquals("stack-oversize", DupeType.STACK_OVERSIZE.id());
    }

    @Test
    void logLineIsSingleLine() {
        String line = finding("a\nb\rc").toLogLine();
        assertFalse(line.contains("\n") || line.contains("\r"));
        assertTrue(line.contains("type=uuid-duplicate") && line.contains("player=Steve"));
    }

    @Test
    void recentKeepsNewestFirstAndIsBounded() {
        RecentFindings recent = new RecentFindings(3);
        for (int i = 0; i < 5; i++) recent.add(finding("n" + i));
        assertEquals(3, recent.size());
        var latest = recent.latest(10);
        assertEquals("n4", latest.get(0).detail());
        assertEquals("n2", latest.get(2).detail());
        assertEquals(1, recent.latest(1).size());
    }

    @Test
    void auditLogWritesAndPrunesOldFiles(@TempDir Path dir) throws Exception {
        Path logs = dir.resolve("logs");
        Files.createDirectories(logs);
        Path old = Files.writeString(logs.resolve("dupe-2020-01-01.log"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(90))));
        Path other = Files.writeString(logs.resolve("keep.txt"), "x");
        Files.setLastModifiedTime(other, FileTime.from(Instant.now().minus(Duration.ofDays(90))));

        AuditLog log = new AuditLog(logs, Logger.getAnonymousLogger());
        log.write(finding("hello"));
        log.cleanup(30);
        log.close();

        assertFalse(Files.exists(old));
        assertTrue(Files.exists(other));
        try (var files = Files.list(logs)) {
            var today = files.filter(p -> p.getFileName().toString().startsWith("dupe-") && !p.equals(old)).toList();
            assertEquals(1, today.size());
            assertTrue(Files.readString(today.get(0)).contains("detail=hello"));
        }
        assertEquals(0, log.dropped());
    }

    @Test
    void cleanupDisabledWithZeroDays(@TempDir Path dir) throws Exception {
        Path old = Files.writeString(dir.resolve("dupe-2020-01-01.log"), "x");
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(Duration.ofDays(900))));
        AuditLog log = new AuditLog(dir, Logger.getAnonymousLogger());
        log.cleanup(0);
        log.close();
        assertTrue(Files.exists(old));
    }
}
