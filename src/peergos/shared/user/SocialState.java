package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.*;

@JsType
public class SocialState {

    public final List<FollowRequest> pendingIncoming;
    public final Map<String, FileTreeNode> followerRoots;
    public final Set<FileTreeNode> followingRoots;
    public final Map<String, FileTreeNode> pendingOutgoingFollowRequests;

    public SocialState(List<FollowRequest> pendingIncoming,
                       Set<String> actualFollowers,
                       Map<String, FileTreeNode> followerRoots,
                       Set<FileTreeNode> followingRoots) {
        this.pendingIncoming = pendingIncoming;
        this.pendingOutgoingFollowRequests = followerRoots.entrySet()
                .stream()
                .filter(e -> ! actualFollowers.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        Map<String, FileTreeNode> actualFollowerRoots = followerRoots.entrySet()
                .stream()
                .filter(e -> actualFollowers.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        this.followerRoots = new TreeMap<>(actualFollowerRoots);
        TreeSet<FileTreeNode> sortedByName = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        sortedByName.addAll(followingRoots);
        this.followingRoots = sortedByName;
    }
}
