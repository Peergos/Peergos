package peergos.shared.user;

import jsinterop.annotations.*;
import peergos.shared.social.*;
import peergos.shared.user.fs.*;

import java.util.*;
import java.util.stream.*;

@JsType
public class SocialState {

    public final List<FollowRequestWithCipherText> pendingIncoming;
    public final Map<String, FileWrapper> followerRoots;
    public final Set<FileWrapper> followingRoots;
    public final Map<String, FileWrapper> pendingOutgoingFollowRequests;

    public SocialState(List<FollowRequestWithCipherText> pendingIncoming,
                       Set<String> actualFollowers,
                       Map<String, FileWrapper> followerRoots,
                       Set<FileWrapper> followingRoots) {
        this.pendingIncoming = pendingIncoming;
        this.pendingOutgoingFollowRequests = followerRoots.entrySet()
                .stream()
                .filter(e -> ! actualFollowers.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        Map<String, FileWrapper> actualFollowerRoots = followerRoots.entrySet()
                .stream()
                .filter(e -> actualFollowers.contains(e.getKey()))
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        this.followerRoots = new TreeMap<>(actualFollowerRoots);
        TreeSet<FileWrapper> sortedByName = new TreeSet<>((a, b) -> a.getName().compareTo(b.getName()));
        sortedByName.addAll(followingRoots);
        this.followingRoots = sortedByName;
    }
}
