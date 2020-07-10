package peergos.shared.user.fs;


import peergos.shared.io.ipfs.multihash.*;

import java.util.*;

public class FragmentWithHash {
    public final Fragment fragment;
    public final Optional<Multihash> hash;

    public FragmentWithHash(Fragment fragment, Optional<Multihash> hash) {
        this.fragment = fragment;
        this.hash = hash;
    }

    public boolean isInlined() {
        return hash.isEmpty();
    }
}
