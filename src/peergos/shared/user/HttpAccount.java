
package peergos.shared.user;

import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.io.ipfs.multihash.*;
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
    public CompletableFuture<Either<UserStaticData, List<MultiFactorAuthMethod>>> getLoginData(String username,
                                                                                               PublicSigningKey authorisedReader,
                                                                                               byte[] auth,
                                                                                               Optional<MultiFactorAuthResponse>  mfa) {
        return getLoginData(directUrlPrefix, direct, username, authorisedReader, auth,  mfa);
    }

    @Override
    public CompletableFuture<Either<UserStaticData, List<MultiFactorAuthMethod>>> getLoginData(Multihash targetServerId,
                                                                                               String username,
                                                                                               PublicSigningKey authorisedReader,
                                                                                               byte[] auth,
                                                                                               Optional<MultiFactorAuthResponse>  mfa) {
        return getLoginData(getProxyUrlPrefix(targetServerId), p2p, username, authorisedReader, auth, mfa);
    }

    private CompletableFuture<Either<UserStaticData, List<MultiFactorAuthMethod>>> getLoginData(String urlPrefix,
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
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> enableTotpFactor(String username, String uid, String code) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<Boolean> deleteSecondFactor(String username, String uid, byte[] auth) {
        throw new IllegalStateException("TODO");
    }

    @Override
    public CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth) {
        throw new IllegalStateException("TODO");
    }
}
