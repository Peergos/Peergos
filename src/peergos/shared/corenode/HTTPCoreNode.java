
package peergos.shared.corenode;
import java.time.*;
import java.util.logging.*;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class HTTPCoreNode implements CoreNode {
	private static final Logger LOG = Logger.getGlobal();
	private static final String P2P_PROXY_PROTOCOL = "/http";

    private final HttpPoster poster;
    private final String urlPrefix;

    public HTTPCoreNode(HttpPoster p2p, Multihash pkiServerNodeId)
    {
        if (pkiServerNodeId == null)
            throw new IllegalStateException("Null pki server node id!");
        LOG.info("Creating HTTP Corenode API at " + p2p);
        this.poster = p2p;
        this.urlPrefix = getProxyUrlPrefix(pkiServerNodeId);
    }

    public HTTPCoreNode(HttpPoster direct)
    {
        LOG.info("Creating HTTP Corenode API at " + direct);
        this.poster = direct;
        this.urlPrefix = "";
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
    }

    @Override
    public CompletableFuture<Optional<PublicKeyHash>> getPublicKeyHash(String username) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            CompletableFuture<byte[]> fut = poster.postUnzip(urlPrefix + Constants.CORE_URL + "getPublicKey", bout.toByteArray());
            return fut.thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));

                try {
                    if (!din.readBoolean())
                        return Optional.empty();
                    byte[] publicKey = CoreNodeUtils.deserializeByteArray(din);
                    return Optional.of(PublicKeyHash.fromCbor(CborObject.fromByteArray(publicKey)));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    @Override
    public CompletableFuture<String> getUsername(PublicKeyHash owner) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(owner.serialize(), dout);
            dout.flush();
            CompletableFuture<byte[]> fut = poster.post(urlPrefix + Constants.CORE_URL + "getUsername", bout.toByteArray(), true);
            return fut.thenApply(res -> {
                DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                try {
                    String username = Serialize.deserializeString(din, CoreNode.MAX_USERNAME_SIZE);
                    return username;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (IOException ioe) {
            LOG.severe("Couldn't connect to " + poster);
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return null;
        }
    }

    @Override
    public CompletableFuture<List<UserPublicKeyLink>> getChain(String username) {
        try
        {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + Constants.CORE_URL + "getChain", bout.toByteArray()).thenApply(res -> {
                CborObject cbor = CborObject.fromByteArray(res);
                if (! (cbor instanceof CborObject.CborList))
                    throw new IllegalStateException("Invalid cbor for claim chain: " + cbor);
                return ((CborObject.CborList) cbor).value.stream()
                        .map(UserPublicKeyLink::fromCbor)
                        .collect(Collectors.toList());
            });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            throw new IllegalStateException(ioe);
        }
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> signup(String username,
                                                                  UserPublicKeyLink chain,
                                                                  OpLog ops,
                                                                  ProofOfWork proof,
                                                                  String token) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            Serialize.serialize(chain.serialize(), dout);
            Serialize.serialize(ops.serialize(), dout);
            Serialize.serialize(proof.serialize(), dout);
            Serialize.serialize(token, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + Constants.CORE_URL + "signup", bout.toByteArray())
                    .thenApply(res -> {
                        DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                        try {
                            boolean success = din.readBoolean();
                            if (success)
                                return Optional.empty();
                            return Optional.of(new RequiredDifficulty(din.readInt()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Futures.errored(ioe);
        }
    }

    @Override
    public CompletableFuture<Either<PaymentProperties, RequiredDifficulty>> startPaidSignup(String username, UserPublicKeyLink chain, ProofOfWork proof) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            Serialize.serialize(chain.serialize(), dout);
            Serialize.serialize(proof.serialize(), dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + Constants.CORE_URL + "startPaidSignup", bout.toByteArray())
                    .thenApply(res -> {
                        DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                        try {
                            boolean success = din.readBoolean();
                            if (success) {
                                return Either.a(PaymentProperties.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 1024))));
                            }
                            return Either.b(new RequiredDifficulty(din.readInt()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Futures.errored(ioe);
        }
    }

    @Override
    public CompletableFuture<PaymentProperties> completePaidSignup(String username,
                                                                   UserPublicKeyLink chain,
                                                                   OpLog setupOperations,
                                                                   byte[] signedspaceRequest,
                                                                   ProofOfWork proof) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            Serialize.serialize(chain.serialize(), dout);
            Serialize.serialize(setupOperations.serialize(), dout);
            Serialize.serialize(proof.serialize(), dout);
            Serialize.serialize(signedspaceRequest, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + Constants.CORE_URL + "completePaidSignup", bout.toByteArray())
                    .thenApply(res -> PaymentProperties.fromCbor(CborObject.fromByteArray(res)));
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Futures.errored(ioe);
        }
    }

    @Override
    public CompletableFuture<Optional<RequiredDifficulty>> updateChain(String username,
                                                                       List<UserPublicKeyLink> chain,
                                                                       ProofOfWork proof,
                                                                       String token) {
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            Serialize.serialize(new CborObject.CborList(chain).serialize(), dout);
            Serialize.serialize(proof.serialize(), dout);
            Serialize.serialize(token, dout);
            dout.flush();

            return poster.postUnzip(urlPrefix + Constants.CORE_URL + "updateChain", bout.toByteArray())
                    .thenApply(res -> {
                        DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
                        try {
                            boolean success = din.readBoolean();
                            if (success)
                                return Optional.empty();
                            return Optional.of(new RequiredDifficulty(din.readInt()));
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Futures.errored(ioe);
        }
    }

    @Override
    public CompletableFuture<List<String>> getUsernames(String prefix) {
        return poster.postUnzip(urlPrefix + Constants.CORE_URL + "getUsernamesGzip/"+prefix, new byte[0])
                .thenApply(raw -> (List) JSONParser.parse(new String(raw)));
    }

    @Override
    public CompletableFuture<UserSnapshot> migrateUser(String username,
                                                       List<UserPublicKeyLink> newChain,
                                                       Multihash currentStorageId,
                                                       Optional<BatWithId> mirrorBat,
                                                       LocalDateTime latestLinkCountUpdate,
                                                       long currentUsage) {
        String modifiedPrefix = urlPrefix.isEmpty() ? "" : getProxyUrlPrefix(currentStorageId);
        try {
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            DataOutputStream dout = new DataOutputStream(bout);

            Serialize.serialize(username, dout);
            Serialize.serialize(new CborObject.CborList(newChain).serialize(), dout);
            Serialize.serialize(currentStorageId.toBytes(), dout);
            dout.writeBoolean(mirrorBat.isPresent());
            if (mirrorBat.isPresent())
                Serialize.serialize(mirrorBat.get().serialize(), dout);
            dout.writeLong(latestLinkCountUpdate.toEpochSecond(ZoneOffset.UTC));
            dout.writeLong(currentUsage);
            dout.flush();

            return poster.postUnzip(modifiedPrefix + Constants.CORE_URL + "migrateUser", bout.toByteArray(), -1)
                    .thenApply(res -> UserSnapshot.fromCbor(CborObject.fromByteArray(res)));
        } catch (IOException ioe) {
            LOG.log(Level.WARNING, ioe.getMessage(), ioe);
            return Futures.errored(ioe);
        }
    }

    @Override
    public CompletableFuture<Optional<Multihash>> getNextServerId(Multihash serverId) {
        throw new IllegalStateException("getNextServerId cannot be called remotely!");
    }

    @Override public void close() {}
}
