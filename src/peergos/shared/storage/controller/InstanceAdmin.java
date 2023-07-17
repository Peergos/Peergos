package peergos.shared.storage.controller;

import jsinterop.annotations.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * This is how the administrator of a Peergos instance can control it including:
 *
 * 1) Upgrade the Peergos version
 * 2) Change local user quotas
 * 3) Change policy on allowing new user sign ups
 * 4) Grant/revoke admin rights to another user
 * 5) Add/remove ipfs instances to the storage cluster
 * 6) Change the erasure code/raid policy for data storage per user
 */
public interface InstanceAdmin {

    CompletableFuture<VersionInfo> getVersionInfo();

    CompletableFuture<List<QuotaControl.LabelledSignedSpaceRequest>> getPendingSpaceRequests(PublicKeyHash adminIdentity,
                                                                                             Multihash instanceIdentity,
                                                                                             byte[] signedTime);

    CompletableFuture<Boolean> approveSpaceRequest(PublicKeyHash adminIdentity,
                                                   Multihash instanceIdentity,
                                                   byte[] signedRequest);

    @JsMethod
    CompletableFuture<AllowedSignups> acceptingSignups();

    @JsMethod
    CompletableFuture<Boolean> addToWaitList(String email);

    class VersionInfo implements Cborable {
        public final Version version;
        public final String sourceVersion;

        public VersionInfo(Version version, String sourceVersion) {
            this.version = version;
            this.sourceVersion = sourceVersion;
        }

        @Override
        public CborObject toCbor() {
            Map<String, Cborable> props = new TreeMap<>();
            props.put("v", new CborObject.CborString(version.toString()));
            props.put("s", new CborObject.CborString(sourceVersion));
            return CborObject.CborMap.build(props);
        }

        public static VersionInfo fromCbor(Cborable cbor) {
            CborObject.CborMap map = (CborObject.CborMap) cbor;
            String version = map.getString("v");
            String sourceVersion = map.getString("s");
            return new VersionInfo(Version.parse(version), sourceVersion);
        }

        @Override
        public String toString() {
            return version + "-" + sourceVersion;
        }
    }
}