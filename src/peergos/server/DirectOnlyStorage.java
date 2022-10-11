package peergos.server;

import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class DirectOnlyStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;

    public DirectOnlyStorage(ContentAddressedStorage target) {
        super(target);
        this.target = target;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new DirectOnlyStorage(target.directToOrigin());
    }

    @Override
    public CompletableFuture<BlockStoreProperties> blockStoreProperties() {
        return Futures.of(new BlockStoreProperties(false, false, false, Optional.empty(), Optional.empty()));
    }
}
