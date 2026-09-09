package peergos.shared.util;

public class Constants {
    public static final String DHT_URL = "/api/v0/";
    public static final String PEERGOS_API_PREFIX = "peergos/v0/";
    public static final String ADMIN_URL = PEERGOS_API_PREFIX + "admin/";
    public static final String BATS_URL = PEERGOS_API_PREFIX + "bats/";
    /** How long to give a read proxied to a user's home server before falling back to our mirror.
     *
     *  It has to be comfortably less than the budget of the request we are answering, or the caller
     *  gives up before the fallback we have is ever tried: a home server that has just died is not
     *  reported unreachable until the dial times out, which takes longer than a default request
     *  allows. Only reads with a local copy to fall back on use this.
     */
    public static final int PROXIED_READ_TIMEOUT_MILLIS = 5_000;

    public static final String MUTABLE_POINTERS_URL = PEERGOS_API_PREFIX + "mutable/";
    public static final String LOGIN_URL = PEERGOS_API_PREFIX + "login/";
    public static final String CORE_URL = PEERGOS_API_PREFIX + "core/";
    public static final String SOCIAL_URL = PEERGOS_API_PREFIX + "social/";
    public static final String SPACE_USAGE_URL = PEERGOS_API_PREFIX + "storage/";
    public static final String SERVER_MESSAGE_URL = PEERGOS_API_PREFIX + "server-message/";
    public static final String ANDROID_FILE_REFLECTOR = PEERGOS_API_PREFIX + "reflector/";
    public static final String STOP = PEERGOS_API_PREFIX + "stop/";
    public static final String CONFIG = PEERGOS_API_PREFIX + "config/";
    public static final String SYNC = PEERGOS_API_PREFIX + "sync/";
    public static final String MOUNT = PEERGOS_API_PREFIX + "mount/";
    public static final String WEBAUTHN = PEERGOS_API_PREFIX + "webauthn/";

    public static final String PUBLIC_FILES_URL = "public/";
}
