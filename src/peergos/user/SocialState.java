package peergos.user;

import peergos.user.fs.*;

import java.util.*;

public class SocialState {
    Set<FollowRequest> pending;
    Map<String, FileTreeNode> followerRoots;
    Set<FileTreeNode> followingRoots;

    public SocialState(Set<FollowRequest> pending, Map<String, FileTreeNode> followerRoots, Set<FileTreeNode> followingRoots) {
        this.pending = pending;
        this.followerRoots = followerRoots;
        this.followingRoots = followingRoots;
    }
}
