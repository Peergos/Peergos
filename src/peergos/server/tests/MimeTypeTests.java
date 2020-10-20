package peergos.server.tests;

import org.junit.*;
import peergos.shared.user.TodoBoard;
import peergos.shared.user.TodoList;
import peergos.shared.user.TodoListItem;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;

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
                board.getName() + UserContext.App.Todo.TODO_FILE_EXTENSION);
        Assert.assertTrue(mime.equals("application/vnd.peergos-todo"));
    }
}
