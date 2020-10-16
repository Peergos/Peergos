package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

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
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("version", new CborObject.CborString(VERSION_1));
        cbor.put("name", new CborObject.CborString(name.substring(0, Math.min(name.length(), 25))));
        cbor.put("lists", new CborObject.CborList(todoLists));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoBoard fromCbor(LocalDateTime timestamp, Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("TodoList cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
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
