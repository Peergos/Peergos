package peergos.server.util;

import org.peergos.thumbnailer.VideoThumbnailer;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** The child process side of {@link ThumbnailerHost}.
 *
 *  ffmpeg is linked into whichever process calls it, and one of its assertions failing on a file it
 *  can't parse aborts that process. This main exists so that process is never the server.
 *
 *  One request per line on stdin: "mode input output size", the paths base64 encoded. The thumbnail
 *  is written to the output path and a single "ok" or "fail" line comes back, so nothing the native
 *  code writes to our stdout can be mistaken for image data.
 */
public class ThumbnailerWorker {

    /** Set on a worker started through the app launcher, whose main class is the server's. */
    public static final String WORKER_ENV = "PEERGOS_THUMBNAILER_WORKER";

    public static boolean isWorker() {
        return "1".equals(System.getenv(WORKER_ENV));
    }

    public static void main(String[] args) throws Exception {
        PrintStream results = new PrintStream(new FileOutputStream(FileDescriptor.out), true);
        // anything else that fancies printing goes to stderr rather than into the protocol
        System.setOut(System.err);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        Base64.Decoder base64 = Base64.getDecoder();
        String line;
        while ((line = in.readLine()) != null) {
            String[] parts = line.split(" ");
            String mode = parts[0];
            if (mode.equals("probe")) {
                results.println(VideoThumbnailer.isAvailable() ? "ok" : "fail");
                continue;
            }
            File input = new File(new String(base64.decode(parts[1])));
            Path output = Path.of(new String(base64.decode(parts[2])));
            int size = Integer.parseInt(parts[3]);
            Optional<byte[]> thumbnail;
            try {
                thumbnail = mode.equals("video") ?
                        VideoThumbnailer.generateWebP(input, size) :
                        VideoThumbnailer.generateImageWebP(input, size);
            } catch (Throwable t) {
                thumbnail = Optional.empty();
            }
            if (thumbnail.isPresent())
                Files.write(output, thumbnail.get());
            results.println(thumbnail.isPresent() ? "ok" : "fail");
        }
    }
}
