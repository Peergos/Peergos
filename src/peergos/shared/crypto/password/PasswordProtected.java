package peergos.shared.crypto.password;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.crypto.random.*;
import peergos.shared.crypto.symmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;

public class PasswordProtected {

    public static SecretGenerationAlgorithm getDefault() {
        return new ScryptGenerator(ScryptGenerator.MIN_MEMORY_COST, 8, 1, 32);
    }

    public static Cborable encryptWithPassword(byte[] cleartext,
                                               String password,
                                               LoginHasher hasher,
                                               Salsa20Poly1305 provider,
                                               SafeRandom random) {
        return encryptWithPassword(cleartext, password, getDefault(), hasher, provider, random);
    }

    public static Cborable encryptWithPassword(byte[] cleartext,
                                               String password,
                                               SecretGenerationAlgorithm algorithm,
                                               LoginHasher hasher,
                                               Salsa20Poly1305 provider,
                                               SafeRandom random) {
        List<Cborable> elements = new ArrayList<>();
        byte[] saltBytes = random.randomBytes(TweetNaCl.SECRETBOX_KEY_BYTES);
        String salt = ArrayOps.bytesToHex(saltBytes);

        try {
            byte[] derivedKeyBytes = hasher.hashToKeyBytes(salt, password, algorithm).get();
            SymmetricKey key = new TweetNaClKey(derivedKeyBytes, false, provider, random);
            byte[] nonce = key.createNonce();
            byte[] cipherText = key.encrypt(cleartext, nonce);
            elements.add(algorithm.toCbor());
            elements.add(new CborObject.CborByteArray(saltBytes));
            elements.add(new CborObject.CborByteArray(nonce));
            elements.add(new CborObject.CborByteArray(cipherText));
            return new CborObject.CborList(elements);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    public static byte[] decryptFromCbor(Cborable cbor,
                                         String password,
                                         LoginHasher hasher,
                                         Salsa20Poly1305 provider,
                                         SafeRandom random) {
        if (! (cbor instanceof CborObject.CborList))
            throw new IllegalStateException("Invalid cbor for SecretSigningKey! " + cbor);
        List<? extends Cborable> list = ((CborObject.CborList) cbor).value;

        SecretGenerationAlgorithm algorithm = SecretGenerationAlgorithm.fromCbor(list.get(0));
        String salt = ArrayOps.bytesToHex(((CborObject.CborByteArray) list.get(1)).value);
        byte[] nonce = ((CborObject.CborByteArray) list.get(2)).value;
        byte[] cipherText = ((CborObject.CborByteArray) list.get(3)).value;

        try {
            byte[] derivedKeyBytes = hasher.hashToKeyBytes(salt, password, algorithm).get();

            SymmetricKey key = new TweetNaClKey(derivedKeyBytes, false, provider, random);
            return key.decrypt(cipherText, nonce);
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }
}
