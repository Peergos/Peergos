package peergos.shared.user.app;

import peergos.shared.cbor.*;

import java.util.*;
import java.util.stream.*;

public class AppGrants implements Cborable {
    public final List<FolderGrant> grants;

    public AppGrants(List<FolderGrant> grants) {
        this.grants = Collections.unmodifiableList(grants);
    }

    public static AppGrants empty() {
        return new AppGrants(Collections.emptyList());
    }

    public Optional<FolderGrant> get(String id) {
        return grants.stream().filter(g -> g.id.equals(id)).findFirst();
    }

    public AppGrants add(FolderGrant g) {
        List<FolderGrant> res = new ArrayList<>(grants);
        res.add(g);
        return new AppGrants(res);
    }

    public AppGrants replace(FolderGrant g) {
        return new AppGrants(grants.stream()
                .map(e -> e.id.equals(g.id) ? g : e)
                .collect(Collectors.toList()));
    }

    public AppGrants remove(String id) {
        return new AppGrants(grants.stream()
                .filter(e -> ! e.id.equals(id))
                .collect(Collectors.toList()));
    }

    @Override
    public CborObject toCbor() {
        Map<String, Cborable> cbor = new TreeMap<>();
        cbor.put("g", new CborObject.CborList(grants));
        return CborObject.CborMap.build(cbor);
    }

    public static AppGrants fromCbor(Cborable cbor) {
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Incorrect cbor for AppGrants: " + cbor);
        return new AppGrants(((CborObject.CborMap) cbor).getList("g", FolderGrant::fromCbor));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return grants.equals(((AppGrants) o).grants);
    }

    @Override
    public int hashCode() {
        return grants.hashCode();
    }
}
