package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.social.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.*;

@JsType
public class SocialState {
    public static final String FRIENDS_GROUP_NAME = "friends";
    public static final String FOLLOWERS_GROUP_NAME = "followers";

    public final List<FollowRequestWithCipherText> pendingIncoming;
    public final Set<String> pendingOutgoing;
    public final Map<String, FileWrapper> followerRoots;
    public final Set<FileWrapper> followingRoots;
    public final Set<String> blocked;
    public final Map<String, FriendAnnotation> friendAnnotations;
    public final Map<String, String> uidToGroupName, groupNameToUid;

    public SocialState(List<FollowRequestWithCipherText> pendingIncoming,
                       Set<String> pendingOutgoing,
                       Set<String> actualFollowers,
                       Map<String, FileWrapper> followerRoots,
                       Set<FileWrapper> followingRoots,
                       Set<String> blocked,
                       Map<String, FriendAnnotation> friendAnnotations,
                       Map<String, String> uidToGroupName) {
        this.pendingIncoming = pendingIncoming;
        this.pendingOutgoing = pendingOutgoing;
        Map<String, FileWrapper> actualFollowerRoots = followerRoots.entrySet()
                .stream()
                .filter(e -> actualFollowers.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        this.followerRoots = new TreeMap<>(actualFollowerRoots);
        TreeSet<FileWrapper> sortedByName = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        sortedByName.addAll(followingRoots);
        this.followingRoots = sortedByName;
        this.blocked = blocked;
        this.friendAnnotations = friendAnnotations;
        this.uidToGroupName = uidToGroupName;
        this.groupNameToUid = uidToGroupName.entrySet()
                .stream()
                .collect(Collectors.toMap(e -> e.getValue(), e -> e.getKey()));
    }

    public Set<String> getFollowers() {
        return followerRoots.keySet();
    }

    public Set<String> getFollowing() {
        return followingRoots.stream().map(f -> f.getFileProperties().name).collect(Collectors.toSet());
    }

    public Set<String> getFriends() {
        HashSet<String> res = new HashSet<>(getFollowing());
        res.retainAll(getFollowers());
        return res;
    }

    public String getFriendsGroupUid() {
        return groupNameToUid.get(FRIENDS_GROUP_NAME);
    }

    public String getFollowersGroupUid() {
        return groupNameToUid.get(FOLLOWERS_GROUP_NAME);
    }
}
