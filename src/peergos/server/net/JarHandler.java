package peergos.server.net;

import java.io.IOException;
import java.nio.file.Path;

public class JarHandler extends StaticHandler {
    private final Path root;

    public JarHandler(boolean isGzip, Path root) {
        super(isGzip);
        this.root = root;
    }

    @Override
    public Asset getAsset(String resourcePath) throws IOException {
        String pathWithinJar = root.resolve(resourcePath).toString();
        byte[] data = StaticHandler.readResource(getClass().getResourceAsStream(pathWithinJar), false);
        return new Asset(data);
    }
}
