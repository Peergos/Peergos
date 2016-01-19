package peergos.crypto.asymmetric.curve25519;

import peergos.crypto.TweetNaCl;

import java.util.Arrays;

public class JSUser extends JSUserPublicKey
{

    byte[] secretSigningKey, secretBoxingKey;

    public JSUser(byte[] secretSigningKey, byte[] secretBoxingKey, byte[] publicSigningKey, byte[] publicBoxingKey) {
        super(publicSigningKey, publicBoxingKey);
        this.secretSigningKey = secretSigningKey;
        this.secretBoxingKey = secretBoxingKey;
    }

    public byte[] signMessage(byte[] message)
    {
        byte[] res = null;
        try {
            res = (byte[]) invocable.invokeFunction("toByteArray", invocable.invokeFunction("sign",
                    invocable.invokeFunction("fromByteArray", message),
                    invocable.invokeFunction("fromByteArray", secretSigningKey)));
        } catch (Exception e) {e.printStackTrace();}
        return res;
    }

    public byte[] decryptMessage(byte[] cipher, byte[] theirPublicBoxingKey)
    {
        byte[] nonce = Arrays.copyOfRange(cipher, cipher.length - TweetNaCl.BOX_NONCE_BYTES, cipher.length);
        cipher = Arrays.copyOfRange(cipher, 0, cipher.length - TweetNaCl.BOX_NONCE_BYTES);
        byte[] res = null;
        try {
            res = (byte[]) invocable.invokeFunction("toByteArray", invocable.invokeFunction("unbox",
                    invocable.invokeFunction("fromByteArray", cipher),
                    invocable.invokeFunction("fromByteArray", nonce),
                    invocable.invokeFunction("fromByteArray", theirPublicBoxingKey),
                    invocable.invokeFunction("fromByteArray", secretBoxingKey)));
        } catch (Exception e) {e.printStackTrace();}
        return res;
    }
}
