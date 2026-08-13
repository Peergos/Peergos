package peergos.server.webauthn;

import java.io.IOException;
import java.util.List;

/** A security key, however this platform reaches one.
 *
 *  Everything above this line is shared - the localhost endpoint, the rpId pinning, the
 *  origin rule and the polyfill in the page - and only the thing that talks to the key
 *  differs per platform. The clientDataJSON is built above us and passed in whole rather
 *  than hashed, because CTAP2 wants the hash and webauthn.dll wants the bytes.
 */
public interface SecurityKey {

    Ctap2.Credential makeCredential(byte[] clientDataJson,
                                    String rpId,
                                    String rpName,
                                    byte[] userId,
                                    String userName,
                                    String displayName,
                                    List<Long> algorithms,
                                    long deadline) throws IOException;

    Ctap2.Credential getAssertion(byte[] clientDataJson,
                                  String rpId,
                                  List<byte[]> allowedCredentials,
                                  long deadline) throws IOException;

    /** The desktop host's window, so a platform prompt can be modal to it.
     *
     *  Only windows has anything to be modal to: the linux path draws no ui of its own,
     *  the key itself blinks.
     */
    default void setWindowHandle(long handle) {}

    static SecurityKey forThisPlatform() {
        if (System.getProperty("os.name", "").toLowerCase().contains("win"))
            return windowsKey();
        return new LinuxSecurityKey();
    }

    /** Reflectively, because this jar is also dexed into the android app, where
     *  java.lang.foreign does not exist. Naming the class here would put it on the
     *  reachable graph. */
    private static SecurityKey windowsKey() {
        try {
            return (SecurityKey) Class.forName("peergos.server.webauthn.WindowsSecurityKey")
                    .getDeclaredConstructor()
                    .newInstance();
        } catch (ReflectiveOperationException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ? e.getCause() : e;
            throw new IllegalStateException("No security key support on this windows build: " + cause, cause);
        }
    }
}
