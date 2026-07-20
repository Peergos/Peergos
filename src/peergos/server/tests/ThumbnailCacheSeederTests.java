package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.Thumbnail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The thumbnail-cache pre-seed feature only works if the dav:// URI Peergos builds is
 * byte-identical to the one gio/GVFS uses, since the cache key is md5(uri). These cases were
 * captured from real `gio info` output on a mounted drive; if the encoding drifts, seeded
 * thumbnails silently stop being found (cache misses) and files get downloaded instead.
 */
public class ThumbnailCacheSeederTests {

    private static String encodePath(String path) throws Exception {
        Class<?> c = Class.forName("peergos.server.webdav.ThumbnailCacheSeeder");
        Method m = c.getDeclaredMethod("encodePath", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, path);
    }

    private static String uri(String user, int port, String path) throws Exception {
        return "dav://" + user + "@localhost:" + port + encodePath(path);
    }

    private static String md5Hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : d) b.append(String.format("%02x", x));
        return b.toString();
    }

    @Test
    public void matchesGioUriForSimpleName() throws Exception {
        String expected = "dav://9003a13d0b00497ccd665533725e1f3c@localhost:40915/demo/4640.jpg";
        Assert.assertEquals(expected,
                uri("9003a13d0b00497ccd665533725e1f3c", 40915, "/demo/4640.jpg"));
    }

    @Test
    public void matchesGioUriForNameWithSpaces() throws Exception {
        // gio percent-encodes spaces as %20 and preserves case and '.'
        String expected = "dav://9003a13d0b00497ccd665533725e1f3c@localhost:40915"
                + "/demo/social%20feed%20loading%20on%20iPad.png";
        Assert.assertEquals(expected,
                uri("9003a13d0b00497ccd665533725e1f3c", 40915, "/demo/social feed loading on iPad.png"));
    }

    @Test
    public void md5KeyMatchesGio() throws Exception {
        // The cache filename is md5(uri).png — this md5 was confirmed against the live cache.
        Assert.assertEquals("845d3d0f88f87d2b79b7410f3687c636",
                md5Hex(uri("9003a13d0b00497ccd665533725e1f3c", 40915, "/demo/4640.jpg")));
        Assert.assertEquals("8d7e57a729b0ac0ab05f77e286053a44",
                md5Hex(uri("9003a13d0b00497ccd665533725e1f3c", 40915, "/demo/social feed loading on iPad.png")));
    }

    @Test
    public void keepsSeparatorsAndUnreserved() throws Exception {
        Assert.assertEquals("/a/b-c_d.e~f", encodePath("/a/b-c_d.e~f"));
    }

    /** Reflectively build a seeder pointing at a temp dir (bypasses the flatpak-only factory). */
    private static Object seederFor(String user, int port, Path normalDir) throws Exception {
        Class<?> c = Class.forName("peergos.server.webdav.ThumbnailCacheSeeder");
        Constructor<?> ctor = c.getDeclaredConstructor(String.class, int.class, Path.class);
        ctor.setAccessible(true);
        return ctor.newInstance(user, port, normalDir);
    }

    private static void seed(Object seeder, String path, FileProperties props) throws Exception {
        Method m = seeder.getClass().getDeclaredMethod("seed", String.class, FileProperties.class);
        m.setAccessible(true);
        m.invoke(seeder, path, props);
    }

    private static FileProperties fileWithThumbnail(String name, byte[] thumbBytes) {
        return new FileProperties(name, false, false, "image/jpeg", 0, 123,
                LocalDateTime.now(), LocalDateTime.now(), false,
                Optional.of(new Thumbnail("image/webp", thumbBytes)),
                Optional.of(new byte[32]), Optional.empty());
    }

    @Test
    public void seedWritesThumbnailAtMd5Key() throws Exception {
        Path dir = Files.createTempDirectory("thumbseed");
        Object seeder = seederFor("9003a13d0b00497ccd665533725e1f3c", 40915, dir);
        byte[] thumb = "pretend-webp-bytes".getBytes(StandardCharsets.UTF_8);

        seed(seeder, "/demo/4640.jpg", fileWithThumbnail("4640.jpg", thumb));

        Path expected = dir.resolve("845d3d0f88f87d2b79b7410f3687c636.png");
        Assert.assertTrue("thumbnail should be written at md5(uri).png", Files.exists(expected));
        Assert.assertArrayEquals("stored bytes written verbatim", thumb, Files.readAllBytes(expected));
        // no leftover temp files
        Assert.assertEquals(1, Files.list(dir).count());
    }

    @Test
    public void seedSkipsFilesWithoutThumbnail() throws Exception {
        Path dir = Files.createTempDirectory("thumbseed");
        Object seeder = seederFor("u", 1, dir);
        FileProperties noThumb = new FileProperties("x.txt", false, false, "text/plain", 0, 1,
                LocalDateTime.now(), LocalDateTime.now(), false,
                Optional.empty(), Optional.of(new byte[32]), Optional.empty());

        seed(seeder, "/demo/x.txt", noThumb);

        Assert.assertEquals("nothing written for a file with no thumbnail", 0, Files.list(dir).count());
    }
}
