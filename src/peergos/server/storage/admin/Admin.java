package peergos.server.storage.admin;

import peergos.server.*;
import peergos.shared.storage.controller.*;

import java.util.concurrent.*;

public class Admin implements InstanceAdmin {

    public Admin() {}

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return CompletableFuture.completedFuture(new VersionInfo(UserService.CURRENT_VERSION));
    }
}
