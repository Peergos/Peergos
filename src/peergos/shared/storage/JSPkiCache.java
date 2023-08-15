package peergos.shared.storage;

import peergos.shared.cbor.CborObject;
import peergos.shared.corenode.PkiCache;
import peergos.shared.corenode.UserPublicKeyLink;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.bases.Multibase;
import peergos.shared.user.NativeJSPkiCache;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class JSPkiCache implements PkiCache {

    private final NativeJSPkiCache cache = new NativeJSPkiCache();

    public JSPkiCache() {
        cache.init();
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        return cache.getChain(username).thenApply(serialisedUserPublicKeyLinks -> {
            if (serialisedUserPublicKeyLinks.isEmpty())
                throw new RuntimeException("Client Offline!");
            List<UserPublicKeyLink> list = new ArrayList();
            for(String userPublicKeyLink :  serialisedUserPublicKeyLinks) {
                list.add(UserPublicKeyLink.fromCbor(CborObject.fromByteArray(Multibase.decode(userPublicKeyLink))));
            }
            return list;
        });
    }

    @Override
    public CompletableFuture<Boolean> setChain(String username, List<UserPublicKeyLink> chain) {
        String[] serialisedUserPublicKeyLinks = new String[chain.size()];
        for(int i =0; i < chain.size(); i++) {
            serialisedUserPublicKeyLinks[i] = Multibase.encode(Multibase.Base.Base58BTC, chain.get(i).serialize());
        }
        PublicKeyHash owner = chain.get(chain.size() - 1).owner;
        String serialisedOwner = new String(Base64.getEncoder().encode(owner.serialize()));
        return cache.setChain(username, serialisedUserPublicKeyLinks, serialisedOwner);
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash key) {
        return cache.getUsername(new String(Base64.getEncoder().encode(key.serialize()))).thenApply(username -> {
           if (username.isEmpty()) {
               throw new RuntimeException("Client Offline!");
           }
           return username;
        });
    }

}
