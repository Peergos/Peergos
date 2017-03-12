package peergos.server.net;

import java.io.*;
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
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        byte[] data = StaticHandler.readResource(context.getResourceAsStream(pathWithinJar), isGzip());
        return new Asset(data);
    }
}
