package peergos.server.util;

import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Generates thumbnails with ffmpeg in a child process.
 *
 *  ffmpeg is linked in to the process that calls it, so an assertion it fails on a file it can't
 *  parse takes that whole process down. Keeping it out here costs one long lived helper JVM and
 *  turns a dead server into a missing thumbnail.
 */
public class ThumbnailerHost implements ThumbnailGenerator.Generator, ThumbnailGenerator.VideoGenerator {

    private static final int SIZE = 400;
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000;

    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "thumbnailer-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final List<String> command;
    private final long timeoutMillis;
    private Process process;
    private BufferedWriter toWorker;
    private BufferedReader fromWorker;

    public ThumbnailerHost(List<String> command) {
        this(command, DEFAULT_TIMEOUT_MILLIS);
    }

    public ThumbnailerHost(List<String> command, long timeoutMillis) {
        this.command = command;
        this.timeoutMillis = timeoutMillis;
    }

    /** Returns a thumbnailer if a worker starts and has the native lib, otherwise empty. */
    public static Optional<ThumbnailerHost> create() {
        ThumbnailerHost host = new ThumbnailerHost(workerCommand());
        if (host.probe())
            return Optional.of(host);
        host.stop();
        return Optional.empty();
    }

    private static List<String> workerCommand() {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", classpath(), ThumbnailerWorker.class.getName());
    }

    /** Absolute, because the worker is started from wherever the server happens to be running. */
    private static String classpath() {
        return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(e -> Path.of(e).toAbsolutePath().toString())
                .collect(Collectors.joining(File.pathSeparator));
    }

    private synchronized boolean probe() {
        return "ok".equals(request("probe"));
    }

    public synchronized void stop() {
        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    private void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        // ffmpeg's complaints are worth seeing, and it writes them to stderr
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        process = pb.start();
        toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    /** Sends one request, restarting the worker if it isn't there or doesn't answer as expected. */
    private synchronized String request(String line) {
        try {
            if (process == null || ! process.isAlive())
                start();
            toWorker.write(line);
            toWorker.newLine();
            toWorker.flush();
            Process current = process;
            ScheduledFuture<?> timeout = watchdog.schedule(current::destroyForcibly,
                    timeoutMillis, TimeUnit.MILLISECONDS);
            try {
                String result = fromWorker.readLine();
                if ("ok".equals(result) || "fail".equals(result))
                    return result;
                // it died mid request, or something wrote over our channel: start again next time
                stop();
                return "fail";
            } finally {
                timeout.cancel(false);
            }
        } catch (IOException e) {
            stop();
            return "fail";
        }
    }

    static String extensionFor(byte[] data) {
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8)
            return ".jpg";
        if (data.length >= 4 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F')
            return ".webp";
        if (data.length >= 8 && data[0] == (byte)0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G')
            return ".png";
        if (data.length >= 4 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F')
            return ".gif";
        if (data.length >= 4 && (data[0] & 0xFF) == 0x49 && data[1] == 0x49 && data[2] == 0x2A)
            return ".tiff";
        if (data.length >= 4 && (data[0] & 0xFF) == 0x4D && data[1] == 0x4D && data[2] == 0x00)
            return ".tiff";
        if (data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p'
                && (   (data[8] == 'a' && data[9] == 'v' && data[10] == 'i' && data[11] == 'f')
                || (data[8] == 'a' && data[9] == 'v' && data[10] == 'i' && data[11] == 's')))
            return ".avif";
        return ".img";
    }

    private Optional<Thumbnail> generate(String mode, Path input) {
        Path output = null;
        try {
            output = Files.createTempFile("peergos-thumb-", ".webp");
            Base64.Encoder base64 = Base64.getEncoder();
            String result = request(mode
                    + " " + base64.encodeToString(input.toAbsolutePath().toString().getBytes())
                    + " " + base64.encodeToString(output.toAbsolutePath().toString().getBytes())
                    + " " + SIZE);
            if (! "ok".equals(result))
                return Optional.empty();
            byte[] webp = Files.readAllBytes(output);
            return webp.length == 0 ? Optional.empty() : Optional.of(new Thumbnail("image/webp", webp));
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public Optional<Thumbnail> generateThumbnail(byte[] data) {
        Path tmp = null;
        try {
            // ffmpeg relies on file extension
            tmp = Files.createTempFile("peergos-thumb-in-", extensionFor(data));
            Files.write(tmp, data);
            return generate("image", tmp);
        } catch (IOException e) {
            return Optional.empty();
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (IOException ignored) {}
            }
        }
    }

    @Override
    public Optional<Thumbnail> generateVideoThumbnail(File video) {
        return generate("video", video.toPath());
    }
}
