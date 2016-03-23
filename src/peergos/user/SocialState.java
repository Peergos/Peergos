package peergos.user;

import peergos.user.fs.*;

import java.util.*;

public class SocialState {
    List<FollowRequest> pending;
    Map<String, FileTreeNode> followerRoots;
    Set<FileTreeNode> followingRoots;

    public SocialState(List<FollowRequest> pending, Map<String, FileTreeNode> followerRoots, Set<FileTreeNode> followingRoots) {
        this.pending = pending;
        this.followerRoots = followerRoots;
        this.followingRoots = followingRoots;
    }
}
