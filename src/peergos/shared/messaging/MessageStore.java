package peergos.shared.messaging;

import peergos.shared.user.*;

import java.util.*;
import java.util.concurrent.*;

public interface MessageStore {

    CompletableFuture<List<SignedMessage>> getMessagesFrom(long index);

    CompletableFuture<List<SignedMessage>> getMessages(long fromIndex, long toIndex);

    CompletableFuture<Snapshot> addMessages(Snapshot initialVersion, Committer committer, long msgIndex, List<SignedMessage> msgs);

    CompletableFuture<Snapshot> revokeAccess(Set<String> usernames, Snapshot initialVersion, Committer committer);
}
