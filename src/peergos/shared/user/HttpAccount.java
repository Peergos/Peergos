
package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.Multihash;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class HttpAccount implements AccountProxy {
    private static final String P2P_PROXY_PROTOCOL = "/http";

    private final HttpPoster direct, p2p;
    private final String directUrlPrefix;

    public HttpAccount(HttpPoster direct, HttpPoster p2p) {
        this.direct = direct;
        this.p2p = p2p;
        this.directUrlPrefix = "";
    }

    public HttpAccount(HttpPoster p2p, Multihash targetNodeID) {
        this.directUrlPrefix = getProxyUrlPrefix(targetNodeID);
        this.direct = p2p;
        this.p2p = p2p;
    }

    private static String getProxyUrlPrefix(Multihash targetId) {
        return "/p2p/" + targetId.toString() + P2P_PROXY_PROTOCOL + "/";
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth) {
        return setLoginData(directUrlPrefix, direct, login, auth);
    }

    @Override
    public CompletableFuture<Boolean> setLoginData(Multihash targetServerId, LoginData login, byte[] auth) {
        return setLoginData(getProxyUrlPrefix(targetServerId), p2p, login, auth);
    }

    private CompletableFuture<Boolean> setLoginData(String urlPrefix,
                                                    HttpPoster poster,
                                                    LoginData login,
                                                    byte[] auth) {
        return poster.postUnzip(urlPrefix + Constants.LOGIN_URL + "setLogin?username=" + login.username + "&auth=" + ArrayOps.bytesToHex(auth), login.serialize()).thenApply(res -> {
            DataInputStream din = new DataInputStream(new ByteArrayInputStream(res));
            try {
                return din.readBoolean();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa,
                                                                                          boolean cacheMfaLoginData) {
        return getLoginData(directUrlPrefix, direct, username, authorisedReader, auth,  mfa);
    }

    @Override
    public CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(Multihash targetServerId,
                                                                                          String username,
                                                                                          PublicSigningKey authorisedReader,
                                                                                          byte[] auth,
                                                                                          Optional<MultiFactorAuthResponse>  mfa) {
        return getLoginData(getProxyUrlPrefix(targetServerId), p2p, username, authorisedReader, auth, mfa);
    }

    private CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String urlPrefix,
                                                                                           HttpPoster poster,
                                                                                           String username,
                                                                                           PublicSigningKey authorisedReader,
                                                                                           byte[] auth,
                                                                                           Optional<MultiFactorAuthResponse>  mfa) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "getLogin?username=" + username
                        + "&author=" + ArrayOps.bytesToHex(authorisedReader.serialize())
                        + "&auth=" + ArrayOps.bytesToHex(auth)
                        + mfa.map(mfaCode -> "&mfa=" + ArrayOps.bytesToHex(mfaCode.serialize())).orElse(""))
                .thenApply(res -> LoginResponse.fromCbor(CborObject.fromByteArray(res)).resp);
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth) {
        return getSecondAuthMethods(directUrlPrefix, direct, username, auth);
    }

    @Override
    public CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(Multihash targetServerId, String username, byte[] auth) {
        return getSecondAuthMethods(getProxyUrlPrefix(targetServerId), p2p, username, auth);
    }

    private CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String urlPrefix,
                                                                                HttpPoster poster,
                                                                                String username,
                                                                                byte[] auth) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "listMfa?username=" + username
                        + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res -> ((CborObject.CborList)CborObject.fromByteArray(res)).map(MultiFactorAuthMethod::fromCbor));
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        return addTotpFactor(directUrlPrefix, direct, username, auth);
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(Multihash targetServerId, String username, byte[] auth) {
        return addTotpFactor(getProxyUrlPrefix(targetServerId), p2p, username, auth);
    }

    private CompletableFuture<TotpKey> addTotpFactor(String urlPrefix, HttpPoster poster, String username, byte[] auth) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "addTotp?username=" + username
                        + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res -> TotpKey.fromString(new String(res)));
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth) {
        return registerSecurityKeyStart(directUrlPrefix, direct, username, auth);
    }

    @Override
    public CompletableFuture<byte[]> registerSecurityKeyStart(Multihash targetServerId, String username, byte[] auth) {
        return registerSecurityKeyStart(getProxyUrlPrefix(targetServerId), p2p, username, auth);
    }

    private CompletableFuture<byte[]> registerSecurityKeyStart(String urlPrefix, HttpPoster poster, String username, byte[] auth) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "registerWebauthnStart?username=" + username
                + "&auth=" + ArrayOps.bytesToHex(auth));
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return registerSecurityKeyComplete(directUrlPrefix, direct, username, keyName, resp, auth);
    }

    @Override
    public CompletableFuture<Boolean> registerSecurityKeyComplete(Multihash targetServerId, String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return registerSecurityKeyComplete(getProxyUrlPrefix(targetServerId), p2p, username, keyName, resp, auth);
    }

    private CompletableFuture<Boolean> registerSecurityKeyComplete(String urlPrefix, HttpPoster poster, String username, String keyName, MultiFactorAuthResponse resp, byte[] auth) {
        return poster.post(urlPrefix + Constants.LOGIN_URL + "registerWebauthnComplete?username=" + username
                        + "&keyname=" + keyName
                        + "&auth=" + ArrayOps.bytesToHex(auth), resp.serialize(), true)
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth) {
        return enableTotpFactor(directUrlPrefix, direct, username, credentialId, code, auth);
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(Multihash targetServerId, String username, byte[] credentialId, String code, byte[] auth) {
        return enableTotpFactor(getProxyUrlPrefix(targetServerId), p2p, username, credentialId, code, auth);
    }

    private CompletableFuture<Boolean> enableTotpFactor(String urlPrefix,
                                                        HttpPoster poster,
                                                        String username,
                                                        byte[] credentialId,
                                                        String code,
                                                        byte[] auth) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "enableTotp?username=" + username
                        + "&credid=" + ArrayOps.bytesToHex(credentialId)
                        + "&auth=" + ArrayOps.bytesToHex(auth)
                        + "&code=" + code)
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth) {
        return deleteSecondFactor(directUrlPrefix, direct, username, credentialId, auth);
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(Multihash targetServerId, String username, byte[] credentialId, byte[] auth) {
        return deleteSecondFactor(getProxyUrlPrefix(targetServerId), p2p, username, credentialId, auth);
    }

    private CompletableFuture<Boolean> deleteSecondFactor(String urlPrefix,
                                                          HttpPoster poster,
                                                          String username,
                                                          byte[] credentialId,
                                                          byte[] auth) {
        return poster.get(urlPrefix + Constants.LOGIN_URL + "deleteMfa?username=" + username
                        + "&credid=" + ArrayOps.bytesToHex(credentialId)
                        + "&auth=" + ArrayOps.bytesToHex(auth))
                .thenApply(res -> ((CborObject.CborBoolean)CborObject.fromByteArray(res)).value);
    }
}
