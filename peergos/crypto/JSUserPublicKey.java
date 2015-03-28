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
    public static void getRandomValues(byte[] in) {
        prng.nextBytes(in);
    }

    static {
        try {
            engine.eval("var navigator = {}, window = {}; window.crypto = {};\n window.crypto.getRandomValues = " +
                    "function (arr){\n" +
                    "    var jarr = Java.to(arr, 'byte[]');" +
                    "    Java.type('peergos.crypto.JSUserPublicKey').getRandomValues(jarr);\n" +
                    "    for (var i=0; i < arr.length; i++) arr[i] = jarr[i];\n" +
                    "}\n" +
                    "" +
                    "function toByteArray(arr) {\n" +
                    "    return Java.to(arr, 'byte[]');\n" +
                    "}\n" +
                    "" +
                    "function fromByteArray(arr) {\n" +
                    "    var res = Uint8Array(arr.length);" +
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

    Object ourKeys;

    public JSUserPublicKey(byte[] publicSigningKey, byte[] publicBoxingKey) {
        super(publicSigningKey, publicBoxingKey);
        try {
            ourKeys = invocable.invokeFunction("UserPublicKey", publicSigningKey, publicBoxingKey);
        } catch (Exception e) {e.printStackTrace();}
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
                    invocable.invokeFunction("fromByteArray", publicBoxingKey),
                    invocable.invokeFunction("fromByteArray", ourSecretBoxingKey)));
        } catch (Exception e) {e.printStackTrace();}
        return ArrayOps.concat(res, nonce);
    }

    public byte[] createNonce() {
        try {
            Object nonce = invocable.invokeFunction("createNonce");
            return (byte[]) invocable.invokeFunction("toByteArray", nonce);
        } catch (Exception e) {e.printStackTrace();}
        return new byte[0];
    }

    public byte[] unsignMessage(byte[] signed)
    {
        byte[] res = null;
        try {
            res = (byte[]) invocable.invokeFunction("toByteArray", invocable.invokeFunction("unsign",
                    invocable.invokeFunction("fromByteArray", signed),
                    invocable.invokeFunction("fromByteArray", publicSigningKey)));
        } catch (Exception e) {e.printStackTrace();}
        return res;
    }

    public static void main(String[] args) {
        User juser = User.generateUserCredentials("Freddy", "password");
        JSUser jsuser = new JSUser(juser.secretSigningKey, juser.secretBoxingKey, juser.publicSigningKey, juser.publicBoxingKey);
        byte[] message = "G'day mate!".getBytes();
        // box and unbox
        byte[] res = jsuser.encryptMessageFor(message, juser.secretBoxingKey);
        byte[] clear = juser.decryptMessage(res, jsuser.publicBoxingKey);
        assert Arrays.equals(clear, message);

        byte[] res2 = juser.encryptMessageFor(message, juser.secretBoxingKey);
        byte[] clear2 = jsuser.decryptMessage(res2, jsuser.publicBoxingKey);
        assert Arrays.equals(clear2, message);

        // sign and unsign
        byte[] sig = juser.signMessage(message);
        byte[] unsigned = jsuser.unsignMessage(sig);
        assert Arrays.equals(unsigned, message);

        byte[] sig2 = jsuser.signMessage(message);
        assert Arrays.equals(sig, sig2);
        byte[] unsigned2 = juser.unsignMessage(sig2);
        assert Arrays.equals(unsigned2, message);
    }
}
