package peergos.user.fs;

import org.ipfs.api.*;

public class FragmentWithHash {
    public final Fragment fragment;
    public final Multihash hash;

    public FragmentWithHash(Fragment fragment, Multihash hash) {
        this.fragment = fragment;
        this.hash = hash;
    }
}
