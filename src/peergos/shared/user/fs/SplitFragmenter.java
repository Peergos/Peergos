package peergos.shared.user.fs;

import peergos.shared.cbor.*;

import java.util.*;

public class SplitFragmenter implements Fragmenter {

    @Override
    public double storageIncreaseFactor() {
        return 1.0;
    }

    public byte[][] split(byte[] input) {

        //align length to 256

        int remainder = input.length % 256;
        int totalLength = input.length + 256 - remainder;
        int nFragments = totalLength / Fragment.MAX_LENGTH;

        if (totalLength % Fragment.MAX_LENGTH > 0)
            nFragments +=1;

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

    @Override
    public CborObject toCbor() {
        Map<String, CborObject> res = new HashMap<>();
        res.put("t", new CborObject.CborLong(Fragmenter.Type.SIMPLE.val));
        return CborObject.CborMap.build(res);
    }

    public byte[] recombine(byte[][] encoded, int truncateTo) {
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
