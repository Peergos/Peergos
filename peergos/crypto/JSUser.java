package peergos.crypto;

import java.util.Arrays;

public class JSUser extends JSUserPublicKey
{

    Object ourKeys;
    byte[] secretSigningKey, secretBoxingKey;

    public JSUser(byte[] secretSigningKey, byte[] secretBoxingKey, byte[] publicSigningKey, byte[] publicBoxingKey) {
        super(publicSigningKey, publicBoxingKey);
        this.secretSigningKey = secretSigningKey;
        this.secretBoxingKey = secretBoxingKey;
//        try {
//            ourKeys = invocable.invokeFunction("User", publicSigningKey, publicBoxingKey);
//        } catch (Exception e) {e.printStackTrace();}
    }

    public byte[] signMessage(byte[] message)
    {
        byte[] signedMessage = new byte[message.length + OurTweetNaCl.crypto_sign_ed25519_tweet_BYTES];
        OurTweetNaCl.crypto_sign(signedMessage, message, message.length, secretSigningKey);
        return signedMessage;
    }

    public byte[] decryptMessage(byte[] cipher, byte[] theirPublicBoxingKey)
    {
        byte[] nonce = Arrays.copyOfRange(cipher, cipher.length - OurTweetNaCl.crypto_box_curve25519xsalsa20poly1305_tweet_NONCEBYTES, cipher.length);
        cipher = Arrays.copyOfRange(cipher, 0, cipher.length - OurTweetNaCl.crypto_box_curve25519xsalsa20poly1305_tweet_NONCEBYTES);
        byte[] rawText = new byte[cipher.length];
        OurTweetNaCl.crypto_box_open(rawText, cipher, cipher.length, nonce, theirPublicBoxingKey, secretBoxingKey);
        return Arrays.copyOfRange(rawText, 32, rawText.length);
    }
}
