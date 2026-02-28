package peergos.server.storage;

import peergos.server.UserService;
import peergos.server.storage.admin.Admin;
import peergos.shared.storage.controller.HttpInstanceAdmin;
import peergos.shared.user.HttpPoster;
import peergos.shared.util.Futures;

import java.util.concurrent.CompletableFuture;

public class LocalVersionInstanceAdmin extends HttpInstanceAdmin {

    public LocalVersionInstanceAdmin(HttpPoster poster) {
        super(poster);
    }

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return Futures.of(new VersionInfo(UserService.CURRENT_VERSION, Admin.getSourceVersion()));
    }
}
