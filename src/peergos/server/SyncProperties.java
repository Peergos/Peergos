package peergos.server;

import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;

public class SyncProperties {

    public final Args args;
    public final SyncRunner syncer;
    public final HostDirEnumerator hostDirs;

    public SyncProperties(Args args, SyncRunner syncer, HostDirEnumerator hostDirs) {
        this.args = args;
        this.syncer = syncer;
        this.hostDirs = hostDirs;
    }
}
