package peergos.user.fs;

import peergos.crypto.UserPublicKey;
import peergos.util.ByteArrayWrapper;

public class Location
{
    public final String owner;
    public final UserPublicKey subKey;
    public final ByteArrayWrapper mapKey;

    public Location(String owner, UserPublicKey subKey, ByteArrayWrapper mapKey) {
        this.owner = owner;
        this. subKey = subKey;
        this.mapKey = mapKey;
    }
}
