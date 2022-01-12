package peergos.shared.user.fs;

import peergos.shared.*;
import peergos.shared.storage.auth.*;

import java.util.*;

public class LocatedChunk {
    public final Location location;
    public final Optional<Bat> bat;
    public final MaybeMultihash existingHash;
    public final Chunk chunk;

    public LocatedChunk(Location location, Optional<Bat> bat, MaybeMultihash existingHash, Chunk chunk) {
        this.location = location;
        this.bat = bat;
        this.existingHash = existingHash;
        this.chunk = chunk;
    }
}
