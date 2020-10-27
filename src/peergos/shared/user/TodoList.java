package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.CborObject;
import peergos.shared.cbor.Cborable;

import java.util.*;
import java.util.stream.Collectors;
@JsType
public class TodoList implements Cborable {

    private final List<TodoListItem> todoItems;
    private final String id;
    private final String name;

    public static TodoList build(String name, String id, List<TodoListItem> todoItems) {
        return new TodoList(name, id, todoItems);
    }

    public static TodoList buildFromJs(String name, String id, TodoListItem[] todoItems) {
        return new TodoList(name, id, Arrays.asList(todoItems));
    }

    private TodoList(String name, String id, List<TodoListItem> todoItems) {
        this.name = name;
        this.id = id;
        this.todoItems = todoItems;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public List<TodoListItem> getTodoItems() {
        return new ArrayList<>(todoItems);
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("name", new CborObject.CborString(name.substring(0, Math.min(name.length(), 20))));
        cbor.put("id", new CborObject.CborString(id));
        cbor.put("items", new CborObject.CborList(todoItems));
        return CborObject.CborMap.build(cbor);
    }

    public static TodoList fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("TodoList cbor must be a Map! " + cbor);
        CborObject.CborMap m = (CborObject.CborMap) cbor;
        String id = m.getString("id");
        String name = m.getString("name");
        List<TodoListItem> todoItems = m.getList("items")
                .value.stream()
                .map(TodoListItem::fromCbor)
                .collect(Collectors.toList());
        return new TodoList(name, id, todoItems);
    }

}
