package peergos.shared.storage.controller;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.multihash.*;
import peergos.shared.storage.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpInstanceAdmin implements InstanceAdmin {
    public static final String VERSION = "version";
    public static final String PENDING = "pending";
    public static final String APPROVE = "approve";

    private final HttpPoster poster;

    public HttpInstanceAdmin(HttpPoster poster) {
        this.poster = poster;
    }

    @Override
    public CompletableFuture<VersionInfo> getVersionInfo() {
        return poster.get(Constants.ADMIN_URL + VERSION)
                .thenApply(raw -> VersionInfo.fromCbor(CborObject.fromByteArray(raw)));
    }

    @Override
    public CompletableFuture<List<SpaceUsage.LabelledSignedSpaceRequest>> getPendingSpaceRequests(
            PublicKeyHash adminIdentity,
            Multihash instanceIdentity,
            byte[] signedTime) {
        return poster.get(Constants.ADMIN_URL + PENDING
                + "?admin=" + encode(adminIdentity.toString())
                + "&instance=" + encode(instanceIdentity.toString())
                + "&auth=" + ArrayOps.bytesToHex(signedTime))
                .thenApply(raw -> ((CborObject.CborList)CborObject.fromByteArray(raw))
                        .map(SpaceUsage.LabelledSignedSpaceRequest::fromCbor));
    }

    @Override
    public CompletableFuture<Boolean> approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest) {
        return poster.get(Constants.ADMIN_URL + APPROVE
                + "?admin=" + encode(adminIdentity.toString())
                + "&instance=" + encode(instanceIdentity.toString())
                + "&req=" + ArrayOps.bytesToHex(signedRequest))
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
    }

    private static String encode(String component) {
        try {
            return URLEncoder.encode(component, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }
}
