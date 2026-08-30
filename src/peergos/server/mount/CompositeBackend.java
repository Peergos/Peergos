package peergos.server.mount;

import peergos.server.util.Logging;
import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Several backends driven by one login.
 *
 *  Windows is the case this exists for: CFAPI gives the drive, but it serves no CalDAV or CardDAV,
 *  so the bridge has to run beside it. Both are handed the same UserContext, so there is still one
 *  sign in and one secret store entry between them.
 */
public class CompositeBackend implements MountBackend {

    private static final Logger LOG = Logging.LOG();

    private final List<MountBackend> backends;

    public CompositeBackend(MountBackend... backends) {
        this.backends = Arrays.asList(backends);
    }

    @Override
    public void enable(MountConfig config, UserContext context, Path peergosDir) throws Exception {
        for (MountBackend backend : backends)
            backend.enable(config, context, peergosDir);
    }

    @Override
    public void disable() {
        // one that throws must not leave the others running
        for (MountBackend backend : backends) {
            try {
                backend.disable();
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Failed to disable " + backend.getClass().getSimpleName(), e);
            }
        }
    }

    @Override
    public Optional<String> activeMountPoint() {
        return backends.stream()
                .map(MountBackend::activeMountPoint)
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public boolean supportsCalendar() {
        return backends.stream().anyMatch(MountBackend::supportsCalendar);
    }

    @Override
    public boolean supportsContacts() {
        return backends.stream().anyMatch(MountBackend::supportsContacts);
    }

    @Override
    public boolean usesDavClients() {
        return backends.stream().anyMatch(MountBackend::usesDavClients);
    }
}
