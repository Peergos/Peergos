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
        // names of custom groups need not be unique, and the built-in names are reserved for the built-in groups
        this.groupNameToUid = uidToGroupName.entrySet()
                .stream()
                .collect(Collectors.toMap(e -> e.getValue(), e -> e.getKey(), (a, b) -> a.compareTo(b) < 0 ? a : b));
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

    public Optional<String> getGroupUid(String name) {
        return Optional.ofNullable(groupNameToUid.get(name));
    }

    public Optional<String> getGroupName(String uid) {
        return Optional.ofNullable(uidToGroupName.get(uid));
    }

    public boolean isBuiltInGroup(String uid) {
        return uid.equals(getFriendsGroupUid()) || uid.equals(getFollowersGroupUid());
    }

    /** The built-in groups first, then custom groups ordered by name
     */
    public List<String> getGroupUids() {
        List<String> res = new ArrayList<>();
        if (getFriendsGroupUid() != null)
            res.add(getFriendsGroupUid());
        if (getFollowersGroupUid() != null)
            res.add(getFollowersGroupUid());
        uidToGroupName.entrySet().stream()
                .filter(e -> ! isBuiltInGroup(e.getKey()))
                .sorted(Comparator.comparing((Map.Entry<String, String> e) -> e.getValue()).thenComparing(e -> e.getKey()))
                .forEach(e -> res.add(e.getKey()));
        return res;
    }
}
