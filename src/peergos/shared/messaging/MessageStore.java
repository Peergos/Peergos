package peergos.shared.messaging;

import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public interface MessageStore {

    CompletableFuture<List<SignedMessage>> getMessagesFrom(long index);

    CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex);

    CompletableFuture<Snapshot> addMessage(Snapshot initialVersion, Committer committer, long msgIndex, SignedMessage msg);

    CompletableFuture<Snapshot> revokeAccess(Set<String> usernames);
}
