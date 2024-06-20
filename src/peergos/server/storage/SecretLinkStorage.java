package peergos.server.storage;

import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.hamt.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public class SecretLinkStorage extends DelegatingDeletableStorage {

    private final DeletableContentAddressedStorage target;
    private final MutablePointers pointers;
    private final Hasher hasher;
    private final LinkRetrievalCounter counter;

    public SecretLinkStorage(DeletableContentAddressedStorage target, MutablePointers pointers, LinkRetrievalCounter counter, Hasher hasher) {
        super(target);
        this.target = target;
        this.pointers = pointers;
        this.hasher = hasher;
        this.counter = counter;
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        PublicKeyHash owner = link.owner;
        WriterData wd = WriterData.getWriterData(owner, owner, pointers, target).join().props.get();
        if (wd.secretLinks.isEmpty())
            throw new IllegalStateException("No secret link published!");
        SecretLinkChamp champ = SecretLinkChamp.build(owner, (Cid) wd.secretLinks.get(), this, hasher).join();
        Optional<SecretLinkTarget> res = champ.get(owner, link.label).join();
        if (res.isEmpty())
            throw new IllegalStateException("No secret link present!");
        SecretLinkTarget target = res.get();
        if (target.expiry.isPresent() && target.expiry.get().isBefore(LocalDateTime.now()))
            throw new IllegalStateException("Secret link expired!");
        if (target.maxRetrievals.isPresent() && counter.getCount(owner, link.label) >= target.maxRetrievals.get())
            throw new IllegalStateException("Invalid secret link!");
        return Futures.of(target.cap);
    }
}
