package peergos.server.tests;

import org.junit.*;
import peergos.server.user.*;
import peergos.server.util.*;
import peergos.shared.user.fs.*;

import javax.imageio.*;
import java.awt.image.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class ThumbnailerHostTests {

    private static List<String> command(Class<?> worker, String... args) {
        List<String> command = new ArrayList<>(List.of(System.getProperty("java.home") + File.separator + "bin"
                        + File.separator + (System.getProperty("os.name").toLowerCase().contains("windows") ? "java.exe" : "java"),
                "-cp", System.getProperty("java.class.path"), worker.getName()));
        command.addAll(List.of(args));
        return command;
    }

    @Test
    public void imagesAreThumbnailedInAnotherProcess() throws Exception {
        Optional<ThumbnailerHost> host = ThumbnailerHost.create(Optional.empty());
        Assume.assumeTrue("native thumbnailer available", host.isPresent());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", bout);

        Optional<Thumbnail> thumbnail = host.get().generateThumbnail(bout.toByteArray());

        Assert.assertTrue(thumbnail.isPresent());
        Assert.assertEquals("image/webp", thumbnail.get().mimeType);
        host.get().stop();
    }

    /** A jpackage runtime can have no java command, leaving the app's own launcher to start the
     *  worker: whatever it is, it comes up in peergos.server.Main. */
    @Test
    public void theServerLauncherCanBeTheWorker() throws Exception {
        Assume.assumeTrue("native thumbnailer available", ThumbnailerHost.create(Optional.empty()).map(h -> {
            h.stop();
            return true;
        }).orElse(false));
        ThumbnailerHost host = new ThumbnailerHost(command(peergos.server.Main.class));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", bout);

        Assert.assertTrue(host.generateThumbnail(bout.toByteArray()).isPresent());
        host.stop();
    }

    /** ffmpeg aborts the process it is linked into, which is what put it in a process of its own. */
    @Test
    public void aWorkerCrashLosesOneThumbnailNotTheServer() throws Exception {
        Path marker = Files.createTempFile("thumb-crash-", ".marker");
        Files.delete(marker);
        ThumbnailerHost host = new ThumbnailerHost(command(CrashingWorker.class, marker.toString()), 10_000);

        Assert.assertEquals(Optional.empty(), host.generateThumbnail(new byte[]{1, 2, 3}));
        // and the next request restarts it
        Assert.assertTrue(host.generateThumbnail(new byte[]{1, 2, 3}).isPresent());
        host.stop();
    }

    @Test
    public void aHungWorkerTimesOut() throws Exception {
        ThumbnailerHost host = new ThumbnailerHost(command(HangingWorker.class), 1_000);

        Assert.assertEquals(Optional.empty(), host.generateThumbnail(new byte[]{1, 2, 3}));
        host.stop();
    }

    /** The file after a crash used to fail too: the dead worker can still look alive, so the write
     *  went down a pipe with nothing on the other end. */
    @Test
    public void theFileAfterACrashGetsAFreshWorker() throws Exception {
        ThumbnailerHost host = new ThumbnailerHost(command(StdinClosingWorker.class), 10_000);

        Assert.assertTrue("first file", host.generateThumbnail(new byte[]{1, 2, 3}).isPresent());
        Assert.assertTrue("the file after it", host.generateThumbnail(new byte[]{1, 2, 3}).isPresent());
        host.stop();
    }

    @Test
    public void whatTheWorkerSaysGoesToItsLog() throws Exception {
        Path log = Files.createTempFile("thumbnailer-", ".log");
        ThumbnailerHost host = new ThumbnailerHost(command(NoisyWorker.class), 10_000, Optional.of(log));

        host.generateThumbnail(new byte[]{1, 2, 3});
        host.stop();

        Assert.assertTrue("ffmpeg's complaint is kept: " + Files.readString(log),
                Files.readString(log).contains("Assertion stream_index < ogg->nstreams failed"));
        Files.deleteIfExists(log);
    }

    /** An image is not worth losing because ffmpeg is unavailable or can't parse it. */
    @Test
    public void imagesFallBackToJavaWhenFfmpegCantHelp() throws Exception {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", bout);
        ThumbnailGenerator.Generator refuses = data -> Optional.empty();

        Optional<Thumbnail> thumbnail = new FallbackThumbnailer(refuses, new JavaImageThumbnailer())
                .generateThumbnail(bout.toByteArray());

        Assert.assertTrue(thumbnail.isPresent());
    }

    /** Answers the first request, then goes deaf while staying alive. */
    public static class StdinClosingWorker {
        public static void main(String[] args) throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            reply(in.readLine());
            System.in.close();
            Thread.sleep(60_000);
        }

        static void reply(String line) throws Exception {
            Files.write(Path.of(new String(Base64.getDecoder().decode(line.split(" ")[2]))),
                    new byte[]{'R', 'I', 'F', 'F'});
            System.out.println("ok");
        }
    }

    public static class NoisyWorker {
        public static void main(String[] args) throws Exception {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            System.err.println("Assertion stream_index < ogg->nstreams failed at libavformat/oggdec.c:940");
            System.out.println("fail");
            Thread.sleep(2_000);
        }
    }

    public static class CrashingWorker {
        public static void main(String[] args) throws Exception {
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
            String line = in.readLine();
            Path marker = Path.of(args[0]);
            if (! Files.exists(marker)) {
                Files.write(marker, new byte[0]);
                Runtime.getRuntime().halt(134); // what an ffmpeg assertion does to us
            }
            Path output = Path.of(new String(Base64.getDecoder().decode(line.split(" ")[2])));
            Files.write(output, new byte[]{'R', 'I', 'F', 'F'});
            System.out.println("ok");
        }
    }

    public static class HangingWorker {
        public static void main(String[] args) throws Exception {
            new BufferedReader(new InputStreamReader(System.in)).readLine();
            Thread.sleep(60_000);
        }
    }
}
