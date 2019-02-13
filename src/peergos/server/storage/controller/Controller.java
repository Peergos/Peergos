package peergos.server.storage.controller;

import peergos.server.*;
import peergos.shared.storage.controller.*;

import java.util.concurrent.*;

public class Controller implements StorageController {

    public Controller() {}

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return CompletableFuture.completedFuture(new VersionInfo(UserService.CURRENT_VERSION));
    }
}
