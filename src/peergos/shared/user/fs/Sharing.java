package peergos.shared.user.fs;

public class Sharing {
    public static final int FILE_POINTER_SIZE = 159; // fp.toCbor().toByteArray() DOESN'T INCLUDE .secret
    public static final int SHARING_FILE_MAX_SIZE = FILE_POINTER_SIZE * 2; // record size * 10000
    public static final String SHARING_FILE_PREFIX = "sharing.";
    public static final String CAPABILITY_CACHE_FILE = "capability.cache";
}
