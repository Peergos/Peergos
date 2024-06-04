package peergos.shared.messaging;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.display.*;
import peergos.shared.messaging.messages.*;
import peergos.shared.storage.auth.*;
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
    public static final String MESSAGING_BASE_DIR = ".messaging";

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

    @JsMethod
    public CompletableFuture<ChatController> createChat() {
        return initChat(null);
    }

    @JsMethod
    public CompletableFuture<ChatController> createAppChat(String appName){
        return initChat(appName);
    }

    private CompletableFuture<ChatController> initChat(String appName) {
        String prefix = appName != null ? "chat-" + appName + "$" : "chat$";
        String chatId = prefix + context.username + "$" + UUID.randomUUID().toString();
        Chat chat = Chat.createNew(chatId, context.username, context.signer.publicKeyHash);
        byte[] rawChat = chat.serialize();
        PrivateChatState privateChatState = Chat.generateChatIdentity(crypto);
        byte[] rawPrivateChatState = privateChatState.serialize();
        return createChatRoot(chatId)
                .thenCompose(chatRoot -> chatRoot.getOrMkdirs(PathUtil.get("shared"), context.network, false, context.mirrorBatId(), crypto)
                        .thenCompose(chatSharedDir -> chatRoot.getUpdated(network)
                                .thenCompose(updatedChatRoot -> chatSharedDir.setProperties(chatSharedDir.getFileProperties(), hasher,
                                        network, Optional.of(updatedChatRoot)).thenCompose(b -> chatSharedDir.getUpdated(network))))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(ChatController.SHARED_MSG_LOG,
                                AsyncReader.build(new byte[0]), 0, network, crypto, x -> {}, crypto.random.randomBytes(32),
                                Optional.of(Bat.random(crypto.random)), chatSharedDir.mirrorBatId()))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(ChatController.SHARED_MSG_LOG_INDEX,
                                AsyncReader.build(new byte[16]), 16, network, crypto, x -> {}, crypto.random.randomBytes(32),
                                Optional.of(Bat.random(crypto.random)), chatSharedDir.mirrorBatId()))
                        .thenCompose(chatSharedDir -> chatSharedDir.uploadOrReplaceFile(ChatController.SHARED_CHAT_STATE,
                                AsyncReader.build(rawChat), rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32),
                                Optional.of(Bat.random(crypto.random)), chatSharedDir.mirrorBatId()))
                        .thenCompose(x -> chatRoot.getUpdated(x.version, network))
                        .thenCompose(updatedChatRoot -> updatedChatRoot.uploadOrReplaceFile(ChatController.PRIVATE_CHAT_STATE,
                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network, crypto, x -> {},
                                crypto.random.randomBytes(32), Optional.of(Bat.random(crypto.random)), updatedChatRoot.mirrorBatId()))
                        .thenCompose(newRoot -> ChatController.getChatMessageStore(newRoot, context)
                                .thenApply(messageStore -> new ChatController(chatId, chat, messageStore,
                                        privateChatState, newRoot, cache, context))))
                .thenCompose(controller -> controller.join(context.signer))
                .thenCompose(controller -> controller.addAdmin(context.username));
    }

    private CompletableFuture<FileWrapper> createChatRoot(String chatId) {
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(PathUtil.get(MESSAGING_BASE_DIR), context.network, true, context.mirrorBatId(), context.crypto))
                .thenCompose(chatsRoot -> chatsRoot.mkdir(chatId, context.network, false, chatsRoot.mirrorBatId(), crypto))
                .thenCompose(updated -> updated.getChild(chatId, hasher, network))
                .thenApply(Optional::get);
    }
    public static Path getChatPath(String hostUsername, String chatId) {
        return PathUtil.get(hostUsername, MESSAGING_BASE_DIR, chatId);
    }

    private Path getChatSharedDir(String chatUid) {
        return PathUtil.get(context.username, MESSAGING_BASE_DIR, chatUid, "shared");
    }

    @JsMethod
    public CompletableFuture<ChatController> invite(ChatController chat, List<String> usernames, List<PublicKeyHash> identities) {
        Path chatSharedDir = getChatSharedDir(chat.chatUuid);
        return chat.invite(usernames, identities)
                .thenCompose(res -> context.shareReadAccessWith(chatSharedDir, new HashSet<>(usernames))
                        .thenApply(x -> res));
    }

    /** Copy a chat to our space to join it.
     *
     * @param sourceChatSharedDir
     * @return
     */
    @JsMethod
    public CompletableFuture<ChatController> cloneLocallyAndJoin(FileWrapper sourceChatSharedDir) {
        PrivateChatState privateChatState = Chat.generateChatIdentity(crypto);
        byte[] rawPrivateChatState = privateChatState.serialize();
        return sourceChatSharedDir.retrieveParent(network)
                .thenApply(Optional::get)
                .thenApply(parent -> parent.getName())
                .thenCompose(chatId -> createChatRoot(chatId) // This will error if a chat with this chatId already exists
                        .thenCompose(chatRoot -> chatRoot.getOrMkdirs(PathUtil.get("shared"), network, false, context.mirrorBatId(), crypto)
                                .thenCompose(shared -> ChatController.getChatState(sourceChatSharedDir, network, crypto)
                                        .thenCompose(mirrorState -> {
                                            Chat ourVersion = mirrorState.copy(new Member(context.username,
                                                    mirrorState.getMember(context.username).id,
                                                    context.signer.publicKeyHash, Optional.empty(),
                                                    mirrorState.host().messagesMergedUpto, 0, false));

                                            byte[] rawChat = ourVersion.serialize();
                                            return shared.uploadOrReplaceFile(ChatController.SHARED_CHAT_STATE, AsyncReader.build(rawChat),
                                                    rawChat.length, network, crypto, x -> {}, crypto.random.randomBytes(32),
                                                    Optional.of(Bat.random(crypto.random)), shared.mirrorBatId());
                                        })
                                        .thenCompose(b -> sourceChatSharedDir.getChild(ChatController.SHARED_MSG_LOG, hasher, network))
                                        .thenCompose(msgs -> shared.getUpdated(network)
                                                .thenCompose(updatedShared -> msgs.get().copyTo(updatedShared, context)))
                                        .thenCompose(b -> sourceChatSharedDir.getChild(ChatController.SHARED_MSG_LOG_INDEX, hasher, network))
                                        .thenCompose(msgsIndex -> shared.getUpdated(network)
                                                .thenCompose(updatedShared -> msgsIndex.get().copyTo(updatedShared, context)))
                                        .thenCompose(x -> chatRoot.uploadOrReplaceFile(ChatController.PRIVATE_CHAT_STATE,
                                                AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network,
                                                crypto, y -> {}, crypto.random.randomBytes(32),
                                                Optional.of(Bat.random(crypto.random)), chatRoot.mirrorBatId()))
                                )).thenCompose(b -> context.shareReadAccessWith(
                                getChatPath(context.username, chatId).resolve("shared"),
                                Collections.singleton(sourceChatSharedDir.getOwnerName())))
                        .thenCompose(b -> getChat(chatId))
                        .thenCompose(controller -> controller.join(context.signer)));
    }

    private CompletableFuture<ChatController> updatePrivateState(PrivateChatState state, ChatController current) {
        Path chatPath = getChatPath(context.username, current.chatUuid);
        byte[] rawPrivateChatState = state.serialize();
        return context.getByPath(chatPath)
                .thenCompose(dopt -> dopt.get().uploadOrReplaceFile(ChatController.PRIVATE_CHAT_STATE,
                        AsyncReader.build(rawPrivateChatState), rawPrivateChatState.length, network,
                        crypto, y -> {}, crypto.random.randomBytes(32),
                        Optional.of(Bat.random(crypto.random)), dopt.get().mirrorBatId()))
                .thenApply(f -> current.with(state));
    }

    public boolean allOtherMembersRemoved(ChatController current) {
        Set<String> memberNames = current.getMemberNames();
        return memberNames.contains(context.username) && memberNames.size() == 1;
    }

    @JsMethod
    public CompletableFuture<ChatController> mergeMessages(ChatController current, String mirrorUsername) {
        if (mirrorUsername.equals(this.context.username) ||
                (current.deletedMemberNames().contains(mirrorUsername) && ! allOtherMembersRemoved(current))) {
            return Futures.of(current);
        }
        return Futures.asyncExceptionally(
                () -> getMessageStoreMirror(mirrorUsername, current.chatUuid)
                        .thenCompose(mirrorStore -> current.mergeMessages(mirrorUsername, mirrorStore)),
                t -> {
                    //if (t.getCause() instanceof NoSuchElementException) not GWT compatible
                    if (! current.getPendingMemberNames().contains(mirrorUsername) && t.toString().indexOf("java.util.NoSuchElementException") > -1) {
                        // member server is online, but chat mirror is not accessible
                        // This either means we have been removed, or they deleted their mirror
                        // We add them to the deleted users list to stop polling them, or remove them is we are an admin
                        if (current.isAdmin()) {
                            return removeMember(current, mirrorUsername);
                        } else {
                            if (current.deletedMemberNames().contains(mirrorUsername))
                                return Futures.of(current);
                            PrivateChatState updatedPrivate = current.privateChatState.addDeleted(mirrorUsername);
                            return updatePrivateState(updatedPrivate, current);
                        }
                    }
                    return Futures.of(current);
                });
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
        return sendMessage(current, msg);
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
                        p.right.mirrorBatId(), network, crypto)
                        .thenCompose(f ->  media.reset().thenCompose(r -> crypto.hasher.hashFromStream(r, length))
                                .thenApply(hash -> new Pair<>(f.getFileProperties().getType(),
                                        new FileRef(p.left.resolve(uuid).toString(), f.readOnlyPointer(), hash)))));
    }

    private Path getChatMediaDir(ChatController current) {
        return PathUtil.get(MESSAGING_BASE_DIR,
                current.chatUuid,
                "shared",
                "media");
    }

    private CompletableFuture<Pair<Path, FileWrapper>> getOrMkdirToStoreMedia(ChatController current,
                                                                              LocalDateTime postTime) {
        Path dirFromHome = getChatMediaDir(current).resolve(PathUtil.get(
                Integer.toString(postTime.getYear()),
                Integer.toString(postTime.getMonthValue())));
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(dirFromHome, network, true, context.mirrorBatId(), crypto)
                .thenApply(dir -> new Pair<>(PathUtil.get("/" + context.username).resolve(dirFromHome), dir)));
    }

    @JsMethod
    public CompletableFuture<ChatController> setGroupProperty(ChatController current, String key, String value) {
        return current.sendMessage(new SetGroupState(key, value));
    }

    @JsMethod
    public CompletableFuture<ChatController> getChat(String uuid) {
        return context.getByPath(getChatPath(context.username, uuid))
                .thenApply(Optional::get)
                .thenCompose(d -> ChatController.getChatController(d, context, cache));
    }

    @JsMethod
    public CompletableFuture<Boolean> deleteChat(ChatController chat) {
        Path chatPath = getChatPath(context.username, chat.chatUuid);
        Path parentPath = chatPath.getParent();
        return context.getByPath(parentPath)
                .thenCompose(popt -> popt.get().getChild(chatPath.getFileName().toString(), crypto.hasher, network)
                        .thenCompose(copt -> copt.get().remove(popt.get(), chatPath, context)))
                .thenApply(x -> true);
    }

    @JsMethod
    public CompletableFuture<Set<ChatController>> listChats() {
        return context.getUserRoot()
                .thenCompose(home -> home.getOrMkdirs(PathUtil.get(MESSAGING_BASE_DIR), network, true, context.mirrorBatId(), crypto))
                .thenCompose(chatsRoot -> chatsRoot.getChildren(hasher, network))
                .thenCompose(chatDirs -> Futures.combineAll(chatDirs.stream()
                        .map(d -> ChatController.getChatController(d, context, cache)
                                .thenApply(Optional::of)
                                .exceptionally(t -> Optional.empty()))
                        .collect(Collectors.toList()))
                        .thenApply(res -> res.stream()
                                .flatMap(Optional::stream)
                                .collect(Collectors.toSet())));
    }

    private CompletableFuture<MessageStore> getMessageStoreMirror(String username, String uuid) {
        return context.getByPath(getChatPath(username, uuid))
                .thenApply(Optional::get)
                .thenCompose(d -> ChatController.getChatMessageStore(d, context));
    }
}
