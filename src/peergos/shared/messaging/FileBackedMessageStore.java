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

    private final FileWrapper messages;
    private final FileWrapper indexFile;
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
        return indexFile.getInputStream(indexFile.version.get(indexFile.writer()).props.get(), network, crypto, x -> {})
                        .thenCompose(reader -> findOffset(reader, new byte[1024],
                                0L, 0L, index, indexFile.getSize()));
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
        return messages.getInputStream(messages.version.get(messages.writer()).props.get(), network, crypto, x -> {})
                        .thenCompose(reader -> getChunkByteOffset(index)
                                .thenCompose(p -> reader.seek(p.left)
                                        .thenCompose(seeked -> seeked.parseLimitedStream(SignedMessage::fromCbor,
                                        res::add, p.right, Integer.MAX_VALUE, messages.getSize() - p.left))))
                        .thenApply(x -> res);
    }

    @Override
    public CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex) {
        List<SignedMessage> res = new ArrayList<>();
        return messages.getInputStream(messages.version.get(messages.writer()).props.get(), network, crypto, x -> {})
                        .thenCompose(reader -> getChunkByteOffset(fromIndex)
                                .thenCompose(p -> reader.seek(p.left)
                                        .thenCompose(seeked -> seeked.parseLimitedStream(SignedMessage::fromCbor,
                                                res::add, p.right, (int) (toIndex - fromIndex), messages.getSize() - p.left))))
                        .thenApply(x -> res);
    }

    @Override
    public CompletableFuture<Snapshot> addMessages(Snapshot initialVersion, Committer committer, long msgIndex, List<SignedMessage> msgs) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        List<Integer> sizes = new ArrayList<>();
        for (SignedMessage msg : msgs) {
            try {
                byte[] msgData = msg.serialize();
                buf.write(msgData);
                sizes.add(msgData.length);
            } catch (IOException e) {} // can't happen
        }
        byte[] raw = buf.toByteArray();
        return messages.clean(initialVersion, committer, network, crypto)
                .thenCompose(p -> p.left.overwriteSection(p.right, committer, AsyncReader.build(raw), p.left.getSize(),
                        p.left.getSize() + raw.length, network, crypto, x -> {}).thenCompose(s2 -> {
                    long size = p.left.getSize();
                    boolean newChunk = (raw.length + size)/Chunk.MAX_SIZE > size/Chunk.MAX_SIZE;
                    if (! newChunk)
                        return Futures.of(s2);
                    ByteArrayOutputStream bout = new ByteArrayOutputStream();
                    DataOutputStream dout = new DataOutputStream(bout);
                    // find message that crossed chunk boundary
                    int count=0;
                    int totalSize = 0;
                    while (count < sizes.size()) {
                        totalSize += sizes.get(count);
                        count++;
                        if ((totalSize + size)/Chunk.MAX_SIZE > size/Chunk.MAX_SIZE)
                            break;
                    }
                    try {
                        dout.writeLong(msgIndex + count);
                        dout.writeLong(size + totalSize);
                    } catch (IOException e) {} // can't happen
                    byte[] twoLongs = bout.toByteArray();
                    return indexFile.overwriteSection(s2, committer,
                                    AsyncReader.build(twoLongs), indexFile.getSize(),
                            indexFile.getSize() + twoLongs.length, network, crypto, x -> {});

                }));
    }

    @Override
    public synchronized CompletableFuture<Snapshot> revokeAccess(Set<String> usernames, Snapshot s, Committer c) {
        return context.unShareReadAccessWith(sharedDir, usernames, s, c);
    }
}
