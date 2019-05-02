package peergos.shared.user;

import peergos.shared.user.fs.FileWrapper;

import java.util.concurrent.CompletableFuture;

public interface FileWrapperUpdater {
    /**
     * If a reference to a FileWrapper has become invalid (eg. updated by another client) then this will supply an
     * updated view.
     */
    CompletableFuture<FileWrapper> updated(Snapshot version);
}
