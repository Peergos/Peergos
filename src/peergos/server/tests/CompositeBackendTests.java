package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.mount.CompositeBackend;
import peergos.server.mount.MountBackend;
import peergos.server.webdav.MountConfig;
import peergos.shared.user.UserContext;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

public class CompositeBackendTests {

    /** A backend that throws when told its enable failed doesn't keep the others from hearing it */
    @Test
    public void everyBackendHearsAFailedEnable() {
        List<String> heard = new ArrayList<>();
        MountBackend throwing = backend(error -> { throw new IllegalStateException("broken"); });
        MountBackend listening = backend(heard::add);
        new CompositeBackend(throwing, listening).enableFailed("wrong password");
        Assert.assertEquals(List.of("wrong password"), heard);
    }

    private static MountBackend backend(Consumer<String> onFailedEnable) {
        return new MountBackend() {
            @Override
            public void enable(MountConfig config, UserContext context, Path peergosDir) {}

            @Override
            public void disable() {}

            @Override
            public Optional<String> activeMountPoint() {
                return Optional.empty();
            }

            @Override
            public void enableFailed(String error) {
                onFailedEnable.accept(error);
            }
        };
    }
}
