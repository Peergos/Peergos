package peergos.shared.user;

import jsinterop.annotations.JsType;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.Collectors;

@JsType
public class TodoBoard implements Cborable {

    private static final String VERSION_1 = "1";
    private final List<TodoList> todoLists;
    private final String name;

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

    public byte[] toByteArray() {
        return this.serialize();
    }

    public static TodoBoard fromByteArray(byte[] data) {
        return fromCbor(CborObject.fromByteArray(data));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cborData = new TreeMap<>();
        cborData.put("version", new CborObject.CborString(VERSION_1));
        cborData.put("name", new CborObject.CborString(name.substring(0, Math.min(name.length(), 25))));
        cborData.put("lists", new CborObject.CborList(todoLists));

        List<CborObject> contents = new ArrayList<>();
        contents.add(new CborObject.CborLong(MimeTypes.CBOR_PEERGOS_TODO_INT));
        contents.add(CborObject.CborMap.build(cborData));

        return new CborObject.CborList(contents);
    }

    private static TodoBoard fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for TodoList: " + cbor);

        List<? extends Cborable> contents = ((CborObject.CborList) cbor).value;
        long mimeType = ((CborObject.CborLong) contents.get(0)).value;
        if (mimeType != MimeTypes.CBOR_PEERGOS_TODO_INT)
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
        return new TodoBoard(name, todoList);
    }

}
