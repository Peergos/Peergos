package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.Collectors;
@JsType
public class TodoList implements Cborable {

    private final List<TodoListItem> todoItems;
    private final int index;
    private final String name;

    public static TodoList build(String name, int index, List<TodoListItem> todoItems) {
        return new TodoList(name, index, todoItems);
    }

    public static TodoList buildFromJs(String name, int index, TodoListItem[] todoItems) {
        return new TodoList(name, index, Arrays.asList(todoItems));
    }

    private TodoList(String name, int index, List<TodoListItem> todoItems) {
        this.name = name;
        this.index = index;
        this.todoItems = todoItems;
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    public List<TodoListItem> getTodoItems() {
        return new ArrayList<>(todoItems);
    }

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> cbor = new TreeMap<>();
        cbor.put("name", new CborObject.CborString(name));
        cbor.put("index", new CborObject.CborString("" + index));
        cbor.put("items", new CborObject.CborList(todoItems));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoList fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("TodoList cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        int index = Integer.parseInt(m.getString("index"));
        String name = m.getString("name");
        List<TodoListItem> todoItems = m.getList("items")
                .value.stream()
                .map(TodoListItem::fromCbor)
                .collect(Collectors.toList());
        return new TodoList(name, index, todoItems);
    }

}
