package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.Collectors;

@JsType
public class TodoBoard implements Cborable {

    private final List<TodoList> todoLists;
    private final String name;
    private static final String VERSION_1 = "1";

    private TodoBoard(String name, List<TodoList> todoLists) {
        this.name = name;
        this.todoLists = todoLists;
    }

    public static TodoBoard build(String name, List<TodoList> todoLists) {
        return new TodoBoard(name, todoLists);
    }

    public static TodoBoard buildFromJs(String name, TodoList[] todoLists) {
        return new TodoBoard(name, Arrays.asList(todoLists));
    }

    public String getName() {
        return name;
    }

    public List<TodoList> getTodoLists() {
        return new ArrayList<>(todoLists);
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("version", new CborObject.CborString(VERSION_1));
        cbor.put("name", new CborObject.CborString(name));
        cbor.put("lists", new CborObject.CborList(todoLists));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoBoard fromCbor(Cborable cbor) {
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
        return new TodoBoard(name, todoList);
    }

}
