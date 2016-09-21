package peergos.shared.user.fs;

import peergos.shared.util.*;

public class SplitFragmenter implements Fragmenter {

    public byte[][] split(byte[] input) {

        int remainder  = input.length % Fragment.MAX_LENGTH;

        int extra =  remainder > 0 ? 1: 0;
        int nFragments = input.length / Fragment.MAX_LENGTH + extra;

        byte[][] split = new  byte[nFragments][];
        for(int i= 0; i< nFragments; ++i) {
            int start = Fragment.MAX_LENGTH * i;
            int end = Math.min(input.length, start + Fragment.MAX_LENGTH);
            int length = end - start;
            byte[] b = new byte[length];
            System.arraycopy(input, start, b, 0, length);
            split[i] = b;
        }
        return split;

    }

    public void serialize(DataSink dout) {
        dout.writeInt(peergos.shared.user.fs.Fragmenter.Type.SIMPLE.val);
    }

    public byte[] recombine(byte[][] encoded, int truncateTo) {
        int length = 0;
        for (int i=0; i < encoded.length; i++)
            length += encoded[i].length;

        if (length != truncateTo)
            throw new IllegalStateException();

        byte[] output = new byte[length];
        int pos =  0;
        for (int i=0; i < encoded.length; i++) {
            byte[] b = encoded[i];
            System.arraycopy(b, 0, output, pos, b.length);
            pos += b.length;
        }
        return output;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        return  ! (o == null || getClass() != o.getClass());
    }

    @Override
    public int hashCode() {
        return 0;
    }
}
