import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.stream.*;
import java.util.zip.*;

public class ReproducibleJar {
    public static void main(String[] args) throws Exception {
        Path inputJar = Paths.get(args[0]);
        long timeStamp = Long.parseLong(args[1]);

        URI uri = URI.create("jar:" + inputJar.toUri());
        byte[] newJar;
        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            Iterable<Path> roots = fs.getRootDirectories();
            Path root = roots.iterator().next();
            newJar = setAllTimes(root, FileTime.fromMillis(timeStamp));
        }
        Files.write(inputJar, newJar, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static byte[] setAllTimes(Path root, FileTime lastModified) {
        Map<Path, byte[]> files = new TreeMap<>();
        try {
            Set<Path> all = Files.walk(root).collect(Collectors.toSet());
            for (Path p: all) {
                if (! Files.isDirectory(p))
                    files.put(p, Files.readAllBytes(p));
            }
            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ZipOutputStream zout = new ZipOutputStream(bout);
            for (Path p: files.keySet()) {
                ZipEntry zipEntry = new ZipEntry(p.toString().substring(1));
                zipEntry.setLastModifiedTime(lastModified);
                zout.putNextEntry(zipEntry);
                zout.write(files.get(p));
                zout.closeEntry();
            }
            zout.close();
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
