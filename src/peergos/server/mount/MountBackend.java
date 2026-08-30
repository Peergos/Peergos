package peergos.server.mount;

import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

import java.nio.file.Path;

public interface MountBackend {

    void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception;

    void disable();

    java.util.Optional<String> activeMountPoint();

    /** Whether this platform can sync calendars at all, which is what the UI offers a switch for. */
    default boolean supportsCalendar() {
        return false;
    }

    default boolean supportsContacts() {
        return false;
    }

    /** Whether calendars and contacts are reached by pointing a CalDAV or CardDAV client at the
     *  bridge, in which case the user has to be shown its address and password. Android syncs
     *  through the platform's own account instead, so there is nothing to point anything at. */
    default boolean usesDavClients() {
        return false;
    }
}
