package peergos.server;

import peergos.server.storage.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public class RegisteredUserKeyFilter implements KeyFilter {

    private final CoreNode core;
    private final MutablePointers mutable;
    private final ContentAddressedStorage dht;
    private final Map<PublicKeyHash, Boolean> allowedKeys = new ConcurrentHashMap<>();

    public RegisteredUserKeyFilter(CoreNode core, MutablePointers mutable, ContentAddressedStorage dht) {
        this.core = core;
        this.mutable = mutable;
        this.dht = dht;
    }

    private void reloadKeys() {
        try {
            List<String> usernames = core.getUsernames("").get();
            for (String username : usernames) {
                loadSigningKeys(username);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void loadSigningKeys(String username) {
        for (PublicKeyHash hash : WriterData.getOwnedKeysRecursive(username, core, mutable, dht)) {
            allowedKeys.put(hash, true);
        }
    }

    @Override
    public boolean isAllowed(PublicKeyHash signerHash) {
        if (allowedKeys.getOrDefault(signerHash, false))
            return true;
        // slow reload
        reloadKeys();
        return allowedKeys.getOrDefault(signerHash, false);
    }
}
