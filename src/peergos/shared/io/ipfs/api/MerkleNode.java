package peergos.shared.io.ipfs.api;

import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;

import java.util.*;
import java.util.stream.*;

public class MerkleNode {
    public final Multihash hash;
    public final Optional<String> name;
    public final Optional<Integer> size;
    public final Optional<Integer> type;
    public final List<MerkleNode> links;
    public final Optional<byte[]> data;

    public MerkleNode(String hash) {
        this(hash, Optional.empty());
    }

    public MerkleNode(String hash, Optional<String> name) {
        this(hash, name, Optional.empty(), Optional.empty(), Arrays.asList(), Optional.empty());
    }

    public MerkleNode(String hash, Optional<String> name, Optional<Integer> size, Optional<Integer> type, List<MerkleNode> links, Optional<byte[]> data) {
        this.name = name;
        this.hash = Cid.decode(hash);
        this.size = size;
        this.type = type;
        this.links = links;
        this.data = data;
    }

    @Override
    public boolean equals(Object b) {
        if (!(b instanceof MerkleNode))
            return false;
        MerkleNode other = (MerkleNode) b;
        return hash.equals(other.hash); // ignore name hash says it all
    }

    @Override
    public int hashCode() {
        return hash.hashCode();
    }

    public static MerkleNode fromJSON(Object rawjson) {
        if (rawjson instanceof String)
            return new MerkleNode((String)rawjson);
        Map json = (Map)rawjson;
        String hash = (String)json.get("Hash");
        if (hash == null)
            hash = (String)json.get("Key");
        Optional<String> name = json.containsKey("Name") ? Optional.of((String) json.get("Name")): Optional.empty();
        Optional<Integer> size = json.containsKey("Size") ? Optional.<Integer>empty().of((Integer) json.get("Size")): Optional.<Integer>empty().empty();
        Optional<Integer> type = json.containsKey("Type") ? Optional.<Integer>empty().of((Integer) json.get("Type")): Optional.<Integer>empty().empty();
        List<Object> linksRaw = (List<Object>) json.get("Links");
        List<MerkleNode> links = linksRaw == null ? Collections.EMPTY_LIST : linksRaw.stream().map(x -> MerkleNode.fromJSON(x)).collect(Collectors.toList());
        Optional<byte[]> data = json.containsKey("Data") ? Optional.of(((String)json.get("Data")).getBytes()): Optional.empty();
        return new MerkleNode(hash, name, size, type, links, data);
    }

    public Object toJSON() {
        Map res = new TreeMap<>();
        res.put("Hash", hash);
        res.put("Links", links.stream().map(x -> x.hash).collect(Collectors.toList()));
        if (data.isPresent())
            res.put("Data", data.get());
        if (name.isPresent())
            res.put("Name", name.get());
        if (size.isPresent())
            res.put("Size", size.get());
        if (type.isPresent())
            res.put("Type", type.get());
        return res;
    }

    public String toJSONString() {
        return JSONParser.toString(toJSON());
    }
}
