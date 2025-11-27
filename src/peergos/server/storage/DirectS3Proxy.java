package peergos.server.storage;

import peergos.server.util.HttpUtil;
import peergos.shared.cbor.CborObject;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.crypto.hash.PublicKeyHash;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.BatWithId;
import peergos.shared.storage.auth.S3Request;
import peergos.shared.user.fs.EncryptedCapability;
import peergos.shared.user.fs.SecretLink;
import peergos.shared.util.Futures;
import peergos.shared.util.Pair;
import peergos.shared.util.ProgressConsumer;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import static peergos.server.storage.S3BlockStorage.isRateLimitedException;
import static peergos.shared.storage.DirectS3BlockStore.hashToKey;

/** This can be used for direct (read-only) blockstore access when mirroring a server from S3 to glacier
 *
 */
public class DirectS3Proxy implements ContentAddressedStorageProxy {

    private static final Logger LOG = Logger.getGlobal();

    private final String region, bucket, host, folder;
    private final String accessKeyId, secretKey;
    private final Optional<String> storageClass;
    private final boolean useHttps;
    private final Cid remoteId;
    private final Hasher h;

    public DirectS3Proxy(S3Config config, Cid remoteId, Hasher h) {
        this.remoteId = remoteId;
        this.h = h;
        this.region = config.region;
        this.bucket = config.bucket;
        this.host = config.getHost();
        this.useHttps = ! host.endsWith("localhost") && ! host.contains("localhost:");
        this.folder = (useHttps ? "" : bucket + "/") + (config.path.isEmpty() || config.path.endsWith("/") ? config.path : config.path + "/");
        this.storageClass = config.storageClass;
        this.accessKeyId = config.accessKey;
        this.secretKey = config.secretKey;
    }

    @Override
    public CompletableFuture<Optional<byte[]>> getRaw(Multihash targetServerId, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        if (! remoteId.equals(targetServerId))
            throw new IllegalStateException("Can only proxy to the target instance!");
        if (hash.isIdentity())
            return Futures.of(Optional.of(hash.getHash()));
        return getWithBackoff(() -> getRawWithoutBackoff(List.of(targetServerId), owner, hash))
                .thenApply(p -> p.map(v -> v.left));
    }

    private CompletableFuture<Optional<Pair<byte[], String>>> getRawWithoutBackoff(List<Multihash> peerIds,
                                                                                   PublicKeyHash owner,
                                                                                   Cid hash) {
        String path = folder + hashToKey(hash);
        PresignedUrl getUrl = S3Request.preSignGet(path, Optional.of(600), Optional.empty(),
                S3AdminRequests.asAwsDate(ZonedDateTime.now()), host, region, storageClass, accessKeyId, secretKey, useHttps, h).join();
        try {
            Pair<byte[], String> blockAndVersion = HttpUtil.getWithVersion(getUrl);
            return Futures.of(Optional.of(blockAndVersion));
        } catch (SocketTimeoutException | SSLException | SocketException e) {
            // S3 can't handle the load so treat this as a rate limit and slow down
            throw new RateLimitException();
        } catch (IOException e) {
            String msg = e.getMessage();
            boolean rateLimited = isRateLimitedException(e);
            if (rateLimited) {
                throw new RateLimitException();
            }
            boolean notFound = msg.contains("<Code>NoSuchKey</Code>");
            if (!notFound) {
                LOG.warning("Remote S3 error reading " + path);
                LOG.log(Level.WARNING, msg, e);
            }
            throw new RuntimeException(e);
        }
    }

    private static <V> V getWithBackoff(Supplier<V> req) {
        long sleep = 100;
        for (int i=0; i < 20; i++) {
            try {
                return req.get();
            } catch (RateLimitException e) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException f) {}
                sleep *= 2;
            }
        }
        throw new IllegalStateException("Couldn't process request because of rate limit!");
    }

    @Override
    public CompletableFuture<Optional<CborObject>> get(Multihash targetServerId, PublicKeyHash owner, Cid hash, Optional<BatWithId> bat) {
        return getRaw(targetServerId, owner, hash, bat)
                .thenApply(opt -> opt.map(CborObject::fromByteArray));
    }

    @Override
    public CompletableFuture<String> linkHost(Multihash targetServerId, PublicKeyHash owner) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<TransactionId> startTransaction(Multihash targetServerId, PublicKeyHash owner) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<Boolean> closeTransaction(Multihash targetServerId, PublicKeyHash owner, TransactionId tid) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<byte[]>> getChampLookup(Multihash targetServerId, PublicKeyHash owner, Multihash root, List<ChunkMirrorCap> caps) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<EncryptedCapability> getSecretLink(Multihash targetServerId, SecretLink link) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<LinkCounts> getLinkCounts(Multihash targetServerId, String owner, LocalDateTime after, BatWithId mirrorBat) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Cid>> put(Multihash targetServerId, PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid) {
        throw new IllegalStateException("Unsupported operation!");
    }

    @Override
    public CompletableFuture<List<Cid>> putRaw(Multihash targetServerId, PublicKeyHash owner, PublicKeyHash writer, List<byte[]> signatures, List<byte[]> blocks, TransactionId tid, ProgressConsumer<Long> progressConsumer) {
        throw new IllegalStateException("Unsupported operation!");
    }
}
