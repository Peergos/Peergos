package peergos.server;

import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.user.UserContext;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.util.PathUtil;

import java.io.Console;
import java.net.URL;
import java.util.Collections;
import java.util.Optional;

public class WritableSecretLink {

    public static void main(String[] args) throws Exception {
        Crypto crypto = Main.initCrypto();
        int targetPort = 8000;
        NetworkAccess network = Builder.buildJavaNetworkAccess(
                new URL("http://localhost:" + targetPort + "/"), false, Optional.empty()).join();
        //NetworkAccess network = Builder.buildJavaNetworkAccess(new URL("https://localhost:8000"), true).get();

        String username = "q";
        String password = "qq";
        UserContext context = UserContext.signIn(username, password, network, crypto).get();
        FileWrapper userRoot = context.getUserRoot().get();
        FileWrapper wf = userRoot.getChild("wf", crypto.hasher, network).join().get();

        String fileLink = wf.toWritableLink();
        String filename = "";
        String path = "/q/wf";
        //String json = "{open:true%2cfilename:'wf'%2cpath:'" + path + "'%2csecretLink:true%2clink:'" + fileLink +"'}";
        String json = "#%7B%22secretLink%22:true%2c%22link%22:%22" + fileLink + "%22%2c%22open%22:true%2c%22path%22:%22/q/%22%7D";
        System.currentTimeMillis();
    }
}
