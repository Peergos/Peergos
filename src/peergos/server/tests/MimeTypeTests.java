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
    public void todoCustomMimeType() {
        TodoBoard board = TodoBoard.build("todoBoard",
                Arrays.asList(
                        TodoList.build("todoList", "1",
                                Arrays.asList(
                                        new TodoListItem("id", LocalDateTime.now(), "text", false)
                                ))));

        byte[] raw = board.toCbor().toByteArray();

        String mime = MimeTypes.calculateMimeType(raw,
                board.getName() + ".todo");
        Assert.assertTrue(mime.equals("application/vnd.peergos-todo"));
    }

    @Test
    public void utf8() {
        byte[] utf8 = ArrayOps.concat("<!DOCTYPE html>\n<html>".getBytes(StandardCharsets.UTF_8), new byte[]{(byte)0xe2, (byte)0x80, (byte)0x9c});
        String mime = MimeTypes.calculateMimeType(utf8, "surreal");
        Assert.assertTrue(mime.equals("text/html"));
    }

    @Test
    public void truncatedUtf8() {
        byte[] utf8 = ArrayOps.concat("<!DOCTYPE html>\n<html>".getBytes(StandardCharsets.UTF_8), new byte[]{(byte)0xe2, (byte)0x80});
        String mime = MimeTypes.calculateMimeType(utf8, "surreal");
        Assert.assertTrue(mime.equals("text/html"));
    }
}
