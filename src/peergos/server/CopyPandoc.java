package peergos.server;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.*;

import java.net.URL;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class CopyPandoc {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("http://localhost:8000"), false, Optional.empty()).get();
        String username = "q";
        String password = "qq";
        UserContext context = UserContext.signIn(username, password, Main::getMfaResponseCLI, network, crypto).get();
        String appName = "pandoc-test6";
        String installAppFromFolder = context.username + "/" + appName;
        peergos.shared.user.App.init(context, appName).thenApply(ready -> {
            copyAssetsFolder(context, appName, installAppFromFolder).join();
            return null;
        });
    }
    private static CompletableFuture<Boolean> copyAssetsFolder(UserContext context, String appName, String installAppFromFolder) {
        CompletableFuture<Boolean> future = peergos.shared.util.Futures.incomplete();
        String appFolderPath = "/" + context.username + "/.apps/" + appName;
        context.getByPath(installAppFromFolder + "/assets").thenApply(srcAssetsDirOpt -> {
            if (srcAssetsDirOpt.isPresent()) {
                context.getByPath(appFolderPath).thenApply(destAppDirOpt -> {
                    srcAssetsDirOpt.get().copyTo(destAppDirOpt.get(), context)
                            .thenApply(res -> {
                                future.complete(true);
                                return null;
                            }).exceptionally(throwable -> {
                                System.out.println("unable to copy app assets. error: " + throwable.getMessage());
                                future.complete(false);
                                return null;
                            });
                    return null;
                });
            }else {
                future.complete(false);
            }
            return null;
        });
        return future;
    }
}
