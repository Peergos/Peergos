package peergos.server.sync;

import peergos.shared.io.ipfs.api.JSONParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class PairStatus {
    private static final DateTimeFormatter TS = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public final String pairHash;
    private final Path file;
    private String status;
    private LocalDateTime updateTime;
    private Optional<String> error = Optional.empty();

    public PairStatus(Path peergosDir, String pairHash) {
        this.pairHash = pairHash;
        this.file = statusPath(peergosDir, pairHash);
        loadFromDisk();
    }

    public static Path statusPath(Path peergosDir, String pairHash) {
        return PairLogger.logDir(peergosDir).resolve("sync-" + pairHash + ".status.json");
    }

    public static void deleteFor(Path peergosDir, String pairHash) throws IOException {
        Files.deleteIfExists(statusPath(peergosDir, pairHash));
    }

    private synchronized void loadFromDisk() {
        if (! Files.exists(file))
            return;
        try {
            Map<String, Object> json = (Map<String, Object>) JSONParser.parse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
            Object m = json.get("msg");
            status = m == null ? null : m.toString();
            Object t = json.get("time");
            updateTime = t == null ? null : LocalDateTime.parse(t.toString(), TS);
            Object e = json.get("error");
            error = e == null || e.toString().isEmpty() ? Optional.empty() : Optional.of(e.toString());
        } catch (Exception ex) {
            // Corrupt or unreadable status file — start fresh, don't crash sync.
            System.err.println("Failed to load sync status " + file + ": " + ex.getMessage());
        }
    }

    public synchronized void setStatus(String newStatus) {
        status = newStatus;
        updateTime = LocalDateTime.now();
        persist();
    }

    public synchronized void setError(String err) {
        error = err == null || err.isEmpty() ? Optional.empty() : Optional.of(err);
        persist();
    }

    public synchronized String getStatusAndTime() {
        if (status == null)
            return "";
        return status + " at " + updateTime.toLocalDate() + " " + updateTime.toLocalTime().withNano(0);
    }

    public synchronized String getMessage() {
        return status == null ? "" : status;
    }

    public synchronized Optional<String> getError() {
        return error;
    }

    public synchronized Optional<LocalDateTime> getTime() {
        return Optional.ofNullable(updateTime);
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            LinkedHashMap<String, Object> json = new LinkedHashMap<>();
            json.put("msg", status == null ? "" : status);
            json.put("error", error.orElse(""));
            json.put("time", updateTime == null ? "" : updateTime.format(TS));
            byte[] bytes = JSONParser.toString(json).getBytes(StandardCharsets.UTF_8);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Failed to persist sync status " + file + ": " + e.getMessage());
        }
    }
}
