package peergos.shared.user.fs;

import peergos.shared.io.ipfs.Cid;

import java.util.*;

public class FragmentWithHash {
    public final Fragment fragment;
    public final Optional<Cid> hash;

    public FragmentWithHash(Fragment fragment, Optional<Cid> hash) {
        this.fragment = fragment;
        this.hash = hash;
    }

    public boolean isInlined() {
        return hash.isEmpty();
    }
}
