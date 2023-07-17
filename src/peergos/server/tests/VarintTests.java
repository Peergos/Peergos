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
}
