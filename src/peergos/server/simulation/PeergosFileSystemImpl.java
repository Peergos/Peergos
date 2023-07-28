package peergos.server.simulation;

import peergos.shared.social.FollowRequestWithCipherText;
import peergos.shared.user.*;
import peergos.shared.user.fs.*;
import peergos.shared.user.fs.transaction.*;
import peergos.shared.util.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class PeergosFileSystemImpl implements FileSystem {

    private final UserContext userContext;

    public PeergosFileSystemImpl(UserContext userContext) {
        this.userContext = userContext;
    }

    @Override
    public String user() {
        return userContext.username;
    }

    private FileWrapper getPath(Path path) {
        Optional<FileWrapper> res = userContext.getByPath(path).join();
        if (res.isEmpty()) {
            userContext.getByPath(path).join();
            throw new IllegalStateException("Unable to retrieve file at " + path);
        }
        return res.get();
    }

    private FileWrapper getParent(Path path) {
        return getPath(path.getParent());
    }

    @Override
    public byte[] read(Path path, BiConsumer<Long, Long> progressConsumer) {
        FileWrapper wrapper = getPath(path);
        long size = wrapper.getFileProperties().size;
        ProgressConsumer<Long> monitor = (readBytes) -> progressConsumer.accept(readBytes, size);
        AsyncReader in = wrapper.getInputStream(userContext.network, userContext.crypto, size, 1, monitor).join();
        return Serialize.readFully(in, size).join();
    }

    @Override
    public void write(Path path, byte[] data, Consumer<Long> progressConsumer) {
        FileWrapper directory = getParent(path);
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        String fileName = path.getFileName().toString();
        ProgressConsumer<Long> pc  = l -> progressConsumer.accept(l);
        FileWrapper fileWrapper = directory.uploadFileJS(fileName, resetableFileInputStream, (int)(data.length >> 32), (int) data.length,
                true, userContext.mirrorBatId(), userContext.network, userContext.crypto, pc, userContext.getTransactionService(), f -> Futures.of(false)).join();
    }

    @Override
    public void writeSubtree(Path path, Stream<FileWrapper.FolderUploadProperties> folders, Function<FileUploadTransaction, CompletableFuture<Boolean>> resumeFile) {
        FileWrapper parentDir = getPath(path);
        parentDir.uploadSubtree(folders, userContext.mirrorBatId(), userContext.network, userContext.crypto, userContext.getTransactionService(), resumeFile, () -> true).join();
    }

    @Override
    public void modify(Path path, byte[] data, Consumer<Long> progressConsumer) {
        FileWrapper file = getPath(path);
        file.overwriteFileJS(AsyncReader.build(data), 0, data.length,
                userContext.network, userContext.crypto, l -> progressConsumer.accept(l)).join();
    }

    @Override
    public void delete(Path path) {
        FileWrapper directory = getParent(path);
        FileWrapper updatedParent = getPath(path).remove(directory, path, userContext).join();
    }

    @Override
    public List<Path> ls(Path path, boolean showHidden) {
        return getPath(path).getChildren(userContext.crypto.hasher, userContext.network)
                .join()
                .stream()
                .filter(e -> showHidden || ! e.getFileProperties().isHidden)
                .map(e -> path.resolve(e.getName()))
                .collect(Collectors.toList());
    }

    @Override
    public void grant(Path path, String user, Permission permission) {
        Set<String> userSet = Stream.of(user).collect(Collectors.toSet());
        switch (permission) {
            case READ:
                userContext.shareReadAccessWith(path, userSet).join();
                return;
            case WRITE:
                userContext.shareWriteAccessWith(path, userSet).join();
                return;
        }
        throw new IllegalStateException();
    }

    @Override
    public void revoke(Path path, String user, Permission permission) {

        switch (permission) {
            case READ:
                userContext.unShareReadAccess(path, user).join();
                return;
            case WRITE:
                userContext.unShareWriteAccess(path, user).join();
                return;
        }
        throw new IllegalStateException();
    }

    @Override
    public Stat stat(Path path) {
        FileWrapper fileWrapper = getPath(path);
        return new Stat() {
            @Override
            public boolean isReadable() {
                return fileWrapper.isReadable();
            }

            @Override
            public boolean isWritable() {
                return fileWrapper.isWritable();
            }

            @Override
            public String user() {
                return fileWrapper.getOwnerName();
            }

            @Override
            public FileProperties fileProperties() {
                return fileWrapper.getFileProperties();
            }
        };

    }

    @Override
    public void mkdir(Path path) {
        getParent(path).mkdir(path.getFileName().toString(),
                userContext.network,
                false,
                userContext.mirrorBatId(),
                userContext.crypto).join();
    }

    @Override
    public void follow(FileSystem other, boolean reciprocate) {
        UserContext otherContext = ((PeergosFileSystemImpl) other).userContext;

        this.userContext.sendInitialFollowRequest(other.user()).join();
        List<FollowRequestWithCipherText> join = otherContext.processFollowRequests().join();
        FollowRequestWithCipherText req = join.stream()
                .filter(e -> e.getEntry().ownerName.equals(user()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException());
        otherContext.sendReplyFollowRequest(req, true, reciprocate).join();
        this.userContext.processFollowRequests().join();
    }

    @Override
    public Path getRandomSharedPath(Random random, Permission permission, String sharee) {
        throw new IllegalStateException("Not implemented");
    }

    @Override
    public List<String> getSharees(Path path, Permission permission) {
        FileSharedWithState sharing = userContext.sharedWith(path).join();
        switch (permission) {
            case READ:
                return new ArrayList<>(sharing.readAccess);
            case WRITE:
                return new ArrayList<>(sharing.writeAccess);
            default:
                throw new IllegalStateException();
        }
    }
}

