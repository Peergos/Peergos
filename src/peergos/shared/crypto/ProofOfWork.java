package peergos.shared.crypto;

import jsinterop.annotations.*;
import peergos.shared.io.ipfs.multihash.*;

public class ProofOfWork {
    public final static int PREFIX_BYTES = 8;

    public final byte[] prefix;
    public final byte[] data;
    public final Multihash.Type type;

    public ProofOfWork(byte[] prefix, byte[] data, Multihash.Type type) {
        this.prefix = prefix;
        this.data = data;
        this.type = type;
    }

    @JsMethod
    public static boolean satisfiesDifficulty(int difficulty, byte[] hash) {
        for (int i=0; i < difficulty; i+= 8) {
            if (i <= difficulty - 8) {
                if (hash[i/8] != 0)
                    return false;
            } else {
                int raw = hash[i / 8] & 0xFF;
                return (0xFF & (raw << (8 - difficulty + i))) == 0;
            }
        }
        return true;
    }
}
