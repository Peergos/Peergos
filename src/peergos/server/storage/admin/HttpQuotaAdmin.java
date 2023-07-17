package peergos.server.storage.admin;

import peergos.shared.cbor.*;
import peergos.shared.crypto.hash.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.storage.*;
import peergos.shared.storage.controller.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class HttpQuotaAdmin implements QuotaAdmin {
    public static final String QUOTA_URL = "quota-admin/";

    public static final String SIGNUPS = "signups";
    public static final String USERNAMES = "usernames";
    public static final String ALLOWED = "allowed";
    public static final String CREATE_PAID = "create-paid";
    public static final String REMOVE_DESIRED = "remove-desired-quota";
    public static final String TOKEN_ADD = "token-add";
    public static final String TOKEN_REMOVE = "token-remove";
    public static final String QUOTA_PRIVATE = "quota-by-name";
    public static final String PAYMENT_PROPERTIES = "payment-properties";
    public static final String QUOTA_PUBLIC = "quota";
    public static final String REQUEST_QUOTA = "request";

    private final HttpPoster poster;

    public HttpQuotaAdmin(HttpPoster poster) {
        this.poster = poster;
    }

    @Override
    public AllowedSignups acceptingSignups() {
        return poster.get(QUOTA_URL + SIGNUPS)
                .thenApply(res -> AllowedSignups.fromCbor(CborObject.fromByteArray(res))).join();
    }

    @Override
    public boolean allowSignupOrUpdate(String username, String token) {
        return poster.get(QUOTA_URL + ALLOWED + "?username=" + username + "&token="+token)
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value).join();
    }

    @Override
    public PaymentProperties createPaidUser(String username) {
        return poster.get(QUOTA_URL + CREATE_PAID + "?username=" + username)
                .thenApply(res -> PaymentProperties.fromCbor(CborObject.fromByteArray(res))).join();
    }

    @Override
    public void removeDesiredQuota(String username) {
        poster.get(QUOTA_URL + REMOVE_DESIRED + "?username=" + username).join();
    }

    @Override
    public long getQuota(String username) {
        return poster.get(QUOTA_URL + QUOTA_PRIVATE + "?username=" + username)
                .thenApply(res -> ((CborObject.CborLong)CborObject.fromByteArray(res)).value).join();
    }

    @Override
    public boolean addToken(String token) {
        return poster.get(QUOTA_URL + TOKEN_ADD + "?token=" + token)
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value).join();
    }

    @Override
    public boolean consumeToken(String username, String token) {
        return poster.get(QUOTA_URL + TOKEN_REMOVE + "?username=" + username + "&token=" + token)
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value).join();
    }

    @Override
    public List<String> getLocalUsernames() {
        return poster.get(QUOTA_URL + USERNAMES)
                .thenApply(res -> ((CborObject.CborList)CborObject.fromByteArray(res)).value
                        .stream()
                        .map(x -> ((CborObject.CborString)x).value)
                        .collect(Collectors.toList()))
                .join();
    }

    @Override
    public CompletableFuture<PaymentProperties> getPaymentProperties(PublicKeyHash owner, boolean newClientSecret, byte[] signedTime) {
        return poster.get(QUOTA_URL + PAYMENT_PROPERTIES + "?owner="+owner
                + "&new-client-secret=" + newClientSecret
                + "&auth=" + ArrayOps.bytesToHex(signedTime))
                .thenApply(res -> PaymentProperties.fromCbor(CborObject.fromByteArray(res)));
    }

    @Override
    public CompletableFuture<Long> getQuota(PublicKeyHash owner, byte[] signedTime) {
        return poster.get(QUOTA_URL + QUOTA_PUBLIC + "?owner="+owner + "&auth=" + ArrayOps.bytesToHex(signedTime))
                .thenApply(res -> ((CborObject.CborLong)CborObject.fromByteArray(res)).value);
    }

    @Override
    public CompletableFuture<PaymentProperties> requestQuota(PublicKeyHash owner, byte[] signedRequest) {
        return poster.get(QUOTA_URL + REQUEST_QUOTA + "?owner="+owner + "&req=" + ArrayOps.bytesToHex(signedRequest))
                .thenApply(res -> PaymentProperties.fromCbor(CborObject.fromByteArray(res)));
    }

   @Override
    public List<LabelledSignedSpaceRequest> getSpaceRequests() {
        return Collections.emptyList();
    }

    @Override
    public void approveSpaceRequest(PublicKeyHash adminIdentity, Multihash instanceIdentity, byte[] signedRequest) {}

    @Override
    public void removeSpaceRequest(String username, byte[] unsigned) {}

}
