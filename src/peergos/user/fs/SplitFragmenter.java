package peergos.user.fs;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Random;


import static org.junit.Assert.*;

public class SplitFragmenter implements Fragmenter {

    public byte[][] split(byte[] input) {

        int remainder  = input.length % Chunk.MAX_SIZE;

        int extra =  remainder > 0 ? 1: 0;
        int nChunk = input.length / Chunk.MAX_SIZE + extra;

        byte[][] split = new  byte[nChunk][];
        for(int i= 0; i< nChunk; ++i) {
            int start = Chunk.MAX_SIZE * i;
            int end = Math.min(input.length, start + Chunk.MAX_SIZE);
            int length = end - start;
            if (length <  0)
                System.out.println();
            byte[] b = new byte[length]; 
            System.arraycopy(input, start, b, 0, length);
            split[i] = b;
        }
        return split;

    }

    public void serialize(DataOutput dout) throws IOException {
        dout.writeUTF(peergos.user.fs.Fragmenter.Type.SIMPLE.name());
    }

    public byte[] recombine(byte[][] encoded) {
         int length = 0;
         for (int i=0; i < encoded.length; i++) 
                 length += encoded[i].length;

         byte[] output = new byte[length];
         int pos =  0;
         for (int i=0; i < encoded.length; i++) {
                 byte[] b = encoded[i];
                 System.arraycopy(b, 0, output, pos, b.length);
                pos += b.length;
         }
         return output;
    }
}
