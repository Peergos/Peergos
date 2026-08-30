package peergos.server.mount;

import peergos.server.cfapi.CloudFilesMount;
import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** Windows CFAPI {@link MountBackend}. {@link #disable()} fully cleans up
 *  (CF kernel binding, shell registry, state DB) via {@code unmount()}. */
public class CloudFilesBackend implements MountBackend {

    private final AtomicReference<CloudFilesMount> active = new AtomicReference<>();

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception {
        // This backend is only the drive. Calendar and contacts are served by the WebDAV bridge,
        // which this one does not start, so on Windows they still need the WebDAV backend.
        if (! config.mountDrive) {
            disable();
            return;
        }
        CloudFilesMount mount = CloudFilesMount.mount(context, peergosDir);
        CloudFilesMount prev = active.getAndSet(mount);
        if (prev != null) prev.unmount();
    }

    @Override
    public void disable() {
        CloudFilesMount mount = active.getAndSet(null);
        if (mount != null) mount.unmount();
    }

    @Override
    public java.util.Optional<String> activeMountPoint() {
        CloudFilesMount mount = active.get();
        return mount == null ? java.util.Optional.empty() : java.util.Optional.of(mount.getMountPoint());
    }
}
