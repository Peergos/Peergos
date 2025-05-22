package peergos.server;

import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;
import peergos.shared.util.Either;

public class SyncProperties {

    public final Args args;
    public final SyncRunner syncer;
    public final Either<HostDirEnumerator, HostDirChooser> hostDirs;

    public SyncProperties(Args args, SyncRunner syncer, Either<HostDirEnumerator, HostDirChooser> hostDirs) {
        this.args = args;
        this.syncer = syncer;
        this.hostDirs = hostDirs;
    }
}
