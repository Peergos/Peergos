package peergos.shared.messaging;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** Short lived signals between the members of a chat: typing, presence and read receipts.
 *
 *  A signal is relayed by the server of the account that sends or polls it, held in RAM only and
 *  dropped after a short time - nothing is persisted. The chat members encrypt and sign the payload
 *  before it gets here, so the server only ever sees opaque channel and slot ids and ciphertext.
 *
 *  A plain signal (empty slot) is an event, like "is typing", and expires within seconds. A signal
 *  with a slot replaces the previous one in that slot and is kept for longer, so a member who
 *  connects later still sees the latest presence or read position of the others.
 */
public interface EphemeralSignals {

    int MAX_CHANNEL_SIZE = 32;
    int MAX_SLOT_SIZE = 32;
    int MAX_PAYLOAD_SIZE = 1024;
    int MAX_WAIT_MILLIS = 25_000;

    /** Relay a signal, signed by the sender's identity.
     *
     * @param signedPost a {@link Post} signed by the identity key of username
     */
    CompletableFuture<Boolean> post(String username, byte[] signedPost);

    /** The signals on a channel after the given sequence number, waiting up to waitMillis for one to arrive.
     *
     * @param auth a {@link TimeLimitedClient.SignedRequest} for the poll path, signed by the identity key of username
     */
    CompletableFuture<Batch> poll(String username, byte[] channel, long since, int waitMillis, byte[] auth);

    default CompletableFuture<Boolean> post(String username, Post post, SecretSigningKey identity) {
        return identity.signMessage(post.serialize())
                .thenCompose(signed -> post(username, signed));
    }

    default CompletableFuture<Batch> poll(String username, byte[] channel, long since, int waitMillis, SecretSigningKey identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.EPHEMERAL_URL + "poll", System.currentTimeMillis());
        return req.sign(identity)
                .thenCompose(auth -> poll(username, channel, since, waitMillis, auth));
    }

    class Post implements Cborable {
        public final byte[] channel, slot, payload;
        public final long createdEpochMillis;

        public Post(byte[] channel, byte[] slot, byte[] payload, long createdEpochMillis) {
            if (channel.length == 0 || channel.length > MAX_CHANNEL_SIZE)
                throw new IllegalArgumentException("Invalid channel size: " + channel.length);
            if (slot.length > MAX_SLOT_SIZE)
                throw new IllegalArgumentException("Slot too large: " + slot.length);
            if (payload.length > MAX_PAYLOAD_SIZE)
                throw new IllegalArgumentException("Signal too large: " + payload.length);
            this.channel = channel;
            this.slot = slot;
            this.payload = payload;
            this.createdEpochMillis = createdEpochMillis;
        }

        public boolean isSticky() {
            return slot.length > 0;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("c", new CborObject.CborByteArray(channel));
            state.put("s", new CborObject.CborByteArray(slot));
            state.put("p", new CborObject.CborByteArray(payload));
            state.put("t", new CborObject.CborLong(createdEpochMillis));
            return CborObject.CborMap.build(state);
        }

        public static Post fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for signal! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Post(m.getByteArray("c"), m.getByteArray("s"), m.getByteArray("p"), m.getLong("t"));
        }
    }

    class Signal implements Cborable {
        public final long seq;
        public final byte[] slot, payload;

        public Signal(long seq, byte[] slot, byte[] payload) {
            this.seq = seq;
            this.slot = slot;
            this.payload = payload;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("n", new CborObject.CborLong(seq));
            state.put("s", new CborObject.CborByteArray(slot));
            state.put("p", new CborObject.CborByteArray(payload));
            return CborObject.CborMap.build(state);
        }

        public static Signal fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for signal! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Signal(m.getLong("n"), m.getByteArray("s"), m.getByteArray("p"));
        }
    }

    /** The signals after a poll's sequence number, and the sequence number to poll from next. */
    class Batch implements Cborable {
        public final List<Signal> signals;
        public final long next;

        public Batch(List<Signal> signals, long next) {
            this.signals = signals;
            this.next = next;
        }

        @Override
        public CborObject toCbor() {
            SortedMap<String, Cborable> state = new TreeMap<>();
            state.put("s", new CborObject.CborList(signals));
            state.put("n", new CborObject.CborLong(next));
            return CborObject.CborMap.build(state);
        }

        public static Batch fromCbor(Cborable cbor) {
            if (! (cbor instanceof CborObject.CborMap))
                throw new IllegalStateException("Invalid cbor for signal batch! " + cbor);
            CborObject.CborMap m = (CborObject.CborMap) cbor;
            return new Batch(m.getList("s", Signal::fromCbor), m.getLong("n"));
        }
    }

    class HTTP implements EphemeralSignals {
        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<Boolean> post(String username, byte[] signedPost) {
            return poster.post(Constants.EPHEMERAL_URL + "post?username=" + username, signedPost, false)
                    .thenApply(res -> ((CborObject.CborBoolean) CborObject.fromByteArray(res)).value);
        }

        @Override
        public CompletableFuture<Batch> poll(String username, byte[] channel, long since, int waitMillis, byte[] auth) {
            int wait = Math.max(0, Math.min(waitMillis, MAX_WAIT_MILLIS));
            // the request stays open while the server waits, so give it that long on top of a normal call
            return poster.post(Constants.EPHEMERAL_URL + "poll?username=" + username
                            + "&channel=" + ArrayOps.bytesToHex(channel)
                            + "&since=" + since
                            + "&wait=" + wait
                            + "&auth=" + ArrayOps.bytesToHex(auth), new byte[0], false,
                            HttpPoster.DEFAULT_TIMEOUT_MILLIS + wait)
                    .thenApply(res -> Batch.fromCbor(CborObject.fromByteArray(res)));
        }
    }

    /** For a node that has no relay; signals are dropped and polls return nothing. */
    class Disabled implements EphemeralSignals {
        @Override
        public CompletableFuture<Boolean> post(String username, byte[] signedPost) {
            return Futures.of(false);
        }

        @Override
        public CompletableFuture<Batch> poll(String username, byte[] channel, long since, int waitMillis, byte[] auth) {
            return Futures.of(new Batch(Collections.emptyList(), since));
        }
    }
}
