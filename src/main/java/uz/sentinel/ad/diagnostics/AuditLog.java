package uz.sentinel.ad.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Daily rotating log of findings. Writes happen off the server thread through a bounded queue. */
public final class AuditLog implements AutoCloseable {
    private static final int QUEUE_SIZE = 4096;

    private final Path directory;
    private final Logger logger;
    private final AtomicLong dropped = new AtomicLong();
    private final ThreadPoolExecutor writer;

    public AuditLog(Path directory, Logger logger) {
        this.directory = directory;
        this.logger = logger;
        this.writer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE),
                runnable -> {
                    Thread thread = new Thread(runnable, "SentinelAD-AuditLog");
                    thread.setDaemon(true);
                    return thread;
                },
                (task, pool) -> dropped.incrementAndGet());
    }

    public void write(Finding finding) {
        String line = finding.toLogLine() + System.lineSeparator();
        writer.execute(() -> append(line));
    }

    /** Number of lines discarded because the queue was full. */
    public long dropped() {
        return dropped.get();
    }

    private void append(String line) {
        try {
            Files.createDirectories(directory);
            Path file = directory.resolve("dupe-" + LocalDate.now(ZoneOffset.UTC) + ".log");
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write the dupe log: " + e.getMessage());
        }
    }

    /** Deletes dupe-*.log files older than {@code days}. Does nothing for {@code days <= 0}. */
    public void cleanup(int days) {
        if (days <= 0 || !Files.isDirectory(directory)) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("dupe-") && name.endsWith(".log");
            }).forEach(p -> {
                try {
                    if (Files.getLastModifiedTime(p).toInstant().isBefore(cutoff)) Files.deleteIfExists(p);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "Could not delete old log " + p.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not scan the log directory: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(3, TimeUnit.SECONDS)) writer.shutdownNow();
        } catch (InterruptedException e) {
            writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
