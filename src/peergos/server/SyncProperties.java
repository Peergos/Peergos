package peergos.server;

import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.shared.util.Either;

import java.nio.file.Path;

public class SyncProperties {

    public final SyncConfig config;
    public final Path peergosDir;
    public final SyncRunner syncer;
    public final Either<HostDirEnumerator, HostDirChooser> hostDirs;

    public SyncProperties(SyncConfig config, Path peergosDir, SyncRunner syncer, Either<HostDirEnumerator, HostDirChooser> hostDirs) {
        this.config = config;
        this.peergosDir = peergosDir;
        this.syncer = syncer;
        this.hostDirs = hostDirs;
    }
}
