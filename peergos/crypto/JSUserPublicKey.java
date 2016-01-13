package peergos.crypto;

import peergos.util.ArrayOps;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.Random;

public class JSUserPublicKey extends UserPublicKey
{
    private static ScriptEngineManager engineManager = new ScriptEngineManager();
    public static final ScriptEngine engine = engineManager.getEngineByName("nashorn");
    public static final Invocable invocable = (Invocable) engine;

    public static Random prng = new Random(0); // only used in testing so let's make it deterministic
    public static byte[] getRandomValues(int len) {
        byte[] in = new byte[len];
        prng.nextBytes(in);
        return in;
    }

    static {
        try {
            engine.eval("var navigator = {}, window = {}; window.crypto = {};\n window.crypto.getRandomValues = " +
                    "function (arr){\n" +
                    "    var jarr = Java.type('peergos.crypto.JSUserPublicKey').getRandomValues(arr.length);\n" +
                    "    for (var i=0; i < arr.length; i++) arr[i] = jarr[i];\n" +
                    "}\n" +
                    "" +
                    "function toByteArray(arr) {\n" +
                    "    var jarr = new (Java.type('byte[]'))(arr.length);" +
                    "    for (var i=0; i < jarr.length; i++) jarr[i] = arr[i];" +
                    "    return jarr;\n" +
                    "}\n" +
                    "" +
                    "function fromByteArray(arr) {\n" +
                    "    var res = new Uint8Array(arr.length);" +
                    "    for (var i=0; i < arr.length; i++) res[i] = arr[i];" +
                    "    return res;\n" +
                    "}\n" +
                    "" +
                    "function box(input, nonce, pubBox, secretBox) {" +
                    "    return window.nacl.box(input, nonce, pubBox, secretBox);" +
                    "}" +
                    "" +
                    "function unbox(cipher, nonce, pubBox, secretBox) {" +
                    "    return window.nacl.box.open(cipher, nonce, pubBox, secretBox);" +
                    "}" +
                    "" +
                    "function unsign(signature, publicSigningKey) {" +
                    "    return window.nacl.sign.open(signature, publicSigningKey);" +
                    "}" +
                    "" +
                    "function sign(message, secretSigningKey) {" +
                    "    return window.nacl.sign(message, secretSigningKey);" +
                    "}" +
                    "" +
                    "function sign_keypair(seed) {" +
                    "    var pk = new Uint8Array(32);\n" +
                    "    var sk = new Uint8Array(64);\n" +
                    "    for (var i = 0; i < 32; i++) sk[i] = seed[i];\n" +
                    "    window.nacl.lowlevel.crypto_sign_keypair(pk, sk, true);" +
                    "    var both = new Uint8Array(96);" +
                    "    for (var i = 0; i < 32; i++) both[i] = pk[i];" +
                    "    for (var i = 0; i < 64; i++) both[32+i] = sk[i];" +
                    "    return both;" +
                    "}");
            engine.eval(new InputStreamReader(JSUserPublicKey.class.getClassLoader().getResourceAsStream("ui/lib/scrypt.js")));
            engine.eval(new InputStreamReader(JSUserPublicKey.class.getClassLoader().getResourceAsStream("ui/lib/blake2s.js")));
            engine.eval(new InputStreamReader(JSUserPublicKey.class.getClassLoader().getResourceAsStream("ui/lib/nacl.js")));
            engine.eval(new InputStreamReader(JSUserPublicKey.class.getClassLoader().getResourceAsStream("ui/lib/api.js")));
            engine.eval("Object.freeze(this);");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }


    public JSUserPublicKey(byte[] publicSigningKey, byte[] publicBoxingKey) {
        super(publicSigningKey, publicBoxingKey);
    }

    public byte[] encryptMessageFor(byte[] input, byte[] ourSecretBoxingKey)
    {
        byte[] paddedMessage = new byte[PADDING_LENGTH + input.length];
        System.arraycopy(input, 0, paddedMessage, PADDING_LENGTH, input.length);
        byte[] nonce = createNonce();
        byte[] res = null;
        try {
            res = (byte[]) invocable.invokeFunction("toByteArray", invocable.invokeFunction("box",
                    invocable.invokeFunction("fromByteArray", input),
                    invocable.invokeFunction("fromByteArray", nonce),
                    invocable.invokeFunction("fromByteArray", getPublicBoxingKey()),
                    invocable.invokeFunction("fromByteArray", ourSecretBoxingKey)));
        } catch (Exception e) {throw new RuntimeException(e);}
        return ArrayOps.concat(res, nonce);
    }

    public byte[] createNonce() {
        try {
            Object nonce = invocable.invokeFunction("createNonce");
            return (byte[]) invocable.invokeFunction("toByteArray", nonce);
        } catch (Exception e) {throw new RuntimeException(e);}
    }

    public byte[] unsignMessage(byte[] signed)
    {
        try {
            Object res = invocable.invokeFunction("unsign",
                    invocable.invokeFunction("fromByteArray", signed),
                    invocable.invokeFunction("fromByteArray", getPublicSigningKey()));
            if (res == null)
                throw new IllegalStateException("Invalid signature: unsign returned null.");
            return (byte[]) invocable.invokeFunction("toByteArray", res);
        } catch (Exception e) {throw new RuntimeException(e);}
    }

    public static void main(String[] args) throws Exception {
        byte[] secretBoxingKey = getRandomValues(32);
        byte[] publicBoxingKey = new byte[32];
        TweetNaCl.crypto_scalarmult_base(publicBoxingKey, secretBoxingKey);
        byte[] secretSigningKey = new byte[64];
        byte[] publicSigningKey = new byte[32];
        byte[] signSeed = new byte[32];//getRandomValues(32);
        System.arraycopy(signSeed, 0, secretSigningKey, 0, 32);
        TweetNaCl.crypto_sign_keypair(publicSigningKey, secretSigningKey, true);
        // verify sign keygen
        byte[] jsSignPair = (byte[]) invocable.invokeFunction("toByteArray", invocable.invokeFunction("sign_keypair",
                invocable.invokeFunction("fromByteArray", signSeed)));
        byte[] jsSecretSignKey = Arrays.copyOfRange(jsSignPair, 32, 96);
        byte[] jsPublicSignKey = Arrays.copyOfRange(jsSignPair, 0, 32);
        if (!Arrays.equals(secretSigningKey, jsSecretSignKey))
            throw new IllegalStateException("Signing key generation invalid, different secret keys!");
        if (!Arrays.equals(publicSigningKey, jsPublicSignKey))
            throw new IllegalStateException("Signing key generation invalid, different public keys!");
        if (!Arrays.equals(publicSigningKey, Arrays.copyOfRange(secretSigningKey, 32, 64)))
            throw new IllegalStateException("Signing public key != second half of secret key!");

        User juser = new User(secretSigningKey, secretBoxingKey, publicSigningKey, publicBoxingKey);
        JSUser jsuser = new JSUser(juser.getSecretSigningKey(), juser.getSecretBoxingKey(), juser.getPublicSigningKey(), juser.getPublicBoxingKey());
        byte[] message = "G'day mate!".getBytes();

        // box
        byte[] cipher = jsuser.encryptMessageFor(message, juser.getSecretBoxingKey());
        byte[] cipher2 = juser.encryptMessageFor(message, juser.getSecretBoxingKey());

        // unbox
        byte[] clear = juser.decryptMessage(cipher, jsuser.getPublicBoxingKey());
        if (!Arrays.equals(clear, message)) {
            throw new IllegalStateException("JS -> J, Decrypted message != original: "+new String(clear) + " != "+new String(message));
        }
        byte[] clear2 = jsuser.decryptMessage(cipher2, jsuser.getPublicBoxingKey());
        if (!Arrays.equals(clear2, message))
            throw new IllegalStateException("J -> JS, Decrypted message != original: "+new String(clear2) + " != "+new String(message));

        // sign and unsign
        byte[] sig = juser.signMessage(message);
        byte[] sig2 = jsuser.signMessage(message);
        if (!Arrays.equals(sig, sig2)) {
            System.out.println("J : "+ArrayOps.bytesToHex(sig));
            System.out.println("JS: "+ArrayOps.bytesToHex(sig2));
            throw new IllegalStateException("Signatures not equal! " + ArrayOps.bytesToHex(sig) + " != " + ArrayOps.bytesToHex(sig2));
        }

        byte[] unsigned = juser.unsignMessage(sig);
        if (!Arrays.equals(unsigned, message))
            throw new IllegalStateException("J: Unsigned message != original! ");
//        for (int i=0; i < sig.length; i++)
//            System.out.printf((sig[i]&0xff) + ", ");
        byte[] unsigned2 = jsuser.unsignMessage(sig2);
        if (!Arrays.equals(unsigned2, message))
            throw new IllegalStateException("JS: Unsigned message != original! ");
        System.out.println("Passed all tests!");
    }
}
