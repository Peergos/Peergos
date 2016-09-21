package peergos.shared.util;

import java.io.*;

public class DataSource implements DataInput {

    private final DataInputStream din;

    public DataSource(byte[] source) {
        ByteArrayInputStream bin = new ByteArrayInputStream(source);
        this.din = new DataInputStream(bin);
    }

    public boolean readBoolean() throws IOException {
        return din.readBoolean();
    }

    public byte readByte() throws IOException {
        return din.readByte();
    }

    public int readInt() throws IOException {
        return din.readInt();
    }

    public double readDouble() throws IOException {
        return din.readDouble();
    }

    public byte[] readArray() throws IOException {
        int len = readInt();
        if (len < 0)
            throw new IllegalStateException("negative array size! "+len);
        byte[] res = new byte[len];
        din.readFully(res);
        return res;
    }

    public byte[] read(int len) throws IOException {
        byte[] res = new byte[len];
        din.readFully(res);
        return res;
    }

    public String readString() throws IOException {
        return new String(readArray());
    }

    public void skip(int bytes) throws IOException {
        throw new IllegalStateException("Unimplemented!");
    }

    @Override
    public void readFully(byte[] b) throws IOException {
        din.readFully(b);
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        din.readFully(b, off, len);
    }

    @Override
    public int skipBytes(int n) throws IOException {
        return din.skipBytes(n);
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return din.readUnsignedByte();
    }

    @Override
    public short readShort() throws IOException {
        return din.readShort();
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return din.readUnsignedShort();
    }

    @Override
    public char readChar() throws IOException {
        return din.readChar();
    }

    @Override
    public long readLong() throws IOException {
        return din.readLong();
    }

    @Override
    public float readFloat() throws IOException {
        return din.readFloat();
    }

    @Override
    public String readLine() throws IOException {
        throw new IllegalStateException("Deprecated!");
    }

    @Override
    public String readUTF() throws IOException {
        return din.readUTF();
    }

    public int remaining() throws IOException {
        return din.available();
    }
}
