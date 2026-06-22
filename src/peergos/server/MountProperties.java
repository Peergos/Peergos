package peergos.server;

import peergos.server.webdav.MountConfig;
import java.nio.file.Path;

public class MountProperties {
    public final MountConfig config;
    public final Path peergosDir;
    public final String peergosUrl;

    public MountProperties(MountConfig config, Path peergosDir, String peergosUrl) {
        this.config = config;
        this.peergosDir = peergosDir;
        this.peergosUrl = peergosUrl;
    }
}
