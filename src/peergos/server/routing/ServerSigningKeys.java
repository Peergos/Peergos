package peergos.server.routing;

import io.libp2p.core.crypto.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.asymmetric.curve25519.*;
import peergos.shared.io.ipfs.*;

import java.util.*;

/** Converts between libp2p's Ed25519 server keys and Peergos signing keys. */
public class ServerSigningKeys {
    private static final byte[] ED25519_PROTOBUF_PREFIX = {0x08, 0x01, 0x12, 0x20};

    public static boolean isEd25519(PrivKey priv) {
        return priv.getKeyType() == crypto.pb.Crypto.KeyType.Ed25519;
    }

    public static SigningKeyPair fromLibp2p(PrivKey priv, Ed25519 signer) {
        if (! isEd25519(priv))
            throw new IllegalStateException("Only Ed25519 servers are in the routing log");
        return fromSeed(priv.raw(), signer);
    }

    public static SigningKeyPair fromSeed(byte[] seed, Ed25519 signer) {
        if (seed.length != 32)
            throw new IllegalStateException("Ed25519 seeds are 32 bytes");
        byte[] sk = new byte[64];
        byte[] pk = new byte[32];
        System.arraycopy(seed, 0, sk, 0, 32);
        signer.crypto_sign_keypair(pk, sk);
        return new SigningKeyPair(new Ed25519PublicKey(pk, signer), new Ed25519SecretKey(sk, signer));
    }

    public static byte[] seed(SigningKeyPair pair) {
        byte[] sk = ((CborObject.CborByteArray) ((CborObject.CborList) pair.secretSigningKey.toCbor()).value.get(1)).value;
        return Arrays.copyOfRange(sk, 0, 32);
    }

    /** The key inside an Ed25519 peerid, which is an identity multihash of the key's protobuf. */
    public static Optional<PublicSigningKey> fromPeerId(Multihash peerId, Ed25519 signer) {
        byte[] raw = peerId.getHash();
        if (peerId.type != Multihash.Type.id || raw.length != 36
                || ! Arrays.equals(Arrays.copyOfRange(raw, 0, 4), ED25519_PROTOBUF_PREFIX))
            return Optional.empty();
        return Optional.of(new Ed25519PublicKey(Arrays.copyOfRange(raw, 4, 36), signer));
    }
}
