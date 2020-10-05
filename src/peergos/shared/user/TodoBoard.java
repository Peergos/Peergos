package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.Collectors;

@JsType
public class TodoBoard implements Cborable {

    private final List<TodoList> todoLists;
    private final boolean isWritable;
    private final String name;

    private TodoBoard(String name, boolean isWritable, List<TodoList> todoLists) {
        this.name = name;
        this.isWritable = isWritable;
        this.todoLists = todoLists;
    }

    public static TodoBoard build(String name, List<TodoList> todoLists) {
        return new TodoBoard(name, true, todoLists);
    }

    public static TodoBoard buildFromJs(String name, TodoList[] todoLists) {
        return new TodoBoard(name, true, Arrays.asList(todoLists));
    }

    public String getName() {
        return name;
    }

    public List<TodoList> getTodoLists() {
        return new ArrayList<>(todoLists);
    }

    public boolean isWritable() {
        return isWritable;
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("name", new CborObject.CborString(name));
        cbor.put("lists", new CborObject.CborList(todoLists));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoBoard fromCbor(boolean isWritable, Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("TodoList cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String name = m.getString("name");
        List<TodoList> todoList = m.getList("lists")
                .value.stream()
                .map(TodoList::fromCbor)
                .collect(Collectors.toList());
        return new TodoBoard(name, isWritable, todoList);
    }

}
