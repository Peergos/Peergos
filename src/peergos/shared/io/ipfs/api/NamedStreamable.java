package peergos.shared.io.ipfs.api;

import java.io.*;
import java.net.*;
import java.util.*;

public interface NamedStreamable
{
    InputStream getInputStream() throws IOException;

    Optional<String> getName();

    boolean isDirectory();

    default byte[] getContents() throws IOException {
        InputStream in = getInputStream();
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int r;
        while ((r=in.read(tmp))>= 0)
            bout.write(tmp, 0, r);
        return bout.toByteArray();
    }

    class NativeFile implements NamedStreamable {
        private final File source;
        private final String relativePath;

        public NativeFile(String relativePath, File source) {
            this.source = source;
            this.relativePath = relativePath;
        }

        public NativeFile(File source) {
            this("", source);
        }

        public InputStream getInputStream() throws IOException {
            return new FileInputStream(source);
        }

        public boolean isDirectory() {
            return source.isDirectory();
        }

        public File getFile() {
            return source;
        }

        public Optional<String> getName() {
            try {
                return Optional.of(URLEncoder.encode(relativePath + source.getName(), "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
    }

    class ByteArrayWrapper implements NamedStreamable {
        private final Optional<String> name;
        private final byte[] data;

        public ByteArrayWrapper(byte[] data) {
            this(Optional.empty(), data);
        }

        public ByteArrayWrapper(String name, byte[] data) {
            this(Optional.of(name), data);
        }

        public ByteArrayWrapper(Optional<String> name, byte[] data) {
            this.name = name;
            this.data = data;
        }

        public boolean isDirectory() {
            return false;
        }

        public InputStream getInputStream() throws IOException {
            return new ByteArrayInputStream(data);
        }

        public Optional<String> getName() {
            return name;
        }
    }
}
