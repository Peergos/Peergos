package peergos.server.cli;

import java.io.PrintWriter;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import java.util.stream.*;

public class ProgressBar {

    private static final String[] ANIM = new String[]{"|", "/", "-", "\\"};
    private static final int PROGRESS_BAR_LENGTH = 20;
    private final AtomicLong totalFiles;
    private final AtomicLong currentFile;
    private final Path relativePath;
    private final String filename;
    private long accumulatedBytes;
    private int animationPosition;

    public ProgressBar(AtomicLong currentFile, AtomicLong totalFiles, Path relativePath, String filename) {
        this.totalFiles = totalFiles;
        this.currentFile = currentFile;
        this.relativePath = relativePath;
        this.filename = filename;
    }

    private String prefix() {
        return "(" + currentFile + "/" + totalFiles + ") ";
    }

    public void update(PrintWriter writer, long bytesSoFar, long totalBytes) {
        String msg = format(bytesSoFar, totalBytes);
        writer.print(msg);
        writer.flush();
    }

    private String progressBar(long bytes, long  total) {
        accumulatedBytes += bytes;
        int barsProgressed = (int) (accumulatedBytes * PROGRESS_BAR_LENGTH / total);

        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < PROGRESS_BAR_LENGTH; i++) {
            if (i < barsProgressed)
                sb.append('=');
            else
                sb.append(' ');
        }
        sb.append(']');
        return sb.toString();
    }

    private String format(long bytes, long total) {
        StringBuilder sb =  new StringBuilder("\r");
        if (accumulatedBytes == 0) {
            sb.append("\n");
            currentFile.incrementAndGet();
        }
        sb.append(prefix());
        String path = relativePath.resolve(filename).toString();
        sb.append(path);
        int alignChars = 52;
        int startSize = prefix().length() + path.length();
        if (startSize < alignChars)
            sb.append(IntStream.range(0, alignChars - startSize).mapToObj(i -> " ").collect(Collectors.joining()));
        sb.append(updateAndGetAnimation());
        sb.append("\t");
        sb.append(progressBar(bytes, total));
        sb.append("\t");
        return sb.toString();

    }

    private String updateAndGetAnimation() {
        return ANIM[animationPosition++ % ANIM.length];
    }

    public static void main(String[] args) throws Exception {
        ProgressBar pb = new ProgressBar(new AtomicLong(0), new AtomicLong(1), Paths.get("/home"), "somefile");
        int size = 1000;
        PrintWriter writer = new PrintWriter(System.out);
        for (int i = 10; i <= size; i+=10) {
            pb.update(writer, 10, size);
            Thread.sleep(250);
        }
    }
}
