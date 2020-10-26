package peergos.shared.user.fs;

import peergos.shared.cbor.*;

import java.util.*;

public class SplitFragmenter implements Fragmenter {

    @Override
    public double storageIncreaseFactor() {
        return 1.0;
    }

    public byte[][] split(byte[] input) {
        //calculate padding length to align to 256 bytes
        int padding = 0;
        int mod = input.length % 256;
        if (mod != 0 || input.length == 0)
            padding = 256 - mod;
        //align to 256 bytes
        int len = input.length + padding;

        //calculate the number  of fragments
        int nFragments =  len / Fragment.MAX_LENGTH;
        if (len % Fragment.MAX_LENGTH > 0)
            nFragments++;

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
        Map<String, Cborable> res = new HashMap<>();
        res.put("t", new CborObject.CborLong(Fragmenter.Type.SIMPLE.val));
        return CborObject.CborMap.build(res);
    }

    @Override
    public byte[] recombine(byte[][] encoded, int startOffset, int truncateTo) {
        int length = 0;

        for (int i=0; i < encoded.length; i++)
            length += encoded[i].length;

        byte[] output = new byte[startOffset + length];
        int pos =  0;
        for (int i=0; i < encoded.length && pos < truncateTo; i++) {
            byte[] b = encoded[i];
            int copyLength = Math.max(0, Math.min(b.length, truncateTo - pos));
            System.arraycopy(b, 0, output, startOffset + pos, copyLength);
            pos += copyLength;
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
