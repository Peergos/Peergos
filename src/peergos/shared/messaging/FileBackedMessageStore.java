package peergos.shared.messaging;

import peergos.shared.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public class FileBackedMessageStore implements MessageStore {

    private FileWrapper messages;
    private final NetworkAccess network;
    private final Crypto crypto;

    public FileBackedMessageStore(FileWrapper messages, NetworkAccess network, Crypto crypto) {
        this.messages = messages;
        this.network = network;
        this.crypto = crypto;
    }

    @Override
    public CompletableFuture<List<SignedMessage>> getMessagesFrom(long index) {
        List<SignedMessage> res = new ArrayList<>();
        return messages.getUpdated(network)
                .thenCompose(updated -> updated.getInputStream(network, crypto, x -> {})
                        .thenCompose(reader -> reader.parseLimitedStream(SignedMessage::fromCbor,
                                res::add, (int) index, Integer.MAX_VALUE, updated.getSize()))
                        .thenApply(x -> res));
    }

    @Override
    public CompletableFuture<Boolean> addMessage(SignedMessage msg) {
        byte[] raw = msg.serialize();
        return network.synchronizer.applyComplexUpdate(messages.owner(), messages.signingPair(),
                (s, committer) -> messages.getUpdated(s, network)
                .thenCompose(m -> m.overwriteSection(s, committer, AsyncReader.build(raw), m.getSize(),
                        m.getSize() + raw.length, network, crypto, x -> {})))
                .thenCompose(s -> messages.getUpdated(s, network).thenApply(updated -> {
                    this.messages = updated;
                    return true;
                }));
    }
}
