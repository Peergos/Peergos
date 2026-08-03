package peergos.server.tests;

import org.junit.*;
import peergos.shared.io.ipfs.Multihash;

import java.io.*;

public class VarintTests {

    @Test
    public void minimalEncoding() throws IOException {

        try {
            Multihash.readVarint(new ByteArrayInputStream(new byte[]{(byte) 0x81, 0x00}));
            throw new RuntimeException("Should throw for non minimal encoding");
        } catch (IllegalStateException e) {}
    }

    @Test
    public void truncated() throws IOException {
        try {
            // 0x81 promises a continuation byte that never arrives
            Multihash.readVarint(new ByteArrayInputStream(new byte[]{(byte) 0x81}));
            throw new RuntimeException("Should throw for a truncated varint");
        } catch (EOFException e) {}
    }

    @Test
    public void emptyStream() throws IOException {
        try {
            Multihash.readVarint(new ByteArrayInputStream(new byte[0]));
            throw new RuntimeException("Should throw for an empty stream");
        } catch (EOFException e) {}
    }
}
