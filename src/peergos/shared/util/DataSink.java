package peergos.shared.util;

import java.io.*;
import java.util.*;

public class DataSink implements DataOutput {

    private final ByteArrayOutputStream bout;
    private final DataOutputStream dout;

    public DataSink() {
        bout = new ByteArrayOutputStream();
        dout = new DataOutputStream(bout);
    }

    public void writeByte(byte b) {
        try {
            dout.writeByte(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeInt(int i) {
        try {
            dout.writeInt(i);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeDouble(double d) {
        try {
            dout.writeDouble(d);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeArray(byte[] a) {
        try {
            writeInt(a.length);
            dout.write(a);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(byte[] array, int start, int end) {
        try {
            dout.write(Arrays.copyOfRange(array, start, end));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeString(String s) {
        writeArray(s.getBytes());
    }

    public byte[] toByteArray() {
        try {
            dout.flush();
            return bout.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(int b) {
        try {
            dout.write(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void write(byte[] b) {
        try {
            dout.write(b);
            } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeBoolean(boolean b) {
        try {
            dout.writeBoolean(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeByte(int b) {
        try {
            dout.writeByte(b);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeShort(int s) throws IOException {
        dout.writeShort(s);
    }

    @Override
    public void writeChar(int c) throws IOException {
        dout.writeChar(c);
    }

    @Override
    public void writeLong(long v) {
        try {
            dout.writeLong(v);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void writeFloat(float v) throws IOException {
        dout.writeFloat(v);
    }

    @Override
    public void writeBytes(String s) throws IOException {
        dout.writeBytes(s);
    }

    @Override
    public void writeChars(String s) throws IOException {
        dout.writeChars(s);
    }

    @Override
    public void writeUTF(String s) throws IOException {
        dout.writeUTF(s);
    }
}
