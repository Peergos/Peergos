package peergos.shared.crypto.asymmetric.mlkem;

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
        @Override
        public Encapsulation encapsulate(byte[] publicKeyBytes) {
            throw new IllegalStateException("TODO");
        }

        @Override
        public byte[] decapsulate(byte[] cipherTextBytes, byte[] secretKeyBytes) {
            throw new IllegalStateException("TODO");
        }

        @Override
        public MlkemKeyPair generateKeyPair() {
            throw new IllegalStateException("TODO");
        }
    }
}
