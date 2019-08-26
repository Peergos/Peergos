package peergos.server.simulation;

import peergos.shared.user.fs.FileProperties;

public interface Stat {
    String user();
    FileProperties fileProperties();
    boolean isReadable();
    boolean isWritable();
}