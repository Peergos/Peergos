package peergos.shared.routing;

import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/** Every routing signature is over a context string and the record body, so a signature for one record type
 *  can never be replayed as another.
 */
public class Signing {
    public static final String IDENTITY_INCEPTION = "peergos/routing/v1/identity-inception";
    public static final String IDENTITY_ROTATION = "peergos/routing/v1/identity-rotation";
    public static final String IDENTITY_MOVE = "peergos/routing/v1/identity-move";
    public static final String ADMISSION = "peergos/routing/v1/admission";
    public static final String SERVER_INCEPTION = "peergos/routing/v1/server-inception";
    public static final String SERVER_ROTATION = "peergos/routing/v1/server-rotation";
    public static final String SERVER_ADDRESSES = "peergos/routing/v1/server-addresses";

    public static byte[] message(String context, byte[] body) {
        byte[] res = new byte[context.length() + 1 + body.length];
        for (int i = 0; i < context.length(); i++)
            res[i] = (byte) context.charAt(i);
        System.arraycopy(body, 0, res, context.length() + 1, body.length);
        return res;
    }

    public static CompletableFuture<byte[]> sign(SecretSigningKey signer, String context, byte[] body) {
        return signer.signatureOnly(message(context, body));
    }

    public static CompletableFuture<Boolean> verify(PublicSigningKey signer, String context, byte[] body, byte[] signature) {
        byte[] message = message(context, body);
        try {
            return signer.unsignMessage(ArrayOps.concat(signature, message))
                    .thenApply(unsigned -> Arrays.equals(unsigned, message))
                    .exceptionally(t -> false);
        } catch (Exception e) {
            return Futures.of(false);
        }
    }
}
