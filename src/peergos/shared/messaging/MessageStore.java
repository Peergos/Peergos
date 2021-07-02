package peergos.shared.messaging;

import java.util.*;
import java.util.concurrent.*;

public interface MessageStore {

    CompletableFuture<List<SignedMessage>> getMessagesFrom(long index);

    CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex);

    CompletableFuture<Boolean> addMessage(long msgIndex, SignedMessage msg);

    CompletableFuture<Boolean> revokeAccess(String username);
}
