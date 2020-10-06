package peergos.server.tests;

import org.junit.*;
import peergos.shared.user.TodoBoard;
import peergos.shared.user.TodoList;
import peergos.shared.user.TodoListItem;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class MimeTypeTests {

    @Test
    public void smallTextFile() {
        String mime = MimeTypes.calculateMimeType("G'day Peergos!".getBytes(), "data.txt");
        Assert.assertTrue(mime.equals("text/plain"));
    }

    @Test
    public void todoCustomMimeType() {
        List<TodoListItem> items = new ArrayList<>();
        items.add(new TodoListItem("id", LocalDateTime.now(), "text", false));
        ArrayList<TodoList> lists = new ArrayList<>();
        lists.add(TodoList.build("todoList", "1", items));
        TodoBoard board = TodoBoard.build("todoBoard", lists);

        String mime = MimeTypes.calculateMimeType(board.toCbor().toByteArray(),
                board.getName() + UserContext.App.Todo.TODO_FILE_EXTENSION);
        Assert.assertTrue(mime.equals("application/vnd.peergos-todo"));
    }
}
