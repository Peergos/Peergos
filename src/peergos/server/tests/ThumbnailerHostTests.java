package peergos.server.tests;

import org.junit.*;
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
        Optional<ThumbnailerHost> host = ThumbnailerHost.create();
        Assume.assumeTrue("native thumbnailer available", host.isPresent());
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", bout);

        Optional<Thumbnail> thumbnail = host.get().generateThumbnail(bout.toByteArray());

        Assert.assertTrue(thumbnail.isPresent());
        Assert.assertEquals("image/webp", thumbnail.get().mimeType);
        host.get().stop();
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
