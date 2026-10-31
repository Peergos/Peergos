package peergos.server.messages;

import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.corenode.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.messaging.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

/** Relays {@link EphemeralSignals} between the local users of this server, in RAM only.
 *
 *  Nothing here is persisted: a restart drops every signal. Events expire after EVENT_TTL_MILLIS and
 *  sticky signals after STICKY_TTL_MILLIS. Every limit is enforced so that neither a single user nor
 *  all of them together can make this hold more than a bounded amount of memory or waiting requests.
 */
public class RamEphemeralSignals implements EphemeralSignals {

    public static final long EVENT_TTL_MILLIS = 30_000;
    // a chat's channel changes every day and clients send their sticky signals again in the new one,
    // so nothing needs keeping much beyond a day
    public static final long STICKY_TTL_MILLIS = 26 * 3600_000L;
    public static final int MAX_EVENTS_PER_CHANNEL = 64;
    public static final int MAX_SLOTS_PER_CHANNEL = 64;
    public static final int MAX_CHANNELS = 20_000;
    public static final int MAX_WAITING_POLLS = 500;
    public static final int MAX_WAITING_POLLS_PER_USER = 20;
    public static final int MAX_POSTS_PER_USER_PER_MINUTE = 120;
    /** Sticky signals one user may have stored across all channels, so the rate limit alone can't fill memory. */
    public static final int MAX_STICKY_PER_USER = 256;
    private static final int AUTH_WINDOW_SECONDS = 300;

