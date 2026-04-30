package peergos.server.tests;

import org.junit.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.charset.*;
import java.time.LocalDateTime;
import java.util.*;

public class MimeTypeTests {

    @Test
    public void smallTextFile() {
        String mime = MimeTypes.calculateMimeType("G'day Peergos!".getBytes(), "data.txt");
        Assert.assertTrue(mime.equals("text/plain"));
    }

    @Test
    public void utf8() {
        byte[] utf8 = ArrayOps.concat("<!DOCTYPE html>\n<html>".getBytes(StandardCharsets.UTF_8), new byte[]{(byte)0xe2, (byte)0x80, (byte)0x9c});
        String mime = MimeTypes.calculateMimeType(utf8, "surreal");
        Assert.assertTrue(mime.equals("text/html"));
    }

    @Test
    public void oddMp4() {
        byte[] utf8 = ArrayOps.hexToBytes("000000286674797069736f340000000069736f3469736f6d");
        String mime = MimeTypes.calculateMimeType(utf8, "x.mp4");
        Assert.assertTrue(mime.equals("video/mp4"));
    }

    @Test
    public void truncatedUtf8() {
        byte[] utf8 = ArrayOps.concat("<!DOCTYPE html>\n<html>".getBytes(StandardCharsets.UTF_8), new byte[]{(byte)0xe2, (byte)0x80});
        String mime = MimeTypes.calculateMimeType(utf8, "surreal");
        Assert.assertTrue(mime.equals("text/html"));
    }
}
