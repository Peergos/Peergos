package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@JsType
public class TodoBoard implements Cborable {

    private static final String VERSION_1 = "1";
    private final List<TodoList> todoLists;
    private final String name;
    private final LocalDateTime timestamp;

    private TodoBoard(String name, List<TodoList> todoLists, LocalDateTime timestamp) {
        this.name = name;
        this.todoLists = todoLists;
        if (timestamp == null) {
            this.timestamp = null;
        } else {
            long asSeconds = timestamp.toEpochSecond(ZoneOffset.UTC);
            this.timestamp = LocalDateTime.ofEpochSecond(asSeconds, 0, ZoneOffset.UTC);
        }
    }

    public static TodoBoard build(String name, List<TodoList> todoLists) {
        return buildWithTimestamp(name, todoLists, LocalDateTime.now());
    }

    public static TodoBoard buildWithTimestamp(String name, List<TodoList> todoLists, LocalDateTime timestamp) {
        return new TodoBoard(name, todoLists, timestamp);
    }

    public static TodoBoard buildFromJs(String name, TodoList[] todoLists, LocalDateTime timestamp) {
        return new TodoBoard(name, Arrays.asList(todoLists), timestamp);
    }

    public String getName() {
        return name;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public List<TodoList> getTodoLists() {
        return new ArrayList<>(todoLists);
    }

    public TodoBoard withTimestamp(LocalDateTime timestamp) {
        return new TodoBoard(name, todoLists, timestamp);
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cborData = new TreeMap<>();
        cborData.put("version", new CborObject.CborString(VERSION_1));
        cborData.put("name", new CborObject.CborString(name.substring(0, Math.min(name.length(), 25))));
        cborData.put("lists", new CborObject.CborList(todoLists));

        List<CborObject> contents = new ArrayList<>();
        contents.add(new CborObject.CborString(UserContext.App.Todo.TODO_MIME_TYPE));
        contents.add(CborObject.CborMap.build(cborData));

        return new CborObject.CborList(contents);
    }

    public static TodoBoard fromCbor(LocalDateTime timestamp, Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for TodoList: " + cbor);

        List<? extends Cborable> contents = ((CborObject.CborList) cbor).value;
        String mimeType = ((CborObject.CborString) contents.get(0)).value;
        if (!mimeType.equals(UserContext.App.Todo.TODO_MIME_TYPE))
            throw new IllegalStateException("Invalid mimetype for TodoList: " + mimeType);

        CborObject.CborMap m = (CborObject.CborMap) contents.get(1);
        String version = m.getString("version");
        if (! version.equals(VERSION_1)) {
            throw new IllegalStateException("Unsupported version:" + version);
        }
        String name = m.getString("name");
        List<TodoList> todoList = m.getList("lists")
                .value.stream()
                .map(TodoList::fromCbor)
                .collect(Collectors.toList());
        return new TodoBoard(name, todoList, timestamp);
    }

}
