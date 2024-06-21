package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.util.Logging;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Cid;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.storage.*;
import peergos.shared.storage.auth.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.time.*;
import java.util.*;
import java.util.logging.*;
import java.util.zip.*;

public class CoreNodeHandler implements HttpHandler
{
    private static final Logger LOG = Logging.LOG();

    private final CoreNode coreNode;
    private final boolean isPublicServer;

    public CoreNodeHandler(CoreNode coreNode, boolean isPublicServer) {
        this.coreNode = coreNode;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange)
    {
        long t1 = System.currentTimeMillis();
        DataInputStream din = new DataInputStream(exchange.getRequestBody());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.CORE_URL.length()).split("/");
        String method = subComponents[0];

        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method)
            {
                case "getChain":
                    AggregatedMetrics.GET_PUBLIC_KEY_CHAIN.inc();
                    getChain(din, dout);
                    break;
                case "signup":
                    AggregatedMetrics.SIGNUP.inc();
                    signup(din, dout);
                    break;
                case "startPaidSignup":
                    AggregatedMetrics.PAID_SIGNUP_START.inc();
                    startPaidSignup(din, dout, exchange);
                    break;
                case "completePaidSignup":
                    AggregatedMetrics.PAID_SIGNUP_COMPLETE.inc();
                    completePaidSignup(din, dout);
                    break;
                case "updateChain":
                    AggregatedMetrics.UPDATE_PUBLIC_KEY_CHAIN.inc();
                    updateChain(din, dout);
                    break;
                case "getPublicKey":
                    AggregatedMetrics.GET_PUBLIC_KEY.inc();
                    getPublicKey(din, dout);
                    break;
                case "getUsername":
                    AggregatedMetrics.GET_USERNAME.inc();
                    getUsername(din, dout);
                    break;
                case "getUsernamesGzip":
                    AggregatedMetrics.GET_ALL_USERNAMES.inc();
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    getAllUsernamesGzip(subComponents.length > 1 ? subComponents[1] : "", din, dout);
                    break;
                case "migrateUser":
                    AggregatedMetrics.MIGRATE_USER.inc();
                    migrateUser(din, dout);
                    break;
                default:
                    throw new IOException("Unknown pkinode method!");
            }

            dout.flush();
            dout.close();
            byte[] b = bout.toByteArray();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            e.printStackTrace();
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            LOG.info("Corenode server handled " + method + " request in: " + (t2 - t1) + " mS");
        }
    }

    void getChain(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);

        List<UserPublicKeyLink> chain = coreNode.getChain(username).get();
        dout.write(new CborObject.CborList(chain).serialize());
    }

    void signup(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        byte[] raw = Serialize.deserializeByteArray(din, 2 * UserPublicKeyLink.MAX_SIZE);
        UserPublicKeyLink res = UserPublicKeyLink.fromCbor(CborObject.fromByteArray(raw));
        OpLog ops = OpLog.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 64*1024)));
        ProofOfWork proof = ProofOfWork.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 100)));
        String token = CoreNodeUtils.deserializeString(din);
        Optional<RequiredDifficulty> err = coreNode.signup(username, res, ops, proof, token).get();
        dout.writeBoolean(err.isEmpty());
        if (err.isPresent())
            dout.writeInt(err.get().requiredDifficulty);
    }

    void startPaidSignup(DataInputStream din, DataOutputStream dout, HttpExchange exchange) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        UserPublicKeyLink chain = UserPublicKeyLink.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 2 * UserPublicKeyLink.MAX_SIZE)));
        ProofOfWork proof = ProofOfWork.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 100)));
        Either<PaymentProperties, RequiredDifficulty> res = coreNode.startPaidSignup(username, chain, proof).get();
        dout.writeBoolean(res.isA());
        if (res.isA())
            Serialize.serialize(res.a().serialize(), dout);
        else
            dout.writeInt(res.b().requiredDifficulty);
    }

    void completePaidSignup(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        byte[] raw = Serialize.deserializeByteArray(din, 2 * UserPublicKeyLink.MAX_SIZE);
        UserPublicKeyLink chain = UserPublicKeyLink.fromCbor(CborObject.fromByteArray(raw));
        OpLog ops = OpLog.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 64*1024)));
        ProofOfWork proof = ProofOfWork.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 100)));
        byte[] signedSpaceRequest = Serialize.deserializeByteArray(din, 64 * 1024);
        PaymentProperties res = coreNode.completePaidSignup(username, chain, ops, signedSpaceRequest, proof).get();
        dout.write(res.serialize());
    }

    void updateChain(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        byte[] raw = Serialize.deserializeByteArray(din, 2 * UserPublicKeyLink.MAX_SIZE);
        List<UserPublicKeyLink> res = ((CborObject.CborList)CborObject.fromByteArray(raw)).map(UserPublicKeyLink::fromCbor);
        ProofOfWork proof = ProofOfWork.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 100)));
        String token = CoreNodeUtils.deserializeString(din);
        Optional<RequiredDifficulty> err = coreNode.updateChain(username, res, proof, token).get();
        dout.writeBoolean(err.isEmpty());
        if (err.isPresent())
            dout.writeInt(err.get().requiredDifficulty);
    }

    void migrateUser(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        byte[] raw = Serialize.deserializeByteArray(din, 4096);
        List<UserPublicKeyLink> newChain = ((CborObject.CborList)CborObject.fromByteArray(raw)).map(UserPublicKeyLink::fromCbor);
        Multihash currentStorageId = Cid.cast(Serialize.deserializeByteArray(din, 128));
        boolean hasBat = din.readBoolean();
        Optional<BatWithId> mirrorBat = hasBat ?
                Optional.of(BatWithId.fromCbor(CborObject.fromByteArray(Serialize.deserializeByteArray(din, 128)))) :
                Optional.empty();
        long seconds = din.readLong();
        long currentUsage = din.readLong();
        UserSnapshot state = coreNode.migrateUser(username, newChain, currentStorageId, mirrorBat, LocalDateTime.ofEpochSecond(seconds, 0, ZoneOffset.UTC), currentUsage).join();
        dout.write(state.serialize());
    }

    void getPublicKey(DataInputStream din, DataOutputStream dout) throws Exception
    {
        String username = CoreNodeUtils.deserializeString(din);
        Optional<PublicKeyHash> k = coreNode.getPublicKeyHash(username).get();
        dout.writeBoolean(k.isPresent());
        if (!k.isPresent())
            return;
        byte[] b = k.get().serialize();
        dout.writeInt(b.length);
        dout.write(b);
    }

    void getUsername(DataInputStream din, DataOutputStream dout) throws Exception
    {
        byte[] publicKey = CoreNodeUtils.deserializeByteArray(din);
        PublicKeyHash owner = PublicKeyHash.fromCbor(CborObject.fromByteArray(publicKey));
        String k = coreNode.getUsername(owner).get();
        if (k == null)
            throw new IllegalStateException("Unknown username for key: " + owner.toString());
        Serialize.serialize(k, dout);
    }

    void getAllUsernamesGzip(String prefix, DataInputStream din, DataOutputStream dout) throws Exception
    {
        List<String> res = coreNode.getUsernames(prefix).get();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        GZIPOutputStream gout = new GZIPOutputStream(bout);
        gout.write(JSONParser.toString(res).getBytes());
        gout.flush();
        gout.close();
        dout.write(bout.toByteArray());
    }

    public void close() throws IOException{
        coreNode.close();
    }
}
