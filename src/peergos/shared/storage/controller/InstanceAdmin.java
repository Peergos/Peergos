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
 */
public interface InstanceAdmin {

    CompletableFuture<VersionInfo> getVersionInfo();

    CompletableFuture<List<QuotaControl.LabelledSignedSpaceRequest>> getPendingSpaceRequests(PublicKeyHash adminIdentity,
                                                                                             Multihash instanceIdentity,
                                                                                             byte[] signedTime);

    CompletableFuture<Boolean> approveSpaceRequest(PublicKeyHash adminIdentity,
                                                   Multihash instanceIdentity,
                                                   byte[] signedRequest);

    /** Whether this identity is an admin here. Only its owner may ask, so who the admins are stays
     *  hidden, as it does from getPendingSpaceRequests, which gives anyone else an empty list.
     *
     *  @param signedRequest a TimeLimitedClient.SignedRequest for Constants.ADMIN_URL + "isadmin" */
    CompletableFuture<Boolean> isAdmin(PublicKeyHash identity, byte[] signedRequest);

    /** Single use signup tokens for an admin to hand out: each lets one new user sign up, even
     *  when the instance is not otherwise accepting signups.
     *
     *  @param signedRequest a TimeLimitedClient.SignedRequest for Constants.ADMIN_URL + "tokens" */
    CompletableFuture<List<String>> createSignupTokens(PublicKeyHash adminIdentity,
                                                       Multihash instanceIdentity,
                                                       byte[] signedRequest,
                                                       int count);

    /** The signup tokens not yet used, which are as good as accounts, so each request is spent once.
     *
     *  @param signedRequest a TimeLimitedClient.SignedRequest for Constants.ADMIN_URL + "listtokens" */
    CompletableFuture<List<String>> listSignupTokens(PublicKeyHash adminIdentity, byte[] signedRequest);

    /** Withdraws a signup token nobody has used yet.
     *
     *  @param signedRequest a TimeLimitedClient.SignedRequest for Constants.ADMIN_URL + "revoketoken/" + token */
    CompletableFuture<Boolean> revokeSignupToken(PublicKeyHash adminIdentity, String token, byte[] signedRequest);

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
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;
            VersionInfo that = (VersionInfo) o;
            return Objects.equals(version, that.version) && Objects.equals(sourceVersion, that.sourceVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(version, sourceVersion);
        }

        @Override
        public String toString() {
            return version + "-" + sourceVersion;
        }
    }
}