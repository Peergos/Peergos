package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.*;

public class ChatController {

    public final String chatUuid;
    public final MessageStore store;
    private final Chat state;
    private final PrivateChatState privateChatState;
    private final LRUCache<MessageRef, MessageEnvelope> cache;
    private final Hasher hasher;
    private final Function<Chat, CompletableFuture<Boolean>> committer;
    private final TriFunction<FileRef, ChatController, String, CompletableFuture<Boolean>> mediaCopier;
    private final ContentAddressedStorage ipfs;

    public ChatController(String chatUuid,
                          Chat state,
                          MessageStore store,
                          PrivateChatState privateChatState,
                          LRUCache<MessageRef, MessageEnvelope> cache,
                          Hasher hasher,
                          Function<Chat, CompletableFuture<Boolean>> committer,
                          TriFunction<FileRef, ChatController, String, CompletableFuture<Boolean>> mediaCopier,
                          ContentAddressedStorage ipfs) {
        this.chatUuid = chatUuid;
        this.state = state;
        this.store = store;
        this.privateChatState = privateChatState;
        this.cache = cache;
        this.hasher = hasher;
        this.committer = committer;
        this.mediaCopier = mediaCopier;
        this.ipfs = ipfs;
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
                .filter(m -> m.chatIdentity.isPresent())
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
        return state.addMessage(message, privateChatState.chatIdentity, store, hasher)
                .thenCompose(msg -> this.state.apply(msg, chatUuid, r -> mediaCopier.apply(r, this, state.host().username), store, ipfs))
                .thenCompose(x -> committer.apply(this.state))
                .thenApply(x -> this);
    }

    @JsMethod
    public String getGroupProperty(String key) {
        return state.groupState.get(key).value;
    }

    @JsMethod
    public Set<String> getAdmins() {
        return state.getAdmins();
    }

    public CompletableFuture<ChatController> join(SigningPrivateKeyAndPublicHash identity) {
        OwnerProof chatId = OwnerProof.build(identity, privateChatState.chatIdentity.publicKeyHash);
        return state.join(state.host(), chatId, privateChatState.chatIdPublic, identity, store, committer, hasher)
                .thenApply(x -> this);
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
        return state.inviteMembers(usernames, identities, privateChatState.chatIdentity, store, committer, hasher)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> invite(String username,
                                                    PublicKeyHash identity) {
        return state.inviteMember(username, identity, privateChatState.chatIdentity, store, committer, hasher)
                .thenApply(x -> this);
    }

    public CompletableFuture<ChatController> mergeMessages(String username,
                                                           MessageStore mirrorStore) {
        Member mirrorHost = state.getMember(username);
        return state.merge(chatUuid, mirrorHost.id, mirrorStore, store, ipfs, committer,
                r -> mediaCopier.apply(r, this, username))
                .thenApply(x -> this);
    }
}
