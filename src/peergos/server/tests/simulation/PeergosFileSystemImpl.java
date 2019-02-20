package peergos.server.tests.simulation;

import peergos.shared.user.UserContext;
import peergos.shared.user.fs.*;
import peergos.shared.util.Serialize;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
        return userContext.getByPath(path).join().get();
    }

    private FileWrapper getDirectory(Path path) {
        return getPath(path.getParent());
    }

    @Override
    public byte[] read(Path path) {
        FileWrapper wrapper = getPath(path);
        long size = wrapper.getFileProperties().size;
        AsyncReader in = wrapper.getInputStream(userContext.network, userContext.crypto.random, size, (l) -> {}).join();
        return Serialize.readFully(in, size).join();
    }

    @Override
    public void write(Path path, byte[] data) {
        FileWrapper directory = getDirectory(path);
        AsyncReader resetableFileInputStream = new AsyncReader.ArrayBacked(data);
        String fileName = path.getFileName().toString();
        FileWrapper fileWrapper = directory.uploadOrOverwriteFile(fileName, resetableFileInputStream, data.length,
                userContext.network, userContext.crypto.random, (l) -> {
                }, userContext.fragmenter(),
                userContext.getUserRoot().join().generateChildLocationsFromSize(data.length, userContext.crypto.random)).join();

    }

    @Override
    public void delete(Path path) {
        FileWrapper directory = getDirectory(path);
        FileWrapper updatedParent = getPath(path).remove(directory, userContext.network).join();
    }

    @Override
    public void grant(Path path, List<String> users, Permission permission) {
        Set<String> userSet = users.stream().collect(Collectors.toSet());
        switch (permission) {
            case READ:
                userContext.shareReadAccessWith(path, userSet);
                return;
            case WRITE:
                userContext.shareWriteAccessWith(path, userSet);
                return;
        }
        throw new IllegalStateException();
    }

    @Override
    public void revoke(Path path, List<String> users, Permission permission) {
        Set<String> userSet = users.stream().collect(Collectors.toSet());
        switch (permission) {
            case READ:
                userContext.unShareReadAccess(path, userSet).join();
                return;
            case WRITE:
                userContext.unShareWriteAccess(path, userSet).join();
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
                return this.user();
            }

            @Override
            public FileProperties fileProperties() {
                return fileWrapper.getFileProperties();
            }
        };

    }
}

