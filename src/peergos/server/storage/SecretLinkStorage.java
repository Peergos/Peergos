package peergos.server.storage;

import peergos.server.storage.admin.QuotaAdmin;
import peergos.shared.corenode.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.mutable.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

public class SecretLinkStorage extends DelegatingDeletableStorage {

    private static final Logger LOG = Logger.getGlobal();

    private final DeletableContentAddressedStorage target;
    private final MutablePointers pointers;
    private final Hasher hasher;
    private final LinkRetrievalCounter counter;
    private final BatCave batstore;
    private final boolean allowNonLocalLinks;
    private final QuotaAdmin quota;
    private CoreNode pki;

    public SecretLinkStorage(DeletableContentAddressedStorage target,
                             MutablePointers pointers,
                             LinkRetrievalCounter counter,
                             boolean allowNonLocalLinks,
                             QuotaAdmin quota,
                             BatCave batStore,
                             Hasher hasher) {
        super(target);
        this.target = target;
        this.pointers = pointers;
        this.hasher = hasher;
        this.counter = counter;
        this.allowNonLocalLinks = allowNonLocalLinks;
        this.quota = quota;
        this.batstore = batStore;
    }

    @Override
    public void setPki(CoreNode pki) {
        this.pki = pki;
        target.setPki(pki);
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(SecretLink link) {
        PublicKeyHash owner = link.owner;
        String username = pki.getUsername(owner).join();
        boolean isLocal = quota.getQuota(username) > 0;
        if (! isLocal && !allowNonLocalLinks)
            throw new IllegalStateException("Please use the link owner's server");

        WriterData wd = WriterData.getWriterData(owner, owner, pointers, target).join().props.get();
        if (wd.secretLinks.isEmpty())
            throw new IllegalStateException("No secret link published!");
        Optional<BatWithId> mirrorBat = batstore.getUserBats(username, new byte[0]).join().stream().findFirst();
        SecretLinkChamp champ = SecretLinkChamp.build(owner, (Cid) wd.secretLinks.get(), mirrorBat, this, hasher).join();
        Optional<SecretLinkTarget> res = champ.get(owner, link.label).join();
        if (res.isEmpty())
            throw new IllegalStateException("No secret link present!");
        SecretLinkTarget target = res.get();
        if (target.expiry.isPresent()) {
            LocalDateTime now = LocalDateTime.now();
            if (target.expiry.get().isBefore(now)) {
                LOG.info("Expired secret link: " + owner + "-" + link.label + " " + target.expiry.get() + " < " + now);
                throw new IllegalStateException("Secret link expired!");
            }
        }

        if (target.maxRetrievals.isPresent()) {
            long retrievals = counter.getCount(username, link.label);
            if (retrievals >= target.maxRetrievals.get()) {
                LOG.info("Unavailable secret link: " + owner + "-" + link.label + " " + target.maxRetrievals.get() + " >= " + retrievals);
                throw new IllegalStateException("Maximum link retrievals exceed!");
            }
        }
        counter.increment(username, link.label);
        return Futures.of(target.cap);
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(String owner,
                                                       LocalDateTime after,
                                                       BatWithId mirrorBat) {
        byte[] supplied = hasher.sha256(mirrorBat.serialize()).join();
        List<BatWithId> mirrorBats = batstore.getUserBats(owner, new byte[0]).join();
        byte[] expected = hasher.sha256(mirrorBats.get(mirrorBats.size() - 1).serialize()).join();
        if (! Arrays.equals(expected, supplied))
            throw new IllegalStateException("Unauthorized!");
        return Futures.of(counter.getUpdatedCounts(owner, after));
    }
}
