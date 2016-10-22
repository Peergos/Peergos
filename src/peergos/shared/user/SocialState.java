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
        this.followerRoots = followerRoots;
        this.followingRoots = followingRoots;
    }
}
