package peergos.shared.storage;

import peergos.shared.routing.IdentityKeys;

/** Gives the storage it wraps, and everything wrapping it, a source for identities' current keys. */
public class IdentityKeyedStorage extends DelegatingStorage {
    private final ContentAddressedStorage target;
    private final IdentityKeys keys;

    public IdentityKeyedStorage(ContentAddressedStorage target, IdentityKeys keys) {
        super(target);
        this.target = target;
        this.keys = keys;
    }

    @Override
    public ContentAddressedStorage directToOrigin() {
        return new IdentityKeyedStorage(target.directToOrigin(), keys);
    }

    @Override
    public IdentityKeys identityKeys() {
        return keys;
    }
}
