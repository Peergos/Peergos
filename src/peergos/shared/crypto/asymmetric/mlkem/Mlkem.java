package peergos.shared.crypto.asymmetric.mlkem;

import peergos.shared.crypto.random.JSNaCl;

public interface Mlkem {

    Encapsulation encapsulate(byte[] publicKeyBytes);

    byte[] decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes);

    MlkemKeyPair generateKeyPair();

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
        public Encapsulation encapsulate(byte[] publicKeyBytes) {
            byte[][] encapsulated = mlJs.encapsulate(publicKeyBytes);
            return new Encapsulation(encapsulated[0], encapsulated[1]);
        }

        @Override
        public byte[] decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes) {
            return mlJs.decapsulate(cipherTextBytes, secretKeyBytes);
        }

        @Override
        public MlkemKeyPair generateKeyPair() {
            byte[][] keyPair = mlJs.generateMlkemKeyPair();
            MlkemPublicKey publicKey = new MlkemPublicKey(keyPair[0], this);
            MlkemSecretKey secretKey = new MlkemSecretKey(keyPair[1], this);
            return new MlkemKeyPair(publicKey, secretKey);
        }
    }
}
