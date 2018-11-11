package peergos.server.storage;

import peergos.server.util.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.cid.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.util.*;

import java.net.*;
import java.nio.file.*;

public class IpfsInstaller {

    public static void install(Path targetFile) {
        String os = System.getProperty("os.name");
        switch (os) {
            case "Linux": {
                Linux.install(targetFile);
                return;
            }
            default: throw new IllegalStateException("Unable to install IPFS for unknown Operating System: " + os);
        }
    }

    private static class Linux {
        public static void install(Path targetFile) {
            try {
                URI uri = new URI("http://localhost:8080/ipfs/QmUkvZb5wLdVR13eTueJbAidqA1piCfRs7nuGy3PU4nGi9");
                Logging.LOG().info("Downloading IPFs binary...");
                byte[] raw = Serialize.readFully(uri.toURL().openStream());
                Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(raw));
                Multihash expected = Cid.decode("QmcTFX9sQsn8Pn75w2QKPb1chtvgREnWWXTtb1sV9dzt9x");
                if (! computed.equals(expected))
                    throw new IllegalStateException("Incorrect hash for ipfs binary, aborting install!");
                Files.write(targetFile, raw);
                targetFile.toFile().setExecutable(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static class LocalHasher {
        public static void main(String[] a) throws Exception {
            byte[] bytes = Files.readAllBytes(Paths.get("/path/to/local/ipfs"));
            Multihash computed = new Multihash(Multihash.Type.sha2_256, Hash.sha256(bytes));
            System.out.println(computed);
        }
    }
}
