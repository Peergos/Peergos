package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** All the chats in /$username/.messaging/
 *
 *  Within this, each chat has a directory named with a uid and the following substructure:
 *  $uuid/shared/peergos-chat-messages.cborstream (append only, eventually consistent log of all messages in chat)
 *  $uuid/shared/peergos-chat-state.cbor (our view of the current state of the chat)
 *  $uuid/shared/media/$year/$month/$media-file (media files shared in chat)
 *  $uuid/private-state.cbor  (keypair for chat identity)
 *
 *  To invite a user we add an invite message to our log, and share the shared directory with them.
 *  To join they copy our state and message log, add a join message to their log,
 *  and share their shared directory with us.
 */
public class Messenger {
    private static final String MESSAGING_BASE_DIR = ".messaging";
    private static final String SHARED_CHAT_STATE = "peergos-chat-state.cbor";
    private static final String SHARED_MSG_LOG = "peergos-chat-messages.cborstream";
    private static final String SHARED_MSG_LOG_INDEX = "peergos-chat-messages.index.bin";
    private static final String PRIVATE_CHAT_STATE = "private-chat-state.cbor";

    private final UserContext context;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Hasher hasher;
    private final LRUCache<MessageRef, MessageEnvelope> cache = new LRUCache<>(1_000);

    @JsConstructor
    public Messenger(UserContext context) {
        this.context = context;
        this.network = context.network;
        this.crypto = context.crypto;
        this.hasher = context.crypto.hasher;
    }

    private PrivateChatState generateChatIdentity() {
        SigningKeyPair chatIdentity = SigningKeyPair.random(crypto.random, crypto.signer);
        PublicKeyHash preHash = ContentAddressedStorage.hashKey(chatIdentity.publicSigningKey);
        SigningPrivateKeyAndPublicHash chatIdWithHash =
                new SigningPrivateKeyAndPublicHash(preHash, chatIdentity.secretSigningKey);
        return new PrivateChatState(chatIdWithHash, chatIdentity.publicSigningKey);
    }

    @JsMethod
    public CompletableFuture<ChatController> createChat() {
        String chatId = "chat:" + context.username + ":" + UUID.randomUUID().toString();
        Chat chat = Chat.createNew(context.username, context.signer.publicKeyHash);

        byte[] rawChat = chat.serialize();
        PrivateChatState privateChatState = generateChatIdentity();
        byte[] rawPrivateChatState = privateChatState.serialize();
        return createChatRoot(chatId)
                .thenCompose(chatRoot -> chatRoot.getOrMkdirs(Paths.get("shared"), context.network, false, crypto)
                        .thenCompose(chatSharedDir -> chatRoot.getUpdated(network)
                                .thenCompose(updatedChatRoot -> chatSharedDir.setProperties(chatSharedDir.getFileProperties(), hasher,
                                        network, Optional.of(updatedChatRoot)).thenCompose(b -> chatSharedDir.getUpdated(network))))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_MSG_LOG,
                                AsyncReader.build(new byte[0]), 0, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_MSG_LOG_INDEX,
                                AsyncReader.build(new byte[16]), 16, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_CHAT_STATE,
                                AsyncReader.build(rawChat), rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(x -> chatRoot.getUpdated(x.version, network))
                        .thenCompose(updatedChatRoot -> updatedChatRoot.uploadOrReplaceFile(PRIVATE_CHAT_STATE,
                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network, crypto, x -> {}, crypto.random.randomBytes(32))))
                .thenCompose(this::getChatMessageStore)
                .thenApply(messageStore -> new ChatController(chatId, chat, messageStore, privateChatState, cache, hasher,
                        c -> overwriteState(c, chatId), this::mirrorMedia, network.dhtClient))
                .thenCompose(controller -> controller.join(context.signer))
                .thenCompose(controller -> controller.addAdmin(context.username));
    }

