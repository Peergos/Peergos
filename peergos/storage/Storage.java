package peergos.storage;

import peergos.crypto.*;
import peergos.util.*;

public interface Storage
{

    boolean isWaitingFor(byte[] key);

    boolean accept(ByteArrayWrapper fragmentHash, int size, UserPublicKey owner, byte[] sharingKey, byte[] mapKey, byte[] proof);

    boolean put(ByteArrayWrapper key, byte[] value);

    byte[] get(ByteArrayWrapper key);

    boolean contains(ByteArrayWrapper key);

    int sizeOf(ByteArrayWrapper key);
}
