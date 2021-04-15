package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

/** All the chats in /$username/.messaging/
 *
 *  Within this, each chat has a directory named with a uid and the following substructure:
 *  $uuid/shared/peergos-chat-messages.cborstream (append only, eventually consistent log of all messages in chat)
 *  $uuid/shared/peergos-chat-state.cbor (our view of the current state of the chat)
 *  $uuid/private-state.cbor  (keypair for chat identity)
 *
 *  To invite a user we add an invite message to our log, and share the shared directory with them.
 *  To join they copy our state and message log, add a join message to their log,
 *  and share their shared directory with us.
 */
public class Messager {
    private static final String MESSAGING_BASE_DIR = ".messaging";
    private static final String SHARED_CHAT_STATE = "peergos-chat-state.cbor";
    private static final String SHARED_MSG_LOG = "peergos-chat-messages.cborstream";
    private static final String PRIVATE_CHAT_STATE = "private-chat-state.cbor";

    private final UserContext context;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Hasher hasher;

    @JsConstructor
    public Messager(UserContext context) {
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
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_CHAT_STATE,
                                AsyncReader.build(rawChat), rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(x -> chatRoot.getUpdated(x.version, network))
                        .thenCompose(updatedChatRoot -> updatedChatRoot.uploadOrReplaceFile(PRIVATE_CHAT_STATE,
                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network, crypto, x -> {}, crypto.random.randomBytes(32))))
                .thenCompose(chatRoot -> chatRoot.getDescendentByPath("shared/" + SHARED_MSG_LOG, hasher, network))
                .thenApply(messageFile -> new ChatController(chatId, chat,
                        new FileBackedMessageStore(messageFile.get(), network, crypto), privateChatState))
                .thenCompose(controller -> controller.join(context.signer, c -> overwriteState(c, chatId)));
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

    @JsMethod
    public CompletableFuture<Boolean> invite(ChatController chat, String username, PublicKeyHash identity) {
        Path chatSharedDir = Paths.get(context.username, MESSAGING_BASE_DIR, chat.chatUuid, "shared");
        return chat.invite(username, identity, c -> overwriteState(c, chat.chatUuid))
                .thenCompose(x -> context.shareReadAccessWith(chatSharedDir, Collections.singleton(username)));
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
                                                    mirrorState.host.messagesMergedUpto, 0));

                                            byte[] rawChat = ourVersion.serialize();
                                            return shared.uploadOrReplaceFile(SHARED_CHAT_STATE, AsyncReader.build(rawChat),
                                                    rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32));
                                        })
                                        .thenCompose(b -> sourceChatSharedDir.getChild(SHARED_MSG_LOG, hasher, network))
                                        .thenCompose(msgs -> shared.getUpdated(network)
                                                .thenCompose(updatedShared -> msgs.get().copyTo(updatedShared, context)))
                                        .thenCompose(x -> chatRoot.uploadOrReplaceFile(PRIVATE_CHAT_STATE,
                                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network,
                                                crypto, y -> {}, crypto.random.randomBytes(32)))
                                )).thenCompose(b -> context.shareReadAccessWith(
                                getChatPath(context.username, chatId).resolve("shared"),
                                Collections.singleton(sourceChatSharedDir.getOwnerName())))
                        .thenCompose(b -> getChat(chatId))
                        .thenCompose(controller -> controller.join(context.signer, c -> overwriteState(c, chatId))));
    }

    @JsMethod
    public CompletableFuture<ChatController> mergeMessages(ChatController current, String mirrorUsername) {
        return getMessageStoreMirror(mirrorUsername, current.chatUuid)
                .thenCompose(mirrorStore -> current.mergeMessages(mirrorUsername, mirrorStore, network.dhtClient,
                        c -> overwriteState(c, current.chatUuid)));
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
                                .thenApply(msgStore -> new ChatController(chatRoot.getName(), chat, msgStore, priv))));
    }

    private CompletableFuture<Chat> getChatState(FileWrapper chatRoot) {
        return chatRoot.getChild(SHARED_CHAT_STATE, hasher, network)
                .thenCompose(chatStateOpt -> Serialize.parse(chatStateOpt.get(), Chat::fromCbor, network, crypto));
    }

    private CompletableFuture<PrivateChatState> getPrivateChatState(FileWrapper chatRoot) {
        return chatRoot.getChild(PRIVATE_CHAT_STATE, hasher, network)
                .thenCompose(priv -> Serialize.parse(priv.get(), PrivateChatState::fromCbor, network, crypto));
    }

    private CompletableFuture<MessageStore> getChatMessageStore(FileWrapper chatRoot) {
        return chatRoot.getDescendentByPath("shared/" + SHARED_MSG_LOG, hasher, network)
                .thenApply(msgFile -> new FileBackedMessageStore(msgFile.get(), network, crypto));
    }

    private CompletableFuture<MessageStore> getMessageStoreMirror(String username, String uuid) {
        return context.getByPath(getChatPath(username, uuid).resolve(Paths.get("shared", SHARED_MSG_LOG)))
                .thenApply(msgFile -> new FileBackedMessageStore(msgFile.get(), network, crypto));
    }
}
