package peergos.server.tests.simulation;

import peergos.shared.user.fs.FileProperties;
import peergos.shared.user.fs.FileWrapper;

public interface Stat {
    String user();
    FileProperties fileProperties();
    boolean isReadable();
    boolean isWritable();
}