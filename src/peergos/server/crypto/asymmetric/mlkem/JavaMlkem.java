package peergos.server.crypto.asymmetric.mlkem;

import peergos.server.crypto.asymmetric.mlkem.fips203.FIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.MimicloneFIPS203;
import peergos.server.crypto.asymmetric.mlkem.fips203.ParameterSet;
import peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.DecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.EncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.KeyPair;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem.MLKEMDecapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.key.mlkem.MLKEMEncapsulationKey;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.CipherText;
import peergos.server.crypto.asymmetric.mlkem.fips203.message.MLKEMCipherText;
import peergos.shared.crypto.asymmetric.mlkem.Mlkem;
import peergos.shared.crypto.asymmetric.mlkem.MlkemKeyPair;
import peergos.shared.crypto.asymmetric.mlkem.MlkemPublicKey;
import peergos.shared.crypto.asymmetric.mlkem.MlkemSecretKey;
import peergos.shared.util.Futures;

import java.util.concurrent.CompletableFuture;

public class JavaMlkem implements Mlkem {

    private static FIPS203 fips203 = MimicloneFIPS203.create(ParameterSet.ML_KEM_1024);

    @Override
    public CompletableFuture<MlkemKeyPair> generateKeyPair() {
        KeyPair keyPair = fips203.generateKeyPair();
        MlkemPublicKey publicKey = new MlkemPublicKey(keyPair.getEncapsulationKey().getBytes(), this);
        MlkemSecretKey secretKey = new MlkemSecretKey(keyPair.getDecapsulationKey().getBytes(), this);
        return Futures.of(new MlkemKeyPair(publicKey, secretKey));
    }

    @Override
    public CompletableFuture<Encapsulation> encapsulate(byte[] publicKeyBytes) {
        EncapsulationKey publicKey = MLKEMEncapsulationKey.create(publicKeyBytes);
        peergos.server.crypto.asymmetric.mlkem.fips203.encaps.Encapsulation encapsulated = fips203.encapsulate(publicKey);
        return Futures.of(new Encapsulation(encapsulated.getSharedSecretKey().getBytes(), encapsulated.getCipherText().getBytes()));
    }

    /**
     *
     * @param cipherTextBytes
     * @return sharedSecret
     */
    @Override
    public CompletableFuture<byte[]> decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes) {
        CipherText cipherText = MLKEMCipherText.create(cipherTextBytes);
        DecapsulationKey secretKey = MLKEMDecapsulationKey.create(secretKeyBytes);
        return Futures.of(fips203.decapsulate(secretKey, cipherText).getBytes());
    }
}
