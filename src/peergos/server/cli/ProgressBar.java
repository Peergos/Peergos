package peergos.server.cli;

import java.io.PrintWriter;
import java.nio.file.*;

public class ProgressBar {

    private static final String[] ANIM = new String[]{"|", "/", "-", "\\"};
    private static final int PROGRESS_BAR_LENGTH = 20;
    private final Path relativePath;
    private final String filename;
    private long accumulatedBytes;
    private int animationPosition;

    public ProgressBar(Path relativePath, String filename) {
        this.relativePath = relativePath;
        this.filename = filename;
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
        if (accumulatedBytes == 0)
            sb.append("\n");
        sb.append(relativePath.resolve(filename));
        sb.append(": ");
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
        ProgressBar pb = new ProgressBar(Paths.get("home"), "somefile");
        int size = 1000;
        PrintWriter writer = new PrintWriter(System.out);
        for (int i = 10; i <= size; i+=10) {
            pb.update(writer, 10, size);
            Thread.sleep(250);
        }
    }
}
