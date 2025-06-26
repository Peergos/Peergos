package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.crypto.random.JSNaCl;

import java.util.concurrent.CompletableFuture;

public interface Mlkem {

    CompletableFuture<Encapsulation> encapsulate(byte[] publicKeyBytes);

    CompletableFuture<byte[]> decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes);

    CompletableFuture<MlkemKeyPair> generateKeyPair();

    class Encapsulation {
        public final byte[] sharedSecret, cipherText;

        public Encapsulation(byte[] sharedSecret, byte[] cipherText) {
            this.sharedSecret = sharedSecret;
            this.cipherText = cipherText;
        }
    }

    class Javascript implements Mlkem {
        JSNaCl mlJs = new JSNaCl();

        @Override
        public CompletableFuture<Encapsulation> encapsulate(byte[] publicKeyBytes) {
            return mlJs.encapsulate(publicKeyBytes)
                    .thenApply(encapsulated -> {
                        byte[] sharedSecret = new byte[encapsulated[0].length];
                        for (int i=0; i < sharedSecret.length; i++)
                            sharedSecret[i] = encapsulated[0][i];
                        byte[] cipherText = new byte[encapsulated[1].length];
                        for (int i=0; i < cipherText.length; i++)
                            cipherText[i] = encapsulated[1][i];
                        return new Encapsulation(sharedSecret, cipherText);
                    });
        }

        @Override
        public CompletableFuture<byte[]> decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes) {
            return mlJs.decapsulate(cipherTextBytes, secretKeyBytes);
        }

        @Override
        public CompletableFuture<MlkemKeyPair> generateKeyPair() {
            return mlJs.generateMlkemKeyPair().thenApply(keyPair -> {
                MlkemPublicKey publicKey = new MlkemPublicKey(keyPair[0], this);
                MlkemSecretKey secretKey = new MlkemSecretKey(keyPair[1], this);
                return new MlkemKeyPair(publicKey, secretKey);
            });
        }
    }
}