    private CompletableFuture<FileWrapper> createChatRoot(String chatId) {
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(Paths.get(MESSAGING_BASE_DIR), context.network, true, context.crypto))
                .thenCompose(chatsRoot -> chatsRoot.mkdir(chatId, context.network, false, crypto))
                .thenCompose(updated -> updated.getChild(chatId, hasher, network))
                .thenApply(Optional::get);
    }

    public Path getChatPath(String hostUsername, String chatId) {
        return Paths.get(hostUsername, MESSAGING_BASE_DIR, chatId);
    }

    private Path getChatSharedDir(String chatUid) {
        return Paths.get(context.username, MESSAGING_BASE_DIR, chatUid, "shared");
    }

    @JsMethod
    public CompletableFuture<ChatController> invite(ChatController chat, List<String> usernames, List<PublicKeyHash> identities) {
        Path chatSharedDir = getChatSharedDir(chat.chatUuid);
        return chat.invite(usernames, identities)
                .thenCompose(res -> context.shareReadAccessWith(chatSharedDir, new HashSet<>(usernames))
                        .thenApply(x -> res));
    }

    private CompletableFuture<Boolean> overwriteState(Chat c, String uuid) {
        Path stateFile = Paths.get(context.username, MESSAGING_BASE_DIR, uuid, "shared", SHARED_CHAT_STATE);
        byte[] raw = c.serialize();
        return context.getByPath(stateFile)
                .thenCompose(file -> file.get().overwriteFile(AsyncReader.build(raw), raw.length, network, crypto, x -> {}))
                .thenApply(f -> true);
    }

    /** Copy a chat to our space to join it.
     *
     * @param sourceChatSharedDir
     * @return
     */
    @JsMethod
    public CompletableFuture<ChatController> cloneLocallyAndJoin(FileWrapper sourceChatSharedDir) {
        PrivateChatState privateChatState = generateChatIdentity();
        byte[] rawPrivateChatState = privateChatState.serialize();
        return sourceChatSharedDir.retrieveParent(network)
                .thenApply(Optional::get)
                .thenApply(parent -> parent.getName())
                .thenCompose(chatId -> createChatRoot(chatId) // This will error if a chat with this chatId already exists
                        .thenCompose(chatRoot -> chatRoot.getOrMkdirs(Paths.get("shared"), network, false, crypto)
                                .thenCompose(shared -> getChatState(sourceChatSharedDir)
                                        .thenCompose(mirrorState -> {
                                            Chat ourVersion = mirrorState.copy(new Member(context.username,
                                                    mirrorState.getMember(context.username).id,
                                                    context.signer.publicKeyHash, Optional.empty(),
                                                    mirrorState.host().messagesMergedUpto, 0, false));

                                            byte[] rawChat = ourVersion.serialize();
                                            return shared.uploadOrReplaceFile(SHARED_CHAT_STATE, AsyncReader.build(rawChat),
                                                    rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32));
                                        })
                                        .thenCompose(b -> sourceChatSharedDir.getChild(SHARED_MSG_LOG, hasher, network))
                                        .thenCompose(msgs -> shared.getUpdated(network)
                                                .thenCompose(updatedShared -> msgs.get().copyTo(updatedShared, context)))
                                        .thenCompose(b -> sourceChatSharedDir.getChild(SHARED_MSG_LOG_INDEX, hasher, network))
                                        .thenCompose(msgsIndex -> shared.getUpdated(network)
                                                .thenCompose(updatedShared -> msgsIndex.get().copyTo(updatedShared, context)))
                                        .thenCompose(x -> chatRoot.uploadOrReplaceFile(PRIVATE_CHAT_STATE,
                                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network,
                                                crypto, y -> {}, crypto.random.randomBytes(32)))
                                )).thenCompose(b -> context.shareReadAccessWith(
                                getChatPath(context.username, chatId).resolve("shared"),
                                Collections.singleton(sourceChatSharedDir.getOwnerName())))
                        .thenCompose(b -> getChat(chatId))
                        .thenCompose(controller -> controller.join(context.signer)));
    }

    @JsMethod
    public CompletableFuture<ChatController> mergeMessages(ChatController current, String mirrorUsername) {
        if (mirrorUsername.equals(this.context.username)) {
            return Futures.of(current);
        }
        return Futures.asyncExceptionally(
                () -> getMessageStoreMirror(mirrorUsername, current.chatUuid)
                        .thenCompose(mirrorStore -> current.mergeMessages(mirrorUsername, mirrorStore)),
                t -> {
                    //if (t.getCause() instanceof NoSuchElementException) not GWT compatible
                    if (t.toString().indexOf("java.util.NoSuchElementException") > -1)
                        return Futures.errored(new IllegalStateException("You have been removed from the chat."));
                    return Futures.of(current);
                });
    }

    private CompletableFuture<Boolean> mirrorMedia(FileRef ref, ChatController chat, String currentMirrorUsername) {
        Path mediaDir = getChatMediaDir(chat);
        Path sourcePath = Paths.get(ref.path);
        Path chatRelativePath = sourcePath.subpath(1 + mediaDir.getNameCount(), sourcePath.getNameCount());
        Path ourCopy = mediaDir.resolve(chatRelativePath);
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(ourCopy.getParent(), network, false, crypto))
                .thenCompose(dir -> copyFile(dir, sourcePath, currentMirrorUsername))
                .thenApply(x -> true);
    }

    private CompletableFuture<FileWrapper> copyFile(FileWrapper dir, Path sourcePath, String mirrorUsername) {
        // Try copying file from source first, and then fallback to mirror we are currently merging
        return Futures.asyncExceptionally(() -> context.getByPath(sourcePath)
                        .thenApply(Optional::get),
                t -> context.getByPath(Paths.get(mirrorUsername).resolve(sourcePath.subpath(1, sourcePath.getNameCount())))
                        .thenApply(Optional::get))
                .thenCompose(f -> f.getInputStream(network, crypto, x -> {})
                        .thenCompose(r -> dir.uploadAndReturnFile(f.getName(), r, f.getSize(), false, network, crypto)));
    }

    @JsMethod
    public CompletableFuture<ChatController> mergeAllUpdates(ChatController current, SocialState soc) {
        Set<String> following = soc.getFollowing();
        List<String> toPullFrom = current.getMemberNames().stream()
                .filter(following::contains)
                .collect(Collectors.toList());
        Set<String> pendingMembers = current.getPendingMemberNames().stream()
                .filter(following::contains)
                .collect(Collectors.toSet());
        return Futures.reduceAll(toPullFrom, current,
                (c, n) -> Futures.asyncExceptionally(
                        () -> mergeMessages(c, n),
                        t -> pendingMembers.contains(n) ? Futures.of(c) : Futures.errored(t)),
                (a, b) -> {throw new IllegalStateException();});
    }

    @JsMethod
    public CompletableFuture<ChatController> removeMember(ChatController current, String username) {
        Member member = current.getMember(username);
        if (member == null)
            throw new IllegalStateException("No member in chat with that name!");
        RemoveMember msg = new RemoveMember(current.chatUuid, member.id);
        if (! username.equals(context.username) && ! current.getAdmins().contains(context.username))
            throw new IllegalStateException("Only admins can remove other members!");
        return (username.equals(context.username) ?
                Futures.of(true) :
                current.store.revokeAccess(username))
                .thenCompose(x -> sendMessage(current, msg));
    }

    @JsMethod
    public CompletableFuture<ChatController> sendMessage(ChatController current, Message message) {
        return current.sendMessage(message);
    }

    @JsMethod
    public CompletableFuture<Pair<String, FileRef>> uploadMedia(ChatController current,
                                                                AsyncReader media,
                                                                String fileExtension,
                                                                int length,
                                                                LocalDateTime postTime,
                                                                ProgressConsumer<Long> monitor) {
        String uuid = UUID.randomUUID().toString() + "." + fileExtension;
        return getOrMkdirToStoreMedia(current, postTime)
                .thenCompose(p -> p.right.uploadAndReturnFile(uuid, media, length, false, monitor,
                        network, crypto)
                        .thenCompose(f ->  media.reset().thenCompose(r -> crypto.hasher.hash(r, length))
                                .thenApply(hash -> new Pair<>(f.getFileProperties().getType(),
                                        new FileRef(p.left.resolve(uuid).toString(), f.readOnlyPointer(), hash)))));
    }

    private Path getChatMediaDir(ChatController current) {
        return Paths.get(MESSAGING_BASE_DIR,
                current.chatUuid,
                "shared",
                "media");
    }

    private CompletableFuture<Pair<Path, FileWrapper>> getOrMkdirToStoreMedia(ChatController current,
                                                                              LocalDateTime postTime) {
        Path dirFromHome = getChatMediaDir(current).resolve(Paths.get(
                Integer.toString(postTime.getYear()),
                Integer.toString(postTime.getMonthValue())));
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(dirFromHome, network, true, crypto)
                .thenApply(dir -> new Pair<>(Paths.get("/" + context.username).resolve(dirFromHome), dir)));
    }

    @JsMethod
    public CompletableFuture<ChatController> setGroupProperty(ChatController current, String key, String value) {
        return current.sendMessage(new SetGroupState(key, value));
    }

    public CompletableFuture<ChatController> getChat(String uuid) {
        return context.getByPath(getChatPath(context.username, uuid))
                .thenApply(Optional::get)
                .thenCompose(this::getChatController);
    }

    @JsMethod
    public CompletableFuture<Set<ChatController>> listChats() {
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(Paths.get(MESSAGING_BASE_DIR), network, true, crypto))
                .thenCompose(chatsRoot -> chatsRoot.getChildren(hasher, network))
                .thenCompose(chatDirs -> Futures.combineAll(chatDirs.stream()
                        .map(this::getChatController)
                        .collect(Collectors.toList())));
    }

    private CompletableFuture<ChatController> getChatController(FileWrapper chatRoot) {
        return chatRoot.getChild("shared", hasher, network)
                .thenCompose(sharedDir -> getChatState(sharedDir.get()))
                .thenCompose(chat -> getPrivateChatState(chatRoot)
                        .thenCompose(priv -> getChatMessageStore(chatRoot)
                                .thenApply(msgStore -> new ChatController(chatRoot.getName(), chat, msgStore, priv,
                                        cache, hasher, c -> overwriteState(c, chatRoot.getName()),
                                        this::mirrorMedia, network.dhtClient))));
    }

    private CompletableFuture<Chat> getChatState(FileWrapper chatRoot) {
        return chatRoot.getChild(SHARED_CHAT_STATE, hasher, network)
                .thenCompose(chatStateOpt -> Serialize.parse(chatStateOpt.get(), Chat::fromCbor, network, crypto));
    }

    private CompletableFuture<PrivateChatState> getPrivateChatState(FileWrapper chatRoot) {
        return chatRoot.getChild(PRIVATE_CHAT_STATE, hasher, network)
                .thenCompose(priv -> Serialize.parse(priv.get(), PrivateChatState::fromCbor, network, crypto));
    }

    private CompletableFuture<Pair<FileWrapper, FileWrapper>> getSharedLogAndIndex(FileWrapper chatRoot) {
        return chatRoot.getDescendentByPath("shared/" + SHARED_MSG_LOG, hasher, network)
                .thenCompose(msgFile -> chatRoot.getDescendentByPath("shared/" + SHARED_MSG_LOG_INDEX, hasher, network)
                        .thenApply(index -> new Pair<>(msgFile.get(), index.get())));
    }

    private CompletableFuture<MessageStore> getChatMessageStore(FileWrapper chatRoot) {
        return getSharedLogAndIndex(chatRoot)
                .thenApply(files -> new FileBackedMessageStore(files.left, files.right, context,
                        getChatPath(context.username, chatRoot.getName()).resolve("shared"),
                        () -> context.getByPath(getChatPath(context.username, chatRoot.getName()))
                                .thenApply(Optional::get)
                                .thenCompose(this::getSharedLogAndIndex)));
    }

    private CompletableFuture<MessageStore> getMessageStoreMirror(String username, String uuid) {
        return context.getByPath(getChatPath(username, uuid))
                .thenApply(Optional::get)
                .thenCompose(this::getChatMessageStore);
    }
}
