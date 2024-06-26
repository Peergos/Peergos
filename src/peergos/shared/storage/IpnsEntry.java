package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.io.ipfs.*;
import peergos.shared.resolution.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class IpnsEntry {
    public final byte[] signature, data;

    public IpnsEntry(byte[] signature, byte[] data) {
        this.signature = signature;
        this.data = data;
    }

    private CompletableFuture<byte[]> verifySignature(Multihash signer, peergos.shared.Crypto crypto) {
        if (! signer.isIdentity())
            throw new IllegalStateException("Only Ed25519 keys are supported for IPNS in client!");
        byte[] pubKeymaterial = Arrays.copyOfRange(signer.getHash(), 4, 36);
        Ed25519PublicKey pub = new Ed25519PublicKey(pubKeymaterial, crypto.signer);
        return pub.unsignMessage(ArrayOps.concat(ArrayOps.concat(signature, "ipns-signature:".getBytes()), data));
    }

    public CompletableFuture<ResolutionRecord> getValue(Multihash signer, peergos.shared.Crypto crypto) {
        CborObject cbor = CborObject.fromByteArray(data);
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for IpnsEntry!");
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        ResolutionRecord result = ResolutionRecord.fromCbor(CborObject.fromByteArray(map.getByteArray("Value")));
        // hard code legacy RSA rotations to avoid an RSA implementation in client
        if (signer.equals(Multihash.fromBase58("QmPqn9a1tJLpMtaCz1DSQNMAfsv6qXEx6XU2eLMTc2DVV4")) &&
                result.moved && result.host.isPresent() &&
                result.host.get().equals(Multihash.fromBase58("12D3KooWEnCzE4uSeniFaCGXQuV1UnYkvqvbQJnYC363S2abgknr")))
            return Futures.of(result);
        if (signer.equals(Multihash.fromBase58("QmcoDbhCiVXGrWs6rwBvB59Gm44veo7Qxn2zmRnPw7BaCH")) &&
                result.moved && result.host.isPresent() &&
                result.host.get().equals(Multihash.fromBase58("12D3KooWFv6ZcoUKyaDBB7nR5SQg6HpmEbDXad48WyFSyEk7xrSR")))
            return Futures.of(result);
        return verifySignature(signer, crypto).thenApply(x -> result);
    }

    public long getIpnsSequence() {
        CborObject cbor = CborObject.fromByteArray(data);
        if (! (cbor instanceof CborObject.CborMap))
            throw new IllegalStateException("Invalid cbor for IpnsEntry!");
        CborObject.CborMap map = (CborObject.CborMap) cbor;
        return map.getLong("Sequence");
    }

    public Map toJson() {
        Map res = new HashMap<>();
        res.put("sig", ArrayOps.bytesToHex(signature));
        res.put("data", ArrayOps.bytesToHex(data));
        return res;
    }

    public static IpnsEntry fromJson(Object json) {
        Map m = (Map) json;
        byte[] sig = ArrayOps.hexToBytes((String) m.get("sig"));
        byte[] data = ArrayOps.hexToBytes((String) m.get("data"));
        return new IpnsEntry(sig, data);
    }
}
