package peergos.shared.storage;

import peergos.shared.cbor.*;
import peergos.shared.crypto.*;
import peergos.shared.crypto.hash.*;

import java.util.*;
import java.util.concurrent.*;

public interface SpaceUsage extends QuotaControl {

    CompletableFuture<Long> getUsage(PublicKeyHash owner);

}