    private final CoreNode pki;
    private final ContentAddressedStorage ipfs;
    private final Predicate<String> isLocalUser;
    private final Map<String, Channel> channels = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Integer> waitingPerUser = new HashMap<>();
    private final Map<String, Deque<Long>> recentPosts = new HashMap<>();
    private final Map<String, Integer> stickyPerUser = new HashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ephemeral-signals");
        t.setDaemon(true);
        return t;
    });
    // starts from the clock so that sequence numbers keep increasing across a restart, and a client
    // polling with one from before it doesn't miss what arrives after
    private long seq = System.currentTimeMillis() * 1000;
    private int waiting = 0;

    public RamEphemeralSignals(CoreNode pki, ContentAddressedStorage ipfs, Predicate<String> isLocalUser) {
        this.pki = pki;
        this.ipfs = ipfs;
        this.isLocalUser = isLocalUser;
        timer.scheduleAtFixedRate(this::expire, 60, 60, TimeUnit.SECONDS);
    }

    private static class Stored {
        final long seq, expiry;
        final byte[] slot, payload;
        final String user;

        Stored(long seq, long expiry, byte[] slot, byte[] payload, String user) {
            this.seq = seq;
            this.expiry = expiry;
            this.slot = slot;
            this.payload = payload;
            this.user = user;
        }

        Signal toSignal() {
            return new Signal(seq, slot, payload);
        }
    }

    private static class Channel {
        final Deque<Stored> events = new ArrayDeque<>();
        final Map<String, Stored> sticky = new HashMap<>();
        final List<CompletableFuture<Boolean>> waiters = new ArrayList<>();

        boolean isEmpty() {
            return events.isEmpty() && sticky.isEmpty() && waiters.isEmpty();
        }

        void expire(long now, Consumer<Stored> dropped) {
            events.removeIf(s -> s.expiry < now);
            sticky.values().removeIf(s -> {
                if (s.expiry >= now)
                    return false;
                dropped.accept(s);
                return true;
            });
        }

        List<Signal> after(long since) {
            return Stream.concat(events.stream(), sticky.values().stream())
                    .filter(s -> s.seq > since)
                    .sorted(Comparator.comparingLong(s -> s.seq))
                    .map(Stored::toSignal)
                    .collect(Collectors.toList());
        }
    }

    private PublicSigningKey identityOf(String username) {
        if (! isLocalUser.test(username))
            throw new IllegalStateException("Signals are only relayed for users of this server");
        PublicKeyHash identity = pki.getPublicKeyHash(username).join()
                .orElseThrow(() -> new IllegalStateException("Unknown user"));
        return ipfs.getSigningKey(identity, identity).join()
                .orElseThrow(() -> new IllegalStateException("Couldn't retrieve signer key!"));
    }

    private synchronized void checkPostRate(String username, long now) {
        Deque<Long> times = recentPosts.computeIfAbsent(username, u -> new ArrayDeque<>());
        while (! times.isEmpty() && times.peekFirst() < now - 60_000)
            times.removeFirst();
        if (times.size() >= MAX_POSTS_PER_USER_PER_MINUTE)
            throw new IllegalStateException("Please wait before sending more signals!");
        times.addLast(now);
    }

    @Override
    public CompletableFuture<Boolean> post(String username, byte[] signedPost) {
        PublicSigningKey signer = identityOf(username);
        Post post = Post.fromCbor(CborObject.fromByteArray(signer.unsignMessage(signedPost).join()));
        long now = System.currentTimeMillis();
        if (Math.abs(post.createdEpochMillis - now) > AUTH_WINDOW_SECONDS * 1_000L)
            throw new IllegalStateException("Stale auth time, is your clock accurate?");
        checkPostRate(username, now);

        String channelId = ArrayOps.bytesToHex(post.channel);
        List<CompletableFuture<Boolean>> toWake;
        synchronized (this) {
            Channel channel = channels.get(channelId);
            if (channel == null) {
                if (channels.size() >= MAX_CHANNELS)
                    evictOldest();
                channel = new Channel();
                channels.put(channelId, channel);
            }
            long n = ++seq;
            if (post.isSticky()) {
                String slotId = ArrayOps.bytesToHex(post.slot);
                Stored previous = channel.sticky.get(slotId);
                if (previous == null && channel.sticky.size() >= MAX_SLOTS_PER_CHANNEL)
                    throw new IllegalStateException("Too many slots in channel");
                boolean replacesOwn = previous != null && previous.user.equals(username);
                if (! replacesOwn && stickyPerUser.getOrDefault(username, 0) >= MAX_STICKY_PER_USER)
                    throw new IllegalStateException("Too many stored signals, please wait");
                if (previous != null)
                    releaseSticky(previous);
                channel.sticky.put(slotId, new Stored(n, now + STICKY_TTL_MILLIS, post.slot, post.payload, username));
                stickyPerUser.merge(username, 1, Integer::sum);
            } else {
                channel.events.addLast(new Stored(n, now + EVENT_TTL_MILLIS, post.slot, post.payload, username));
                while (channel.events.size() > MAX_EVENTS_PER_CHANNEL)
                    channel.events.removeFirst();
            }
            toWake = new ArrayList<>(channel.waiters);
            channel.waiters.clear();
        }
        toWake.forEach(w -> w.complete(true));
        return Futures.of(true);
    }

    @Override
    public CompletableFuture<Batch> poll(String username, byte[] channelBytes, long since, int waitMillis, byte[] auth) {
        if (channelBytes.length == 0 || channelBytes.length > MAX_CHANNEL_SIZE)
            throw new IllegalArgumentException("Invalid channel size: " + channelBytes.length);
        TimeLimited.isAllowed(Constants.EPHEMERAL_URL + "poll", auth, AUTH_WINDOW_SECONDS, identityOf(username));
        String channelId = ArrayOps.bytesToHex(channelBytes);
        int wait = Math.max(0, Math.min(waitMillis, MAX_WAIT_MILLIS));

        CompletableFuture<Boolean> arrival = new CompletableFuture<>();
        synchronized (this) {
            Channel channel = channels.get(channelId);
            List<Signal> ready = channel == null ? Collections.emptyList() : channel.after(since);
            boolean canWait = wait > 0
                    && waiting < MAX_WAITING_POLLS
                    && waitingPerUser.getOrDefault(username, 0) < MAX_WAITING_POLLS_PER_USER;
            if (! ready.isEmpty() || ! canWait)
                return Futures.of(batch(ready, since));
            if (channel == null) {
                if (channels.size() >= MAX_CHANNELS)
                    evictOldest();
                channel = new Channel();
                channels.put(channelId, channel);
            }
            channel.waiters.add(arrival);
            waiting++;
            waitingPerUser.merge(username, 1, Integer::sum);
        }
        timer.schedule(() -> arrival.complete(false), wait, TimeUnit.MILLISECONDS);
        return arrival.thenApply(x -> {
            synchronized (this) {
                waiting--;
                waitingPerUser.computeIfPresent(username, (u, c) -> c > 1 ? c - 1 : null);
                Channel channel = channels.get(channelId);
                if (channel == null)
                    return batch(Collections.emptyList(), since);
                channel.waiters.remove(arrival);
                return batch(channel.after(since), since);
            }
        });
    }

    private Batch batch(List<Signal> signals, long since) {
        long next = signals.stream().mapToLong(s -> s.seq).max().orElse(since);
        return new Batch(signals, next);
    }

    private void releaseSticky(Stored s) {
        stickyPerUser.computeIfPresent(s.user, (u, c) -> c > 1 ? c - 1 : null);
    }

    /** Drop the least recently used channel that nobody is waiting on. */
    private void evictOldest() {
        Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Channel c = it.next().getValue();
            if (c.waiters.isEmpty()) {
                c.sticky.values().forEach(this::releaseSticky);
                it.remove();
                return;
            }
        }
        throw new IllegalStateException("Signal relay is full");
    }

    private synchronized void expire() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Channel>> it = channels.entrySet().iterator();
        while (it.hasNext()) {
            Channel c = it.next().getValue();
            c.expire(now, this::releaseSticky);
            if (c.isEmpty())
                it.remove();
        }
        recentPosts.values().removeIf(times -> times.isEmpty() || times.peekLast() < now - 60_000);
    }
}
