package peergos.server.sync;

import peergos.shared.crypto.hash.Hash;
import peergos.shared.util.ArrayOps;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class PairLogger {
    public static final String SYNC_LOGS_SUBDIR = "sync-logs";
    public static final long MAX_LOG_BYTES = 1024L * 1024L;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public final String pairHash;
    private final Path current;
    private final Path rotated;
    private final Object lock = new Object();

    public PairLogger(Path peergosDir, String pairHash) throws IOException {
        this.pairHash = pairHash;
        Path dir = logDir(peergosDir);
        Files.createDirectories(dir);
        this.current = currentLogPath(peergosDir, pairHash);
        this.rotated = rotatedLogPath(peergosDir, pairHash);
    }

    public static String hash(String linkPath, String localDir) {
        return ArrayOps.bytesToHex(Hash.sha256(linkPath + "///" + localDir));
    }

    public static Path logDir(Path peergosDir) {
        return peergosDir.resolve(SYNC_LOGS_SUBDIR);
    }

    public static Path currentLogPath(Path peergosDir, String pairHash) {
        return logDir(peergosDir).resolve("sync-" + pairHash + ".log");
    }

    public static Path rotatedLogPath(Path peergosDir, String pairHash) {
        return logDir(peergosDir).resolve("sync-" + pairHash + ".log.1");
    }

    public void log(String msg) {
        if (msg == null)
            return;
        synchronized (lock) {
            try {
                byte[] line = ("[" + LocalDateTime.now().format(TS) + "] " + msg + "\n").getBytes(StandardCharsets.UTF_8);
                long currentSize = Files.exists(current) ? Files.size(current) : 0;
                if (currentSize > 0 && currentSize + line.length > MAX_LOG_BYTES)
                    Files.move(current, rotated, StandardCopyOption.REPLACE_EXISTING);
                Files.write(current, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                System.err.println("Failed to write sync log " + current + ": " + e.getMessage());
            }
        }
    }

    public void error(String msg) {
        log("ERROR: " + msg);
    }

    public static byte[] readCombined(Path peergosDir, String pairHash) throws IOException {
        Path rotated = rotatedLogPath(peergosDir, pairHash);
        Path current = currentLogPath(peergosDir, pairHash);
        byte[] r = Files.exists(rotated) ? Files.readAllBytes(rotated) : new byte[0];
        byte[] c = Files.exists(current) ? Files.readAllBytes(current) : new byte[0];
        byte[] out = new byte[r.length + c.length];
        System.arraycopy(r, 0, out, 0, r.length);
        System.arraycopy(c, 0, out, r.length, c.length);
        return out;
    }

    public static void deleteFor(Path peergosDir, String pairHash) throws IOException {
        Files.deleteIfExists(currentLogPath(peergosDir, pairHash));
        Files.deleteIfExists(rotatedLogPath(peergosDir, pairHash));
    }
}
