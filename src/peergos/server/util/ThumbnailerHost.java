package peergos.server.util;

import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;
import java.util.stream.*;

/** Generates thumbnails with ffmpeg in a child process.
 *
 *  ffmpeg is linked in to the process that calls it, so an assertion it fails on a file it can't
 *  parse takes that whole process down. Keeping it out here costs one long lived helper JVM and
 *  turns a dead server into a missing thumbnail.
 */
public class ThumbnailerHost implements ThumbnailGenerator.Generator, ThumbnailGenerator.VideoGenerator {

    private static final Logger LOG = Logging.LOG();

    private static final int SIZE = 400;
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000;

    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "thumbnailer-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final List<String> command;
    private final long timeoutMillis;
    private final Optional<Path> errorLog;
    private Process process;
    private BufferedWriter toWorker;
    private BufferedReader fromWorker;

    public ThumbnailerHost(List<String> command) {
        this(command, DEFAULT_TIMEOUT_MILLIS, Optional.empty());
    }

    public ThumbnailerHost(List<String> command, long timeoutMillis) {
        this(command, timeoutMillis, Optional.empty());
    }

    public ThumbnailerHost(List<String> command, long timeoutMillis, Optional<Path> errorLog) {
        this.command = command;
        this.timeoutMillis = timeoutMillis;
        this.errorLog = errorLog;
    }

    /** Returns a thumbnailer if a worker starts and has the native lib, otherwise empty.
     *
     * @param errorLog where to keep what the worker says about the files it can't read. ffmpeg says it
     *                 on stderr, which a windowed app has nowhere to put, so a file of our own it is.
     */
    public static Optional<ThumbnailerHost> create(Optional<Path> errorLog) {
        ThumbnailerHost host = new ThumbnailerHost(workerCommand(), DEFAULT_TIMEOUT_MILLIS, errorLog);
        if (host.probe())
            return Optional.of(host);
        host.stop();
        return Optional.empty();
    }

    private static List<String> workerCommand() {
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java");
        if (java.toFile().exists())
            return List.of(java.toString(), "-cp", classpath(), ThumbnailerWorker.class.getName());
        // A packaged app can ship a runtime with no java command. Our own launcher is then the only
        // thing that can start a jvm here, and it hands over to the worker on the environment below.
        return List.of(ProcessHandle.current().info().command()
                .orElseThrow(() -> new IllegalStateException("Nothing to start a thumbnailer worker with")));
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

    /** Waits for the worker to actually go: windows holds its handles - on the log it was appending to
     *  among others - until it does, and a worker that is still around is one the next request will
     *  mistake for a live one.
     */
    public synchronized void stop() {
        if (process != null) {
            try {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            process = null;
        }
    }

    private void start() throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put(ThumbnailerWorker.WORKER_ENV, "1");
        // ffmpeg's complaints are worth keeping, and it writes them to stderr
        pb.redirectError(errorLog.map(f -> ProcessBuilder.Redirect.appendTo(f.toFile()))
                .orElse(ProcessBuilder.Redirect.INHERIT));
        process = pb.start();
        toWorker = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
        fromWorker = new BufferedReader(new InputStreamReader(process.getInputStream()));
    }

    /** Sends one request, restarting the worker if it isn't there or its pipes have gone.
     *
     *  A worker that ffmpeg aborted can linger while windows deals with the crash, so isAlive() is not
     *  enough to know we still have one: a write that fails means the last request killed it, and this
     *  one is owed a fresh worker rather than the failure that belonged to the previous file.
     */
    private synchronized String request(String line) {
        try {
            if (process == null || ! process.isAlive())
                start();
            return exchange(line);
        } catch (IOException brokenWorker) {
            LOG.info("Restarting the thumbnailer after: " + brokenWorker);
        }
        stop();
        try {
            start();
            return exchange(line);
        } catch (IOException couldNotStart) {
            LOG.log(Level.WARNING, "Could not start a thumbnailer worker", couldNotStart);
            stop();
            return "fail";
        }
    }

    /** One request on the worker we have. A file that kills it is not retried - it would only kill the
     *  next one too - so only a broken pipe comes back as an exception.
     */
    private String exchange(String line) throws IOException {
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
            // it died on this file, or something wrote over our channel: start again next time
            stop();
            return "fail";
        } finally {
            timeout.cancel(false);
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
