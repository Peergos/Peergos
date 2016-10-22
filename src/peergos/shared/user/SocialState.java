package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.user.fs.*;

import java.util.*;

@JsType
public class SocialState {

    public final List<FollowRequest> pending;
    public final Map<String, FileTreeNode> followerRoots;
    public final Set<FileTreeNode> followingRoots;

    public SocialState(List<FollowRequest> pending, Map<String, FileTreeNode> followerRoots, Set<FileTreeNode> followingRoots) {
        this.pending = pending;
        this.followerRoots = new TreeMap<>(followerRoots);
        TreeSet<FileTreeNode> sortedByName = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        sortedByName.addAll(followingRoots);
        this.followingRoots = sortedByName;
    }
}
