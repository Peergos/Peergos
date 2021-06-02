package peergos.server.net;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

public class FileHandler extends StaticHandler
{
    private final Path root;
    public FileHandler(CspHost host,
                       List<String> blockstoreDomain,
                       List<String> appSubdomains,
                       Path root,
                       boolean includeCsp,
                       boolean isGzip) {
        super(host, blockstoreDomain, appSubdomains, includeCsp, isGzip);
        this.root = root;
    }

    @Override
    public Asset getAsset(String resourcePath) throws IOException {
        String stem = resourcePath.startsWith("/")  ?  resourcePath.substring(1) : resourcePath;
        Path fullPath = root.resolve(stem);
        byte[] bytes = readResource(new FileInputStream(fullPath.toFile()), isGzip());
        return new Asset(bytes);
    }
}
