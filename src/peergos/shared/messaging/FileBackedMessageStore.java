package peergos.shared.messaging;

import peergos.shared.*;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

public class FileBackedMessageStore implements MessageStore {

    private FileWrapper messages;
    private FileWrapper indexFile;
    private final NetworkAccess network;
    private final Crypto crypto;
    private final UserContext context;
    private final Path sharedDir;
    private final Supplier<CompletableFuture<Pair<FileWrapper, FileWrapper>>> filesUpdater;

    public FileBackedMessageStore(FileWrapper messages,
                                  FileWrapper indexFile,
                                  UserContext context,
                                  Path sharedDir,
                                  Supplier<CompletableFuture<Pair<FileWrapper, FileWrapper>>> filesUpdater) {
        this.messages = messages;
        this.indexFile = indexFile;
        this.network = context.network;
        this.crypto = context.crypto;
        this.context = context;
        this.sharedDir = sharedDir;
        this.filesUpdater = filesUpdater;
    }

    private CompletableFuture<Pair<Long, Integer>> getChunkByteOffset(long index) {
        if (messages.getSize() < 5*1024*1024)
            return Futures.of(new Pair<>(0L, (int) index));
        return indexFile.getUpdated(network)
                .thenCompose(updated -> updated.getInputStream(network, crypto, x -> {})
                        .thenCompose(reader -> findOffset(reader, new byte[1024],
                                0L, 0L, index, updated.getSize())));

    }

    private CompletableFuture<Pair<Long, Integer>> findOffset(AsyncReader r,
                                                              byte[] buf,
                                                              long previousIndex,
                                                              long previousByteOffset,
                                                              long index,
                                                              long remainingBytes) {
        if (remainingBytes == 0)
            return Futures.of(new Pair<>(previousByteOffset, (int)(index - previousIndex)));
        int toRead = remainingBytes > buf.length ? buf.length : (int) remainingBytes;
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(buf));
        return r.readIntoArray(buf, 0, toRead)
                .thenCompose(read -> {
                    long prevIndex = previousIndex;
                    long prevBytes = previousByteOffset;
                    for (int i=0; i < read / 16; i++) {
                        try {
                            long msgIndex = din.readLong();
                            long byteOffset = din.readLong();
                            if (msgIndex > index)
                                return Futures.of(new Pair<>(prevBytes, (int)(index - prevIndex)));
                            prevIndex = msgIndex;
                            prevBytes = byteOffset;
                        } catch (IOException e) {}
                    }
                    return findOffset(r, buf, prevIndex, prevBytes, index, remainingBytes - read);
                });
    }

    @Override
    public CompletableFuture<List<SignedMessage>> getMessagesFrom(long index) {
        List<SignedMessage> res = new ArrayList<>();
        return messages.getUpdated(network)
                .thenCompose(updated -> updated.getInputStream(network, crypto, x -> {})
                        .thenCompose(reader -> getChunkByteOffset(index)
                                .thenCompose(p -> reader.seek(p.left)
                                        .thenCompose(seeked -> seeked.parseLimitedStream(SignedMessage::fromCbor,
                                        res::add, p.right, Integer.MAX_VALUE, updated.getSize() - p.left))))
                        .thenApply(x -> res));
    }

    @Override
    public CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex) {
        List<SignedMessage> res = new ArrayList<>();
        return messages.getUpdated(network)
                .thenCompose(updated -> updated.getInputStream(network, crypto, x -> {})
                        .thenCompose(reader -> getChunkByteOffset(fromIndex)
                                .thenCompose(p -> reader.seek(p.left)
                                        .thenCompose(seeked -> seeked.parseLimitedStream(SignedMessage::fromCbor,
                                                res::add, p.right, (int) (toIndex - fromIndex), updated.getSize() - p.left))))
                        .thenApply(x -> res));
    }

    @Override
    public CompletableFuture<Boolean> addMessage(long msgIndex, SignedMessage msg) {
        byte[] raw = msg.serialize();
        return network.synchronizer.applyComplexUpdate(messages.owner(), messages.signingPair(),
                (s, committer) -> messages.getUpdated(s, network)
                        .thenCompose(mIn -> mIn.clean(mIn.version, committer, network, crypto))
                        .thenCompose(p -> p.left.overwriteSection(p.right, committer, AsyncReader.build(raw), p.left.getSize(),
                                p.left.getSize() + raw.length, network, crypto, x -> {}).thenCompose(s2 -> {
                            long size = p.left.getSize();
                            boolean newChunk = (raw.length + size)/Chunk.MAX_SIZE > size/Chunk.MAX_SIZE;
                            if (! newChunk)
                                return Futures.of(s2);
                            ByteArrayOutputStream bout = new ByteArrayOutputStream();
                            DataOutputStream dout = new DataOutputStream(bout);
                            try {
                                dout.writeLong(msgIndex + 1);
                                dout.writeLong(size + raw.length);
                            } catch (IOException e) {} // can't happen
                            byte[] twoLongs = bout.toByteArray();
                            return indexFile.getUpdated(s2, network)
                                    .thenCompose(updatedIndex -> updatedIndex.overwriteSection(s2, committer,
                                            AsyncReader.build(twoLongs), updatedIndex.getSize(),
                                            updatedIndex.getSize() + twoLongs.length, network, crypto, x -> {}));

                        })))
                .thenCompose(s -> messages.getUpdated(s, network).thenApply(updated -> {
                    this.messages = updated;
                    return true;
                }));
    }

    @Override
    public synchronized CompletableFuture<Boolean> revokeAccess(String username) {
        return context.unShareReadAccess(sharedDir, username)
                .thenCompose(b -> filesUpdater.get())
                .thenApply(files -> {
                    this.messages = files.left;
                    this.indexFile = files.right;
                    return true;
                });
    }
}
