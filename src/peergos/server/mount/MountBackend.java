package peergos.server.mount;

import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

import java.nio.file.Path;

public interface MountBackend {

    void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception;

    void disable();

    java.util.Optional<String> activeMountPoint();
}
