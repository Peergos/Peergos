package peergos.shared.messaging;

import peergos.shared.display.*;

import java.util.*;

public class ChatUpdate {
    public final Chat state;
    public final List<SignedMessage> newMessages;
    public final List<FileRef> mediaToCopy;
    public final Set<String> toRevokeAccess;
    public final Optional<PrivateChatState> priv;

    public ChatUpdate(Chat state,
                      List<SignedMessage> newMessages,
                      List<FileRef> mediaToCopy,
                      Set<String> toRevokeAccess,
                      Optional<PrivateChatState> priv) {
        this.state = state;
        this.newMessages = newMessages;
        this.mediaToCopy = mediaToCopy;
        this.toRevokeAccess = toRevokeAccess;
        this.priv = priv;
    }

    public boolean isEmpty() {
        return newMessages.isEmpty() && mediaToCopy.isEmpty() && toRevokeAccess.isEmpty() && priv.isEmpty();
    }

    public ChatUpdate(Chat state,
                      List<SignedMessage> newMessages,
                      List<FileRef> mediaToCopy,
                      Set<String> toRevokeAccess) {
        this(state, newMessages, mediaToCopy, toRevokeAccess, Optional.empty());
    }

    public ChatUpdate apply(ChatUpdate next) {
        ArrayList<SignedMessage> msgs = new ArrayList<>(newMessages);
        msgs.addAll(next.newMessages);
        ArrayList<FileRef> refs = new ArrayList<>(mediaToCopy);
        refs.addAll(next.mediaToCopy);
        Set<String> toRevoke = new HashSet(toRevokeAccess);
        toRevoke.addAll(next.toRevokeAccess);
        return new ChatUpdate(next.state, msgs, refs, toRevoke, priv.flatMap(a -> next.priv.map(a::apply)));
    }

    public ChatUpdate withState(Chat c) {
        return new ChatUpdate(c, newMessages, mediaToCopy, toRevokeAccess, priv);
    }

    public static ChatUpdate empty(Chat c) {
        return new ChatUpdate(c, Collections.emptyList(), Collections.emptyList(), Collections.emptySet(), Optional.empty());
    }
}
