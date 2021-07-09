package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class ChatController {

    public final String chatUuid;
    public final MessageStore store;
    public final PrivateChatState privateChatState;
    private final Chat state;
    private final FileWrapper root; // includes the version that the state above was derived from
    private final LRUCache<MessageRef, MessageEnvelope> cache;
    private final Hasher hasher;
    private final UserContext context;

    public ChatController(String chatUuid,
                          Chat state,
                          MessageStore store,
                          PrivateChatState privateChatState,
                          FileWrapper root,
                          LRUCache<MessageRef, MessageEnvelope> cache,
                          Hasher hasher,
                          UserContext context) {
        this.chatUuid = chatUuid;
        this.state = state;
        this.store = store;
        this.privateChatState = privateChatState;
        this.root = root;
        this.cache = cache;
        this.hasher = hasher;
        this.context = context;
    }

    public Member host() {
        return state.host();
    }

    public Member getMember(String username) {
        return state.getMember(username);
    }

    @JsMethod
    public String getUsername(Id author) {
        return state.getMember(author).username;
    }

    @JsMethod
    public Set<String> getMemberNames() {
        return state.members.values().stream()
                .filter(m -> !m.removed)
                .filter(m -> ! privateChatState.deletedMembers.contains(m.username))
                .map(m -> m.username)
                .collect(Collectors.toSet());
    }

    @JsMethod
    public Set<String> getPendingMemberNames() {
        return state.members.values().stream()
                .filter(m -> !m.removed)
                .filter(m -> m.chatIdentity.isEmpty())
                .map(m -> m.username)
                .collect(Collectors.toSet());
    }

    public ChatController with(PrivateChatState priv) {
        return new ChatController(chatUuid, state, store, priv, root, cache, hasher, context);
    }

    @JsMethod
    public Set<String> deletedMemberNames() {
        return privateChatState.deletedMembers;
    }

    @JsMethod
    public List<MessageEnvelope> getRecent() {
        return state.getRecent();
    }

    @JsMethod
    public CompletableFuture<MessageEnvelope> getMessageFromRef(MessageRef ref, int sourceIndex) {
        MessageEnvelope cached = cache.get(ref);
        if (cached != null)
            return Futures.of(cached);
        // Try 100 message prior to reference source first, then try previous chunks
        return store.getMessages(Math.max(0, sourceIndex - 100), sourceIndex)
                .thenCompose(allSigned -> Futures.findFirst(allSigned, s -> hashMessage(s.msg)
                        .thenApply(h -> h.equals(ref) ? Optional.of(s.msg) : Optional.empty())))
                .thenCompose(resOpt -> resOpt.map(Futures::of)
                        .orElseGet(() -> getMessageFromRef(ref, sourceIndex - 100)));
    }

    @JsMethod
    public CompletableFuture<MessageRef> generateHash(MessageEnvelope m) {
        byte[] raw = m.serialize();
        return hasher.bareHash(raw)
                .thenApply(MessageRef::new);
    }

    private CompletableFuture<MessageRef> hashMessage(MessageEnvelope m) {
        return generateHash(m)
                .thenApply(r -> {
                    cache.put(r, m);
                    return r;
                });
    }

    @JsMethod
    public CompletableFuture<List<MessageEnvelope>> getMessages(int from, int to) {
        return store.getMessages(from, to)
                .thenApply(signed -> signed.stream().map(s -> s.msg).collect(Collectors.toList()));
    }

    @JsMethod
    public CompletableFuture<ChatController> sendMessage(Message message) {
        return state.sendMessage(message, privateChatState.chatIdentity, store, context.network.dhtClient, hasher)
                .thenCompose(u -> commitUpdate(u, context.username));
    }

    @JsMethod
    public String getGroupProperty(String key) {
        return state.groupState.get(key).value;
    }

    @JsMethod
    public String getTitle() {
        return state.getTitle();
    }

    @JsMethod
    public Set<String> getAdmins() {
        return state.getAdmins();
    }

    @JsMethod
    public boolean isAdmin() {
        return state.getAdmins().contains(state.host().username);
    }

    private ChatController withState(Chat c) {
        return new ChatController(chatUuid, c, store, privateChatState, root, cache, hasher, context);
    }

    private ChatController withRoot(FileWrapper root) {
        return new ChatController(chatUuid, state, store, privateChatState, root, cache, hasher, context);
    }

    public CompletableFuture<ChatController> join(SigningPrivateKeyAndPublicHash identity) {
        OwnerProof chatId = OwnerProof.build(identity, privateChatState.chatIdentity.publicKeyHash);
        return state.join(state.host(), chatId, privateChatState.chatIdPublic, identity, store, context.network.dhtClient, hasher)
                .thenCompose(u -> commitUpdate(u, context.username));
    }

    @JsMethod
    public CompletableFuture<ChatController> addAdmin(String username) {
        Set<String> admins = new TreeSet<>(state.getAdmins());
        if (! admins.isEmpty() && ! admins.contains(state.host().username))
            throw new IllegalStateException("Only admins can modify the admin list!");
        admins.add(username);
        SetGroupState msg = new SetGroupState(GroupProperty.ADMINS_STATE_KEY, admins.stream().collect(Collectors.joining(",")));
        return sendMessage(msg);
    }

    @JsMethod
    public CompletableFuture<ChatController> removeAdmin(String username) {
        Set<String> admins = new TreeSet<>(state.getAdmins());
        if (! admins.contains(state.host().username))
            throw new IllegalStateException("Only admins can modify the admin list!");

        admins.remove(username);
        if (admins.isEmpty())
            throw new IllegalStateException("A chat must always have at least 1 admin");
        SetGroupState msg = new SetGroupState(GroupProperty.ADMINS_STATE_KEY, admins.stream().collect(Collectors.joining(",")));
        return sendMessage(msg);
    }

    public CompletableFuture<ChatController> invite(List<String> usernames,
                                                    List<PublicKeyHash> identities) {
        return state.inviteMembers(usernames, identities, privateChatState.chatIdentity, store, context.network.dhtClient, hasher)
                .thenCompose(u -> commitUpdate(u, context.username));
    }

    public CompletableFuture<ChatController> mergeMessages(String username,
                                                           MessageStore mirrorStore) {
        Member mirrorHost = state.getMember(username);
        return state.merge(chatUuid, mirrorHost.id, mirrorStore, context.network.dhtClient)
                .thenCompose(u -> commitUpdate(u, username));
    }

    private CompletableFuture<Snapshot> copyFile(FileWrapper dir, Path sourcePath, String mirrorUsername, Snapshot v, Committer c) {
        // Try copying file from source first, and then fallback to mirror we are currently merging
        return Futures.asyncExceptionally(() -> context.getByPath(sourcePath.toString(), v)
                        .thenApply(Optional::get),
                t -> context.getByPath(Paths.get(mirrorUsername).resolve(sourcePath.subpath(1, sourcePath.getNameCount())).toString(), v)
                        .thenApply(Optional::get))
                .thenCompose(f -> f.getInputStream(f.version.get(f.writer()).props, context.network, context.crypto, x -> {})
                        .thenCompose(r -> dir.uploadFileSection(v, c, f.getName(), r, false, 0, f.getSize(),
                                Optional.empty(), false, false, context.network, context.crypto, x -> {},
                                context.crypto.random.randomBytes(RelativeCapability.MAP_KEY_LENGTH))));
    }

    private Path getChatMediaDir(ChatController current) {
        return Paths.get(Messenger.MESSAGING_BASE_DIR,
                current.chatUuid,
                "shared",
                "media");
    }

    private CompletableFuture<Snapshot> mirrorMedia(FileRef ref, ChatController chat, String currentMirrorUsername, Snapshot in, Committer committer) {
        Path mediaDir = getChatMediaDir(chat);
        Path sourcePath = Paths.get(ref.path);
        Path chatRelativePath = sourcePath.subpath(1 + mediaDir.getNameCount(), sourcePath.getNameCount());
        Path ourCopy = mediaDir.resolve(chatRelativePath);
        Path parent = ourCopy.getParent();
        List<String> mediaFileParentPath = IntStream.range(0, parent.getNameCount())
                .mapToObj(i -> parent.getName(i).toString())
                .collect(Collectors.toList());
        return context.getByPath(context.username, in)
                .thenApply(Optional::get)
                .thenCompose(home -> home.getOrMkdirs(mediaFileParentPath, false, context.network, context.crypto, in, committer))
                .thenCompose(dir -> copyFile(dir.right, sourcePath, currentMirrorUsername, dir.left, committer));
    }

    private CompletableFuture<Snapshot> overwriteState(FileWrapper root, Chat c, Snapshot v, Committer com) {
        byte[] raw = c.serialize();
        return root.getUpdated(v, context.network)
                .thenCompose(d -> d.getDescendentByPath("shared/"+ Messenger.SHARED_CHAT_STATE, context.crypto.hasher, context.network))
                .thenCompose(file -> file.get().overwriteFile(AsyncReader.build(raw), raw.length, context.network, context.crypto, x -> {}, v, com));
    }

    private CompletableFuture<ChatController> commitUpdate(ChatUpdate u, String mirrorUsername) {
        // 1. rotate access control
        // 2. copy media
        // 3. append messages
        // 4. commit state file
        return (u.toRevokeAccess.isEmpty() ? Futures.of(store) : store.revokeAccess(u.toRevokeAccess))
                .thenCompose(x -> context.network.synchronizer.applyComplexUpdate(context.signer.publicKeyHash, root.signingPair(),
                (s, c) -> Futures.reduceAll(u.mediaToCopy, s, (v, f) -> mirrorMedia(f, this, mirrorUsername, v, c),
                                (a, b) -> a.merge(b))
                        .thenCompose(s2 -> root.getUpdated(s2, context.network)
                                .thenCompose(base -> Futures.reduceAll(u.newMessages, s2,
                                        (v, m) -> store.addMessage(v, c, state.host().messagesMergedUpto + u.newMessages.indexOf(m), m),
                                        (a, b) -> a.merge(b))))
                        .thenCompose(s4 -> overwriteState(root, u.state, s4, c)))
                        .thenApply(s -> withState(u.state).withRoot(root)));
    }
}
