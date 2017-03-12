package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;

public class FileHandler extends StaticHandler
{
    private final Path root;
    public FileHandler(Path root, boolean isGzip) throws IOException {
        super(isGzip);
        this.root = root;
    }

    @Override
    public Asset getAsset(String resourcePath) throws IOException {
        String stem = resourcePath.startsWith("/")  ?  resourcePath.substring(1) : resourcePath;
        Path fullPath = root.resolve(stem);
        byte[] bytes = Files.readAllBytes(fullPath);
        return new Asset(bytes);
    }
}
