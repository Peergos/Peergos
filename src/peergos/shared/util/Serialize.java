package peergos.shared.util;

import jsinterop.annotations.*;
import peergos.shared.*;
import peergos.shared.cbor.*;
import peergos.shared.user.fs.*;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.function.*;

public class Serialize
{

    @JsMethod
    public static byte[] newByteArray(int len) {
        return new byte[len];
    }

    public static void serialize(byte[] b, DataOutput dout) throws IOException
    {
        dout.writeInt(b.length);
        if (b.length > 0)
            dout.write(b);
    }

    public static void serialize(String s, DataOutput dout) throws IOException
    {
        dout.writeInt(s.length());
        dout.write(s.getBytes());
    }

    public static String deserializeString(DataInput din, int len) throws IOException
    {
        int l = din.readInt();
        if (l > len)
            throw new IOException("String size "+ l + " too long.");
        byte[] b = new byte[l];
        din.readFully(b);
        return new String(b);
    }

    public static byte[] deserializeByteArray(int length, DataInput din, int maxLength) throws IOException
    {
        if (length == 0)
            return new byte[0];

        byte[] b = getByteArray(length, maxLength);
        din.readFully(b);
        return b;
    }

    public static byte[] deserializeByteArray(DataInput din, int maxLength) throws IOException
    {
        int l = din.readInt();
        if (l == 0)
            return new byte[0];

        byte[] b = getByteArray(l, maxLength);
        din.readFully(b);
        return b;
    }

    public static byte[] getByteArray(int len, int maxLength) throws IOException
    {
        if (len > maxLength)
            throw new IOException("byte array of size "+ len +" too big.");
        return new byte[len];
    }

    public static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream bout =  new ByteArrayOutputStream();
        byte[] b =  new  byte[0x1000];
        int nRead;
        while ((nRead = in.read(b, 0, b.length)) != -1 )
            bout.write(b, 0, nRead);
        in.close();
        return bout.toByteArray();
    }

    public static byte[] readFully(InputStream in, int maxSize) throws IOException {
        ByteArrayOutputStream bout =  new ByteArrayOutputStream();
        byte[] b =  new  byte[0x1000];
        int nRead;
        while ((nRead = in.read(b, 0, b.length)) != -1 )
            bout.write(b, 0, nRead);
        in.close();
        return bout.toByteArray();
    }

    public static byte[] read(InputStream in, int size) throws IOException {
        byte[] res = new byte[size];
        byte[] b =  new  byte[0x1000];
        int nRead;
        int offset = 0;
        while (offset < size && (nRead = in.read(b, 0, b.length)) != -1 ) {
            System.arraycopy(b, 0, res, offset, nRead);
            offset += nRead;
        }
        return res;
    }

    public static CompletableFuture<byte[]> readFully(FileWrapper f, Crypto crypto, NetworkAccess network) {
        long size = f.getSize();
        return f.getInputStream(f.version.get(f.writer()).props.get(), network, crypto, x -> {})
                .thenCompose(stream -> readFully(stream, size));
    }

    public static CompletableFuture<byte[]> readFully(AsyncReader in, long size) {
        byte[] res = new byte[(int)size];
        return in.readIntoArray(res, 0, (int) size).thenApply(i -> res);
    }

    @JsMethod
    public static <T> T parse(byte[] in, Function<Cborable, T> parser) {
        return Cborable.parser(parser).apply(in);
    }

    public static <T> CompletableFuture<T> parse(FileWrapper f,
                                                 Function<Cborable, T> parser,
                                                 NetworkAccess network,
                                                 Crypto crypto) {
        byte[] res = new byte[(int)f.getSize()];
        return f.getInputStream(f.version.get(f.writer()).props.get(),network, crypto, x -> {})
                .thenCompose(reader -> reader.readIntoArray(res, 0, (int) f.getSize()))
                .thenApply(i -> Cborable.parser(parser).apply(res));
    }

    public static <T> CompletableFuture<T> parse(AsyncReader in, long size, Function<Cborable, T> parser) {
        byte[] res = new byte[(int)size];
        return in.readIntoArray(res, 0, (int) size)
                .thenApply(i -> Cborable.parser(parser).apply(res));
    }

    public static CompletableFuture<Boolean> readFullArray(AsyncReader in, byte[] result) {
        return in.readIntoArray(result, 0, result.length).thenApply(b -> true);
    }

    public static byte[] ensureSize(byte[] data, int  size) {
        boolean iBigger = data.length < size;
        return  iBigger ? Arrays.copyOf(data, size) : data;
    }
}
