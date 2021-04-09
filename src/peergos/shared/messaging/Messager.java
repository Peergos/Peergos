package peergos.shared.messaging;

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
    private static final String MESSAGE_BASE_DIR = ".messaging";
    private static final String SHARED_CHAT_STATE_FILE = "peergos-chat-state.cbor";
    private static final String SHARED_CHAT_FILE = "peergos-chat-messages.cborstream";
    private static final String PRIVATE_CHAT_STATE_FILE = "private-chat-state.cbor";

    private final UserContext context;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final Hasher hasher;

    public Messager(UserContext context) {
        this.context = context;
        this.network = context.network;
        this.crypto = context.crypto;
        this.hasher = context.crypto.hasher;
    }

    public CompletableFuture<ChatController> createChat() {
        String uuid = UUID.randomUUID().toString();
        Chat chat = Chat.createNew(context.username, context.signer.publicKeyHash);

        SigningKeyPair chatIdentity = SigningKeyPair.random(crypto.random, crypto.signer);
        byte[] rawChat = chat.serialize();
        PublicKeyHash preHash = ContentAddressedStorage.hashKey(chatIdentity.publicSigningKey);
        SigningPrivateKeyAndPublicHash chatIdWithHash =
                new SigningPrivateKeyAndPublicHash(preHash, chatIdentity.secretSigningKey);
        PrivateChatState privateChatState = new PrivateChatState(chatIdWithHash, chatIdentity.publicSigningKey);
        byte[] rawPrivateChatState = privateChatState.serialize();
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(Paths.get(MESSAGE_BASE_DIR), context.network, true, context.crypto))
                .thenCompose(chatsRoot -> chatsRoot.mkdir(uuid, context.network, false, crypto))
                .thenCompose(chatRoot -> chatRoot.getOrMkdirs(Paths.get("shared"), context.network, false, crypto)
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_CHAT_FILE,
                                AsyncReader.build(new byte[0]), 0, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(SHARED_CHAT_STATE_FILE,
                                AsyncReader.build(rawChat), rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32)))
                        .thenCompose(x -> chatRoot.getUpdated(x.version, network))
                        .thenCompose(updatedChatRoot -> updatedChatRoot.uploadOrReplaceFile(PRIVATE_CHAT_STATE_FILE,
                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network, crypto, x -> {}, crypto.random.randomBytes(32))))
                .thenCompose(chatRoot -> chatRoot.getDescendentByPath("shared/" + SHARED_CHAT_FILE, hasher, network))
                .thenApply(messageFile -> new ChatController(uuid, chat,
                        new FileBackedMessageStore(messageFile.get(), network, crypto), privateChatState))
                .thenCompose(controller -> controller.join(context.signer));
    }

    public CompletableFuture<Set<ChatController>> listChats() {
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(Paths.get(MESSAGE_BASE_DIR), network, true, crypto))
                .thenCompose(chatsRoot -> chatsRoot.getChildren(hasher, network))
                .thenCompose(chatDirs -> Futures.combineAll(chatDirs.stream()
                        .map(dir -> getChatState(dir)
                                .thenCompose(chat -> getPrivateChatState(dir)
                                        .thenCompose(priv -> getChatMessageStore(dir)
                                                .thenApply(msgStore -> new ChatController(dir.getName(), chat, msgStore, priv)))))
                        .collect(Collectors.toList())));
    }

    private CompletableFuture<Chat> getChatState(FileWrapper chatRoot) {
        return chatRoot.getDescendentByPath("shared/" + SHARED_CHAT_STATE_FILE, hasher, network)
                .thenCompose(chatStateOpt -> Serialize.parse(chatStateOpt.get(), Chat::fromCbor, network, crypto));
    }

    private CompletableFuture<PrivateChatState> getPrivateChatState(FileWrapper chatRoot) {
        return chatRoot.getChild(PRIVATE_CHAT_STATE_FILE, hasher, network)
                .thenCompose(priv -> Serialize.parse(priv.get(), PrivateChatState::fromCbor, network, crypto));
    }

    private CompletableFuture<MessageStore> getChatMessageStore(FileWrapper chatRoot) {
        return chatRoot.getDescendentByPath("shared/" + SHARED_CHAT_FILE, hasher, network)
                .thenApply(msgFile -> new FileBackedMessageStore(msgFile.get(), network, crypto));
    }
}
