package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public interface ServerMessager {

    CompletableFuture<List<ServerMessage>> getMessages(String username, byte[] auth);

    CompletableFuture<Boolean> sendMessage(String username, byte[] signedBody);

    default CompletableFuture<List<ServerMessage>> getMessages(String username, SecretSigningKey signer) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.SERVER_MESSAGE_URL + "retrieve", System.currentTimeMillis());
        return req.sign(signer)
                .thenCompose(auth -> getMessages(username, auth));
    }

    default CompletableFuture<Boolean> sendMessage(String username, ServerMessage message, SecretSigningKey signer) {
        return signer.signMessage(message.serialize())
                .thenCompose(signedBody -> sendMessage(username, signedBody));
    }

    class HTTP implements ServerMessager {
        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<List<ServerMessage>> getMessages(String username, byte[] auth) {
            return poster.get(Constants.SERVER_MESSAGE_URL + "retrieve?username=" + username + "&auth=" + ArrayOps.bytesToHex(auth))
                    .thenApply(res ->
                            ((CborObject.CborList)CborObject.fromByteArray(res)).value
                                    .stream()
                                    .map(ServerMessage::fromCbor)
                                    .collect(Collectors.toList()));
        }

        @Override
        public CompletableFuture<Boolean> sendMessage(String username, byte[] signedBody) {
            return poster.post(Constants.SERVER_MESSAGE_URL + "send?username=" + username, signedBody, false)
                    .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
        }
    }
}
