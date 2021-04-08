package peergos.shared.messaging;

import java.util.*;
import java.util.concurrent.*;

public interface MessageStore {

    CompletableFuture<List<SignedMessage>> getMessagesFrom(long index);

    CompletableFuture<Boolean> addMessage(SignedMessage msg);
}
