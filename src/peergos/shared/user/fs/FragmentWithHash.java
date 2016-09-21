package peergos.shared.user.fs;

import peergos.shared.ipfs.api.*;

public class FragmentWithHash {
    public final Fragment fragment;
    public final Multihash hash;

    public FragmentWithHash(Fragment fragment, Multihash hash) {
        this.fragment = fragment;
        this.hash = hash;
    }
}
