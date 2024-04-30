package peergos.shared.user;

import jsinterop.annotations.JsMethod;
import peergos.shared.crypto.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.mfa.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

public interface Account {
    /** Auth signed by identity
     *
     * @param login
     * @param auth
     * @return
     */
    CompletableFuture<Boolean> setLoginData(LoginData login, byte[] auth);

    default CompletableFuture<Boolean> setLoginData(LoginData login, SigningPrivateKeyAndPublicHash identity) {
        byte[] auth = identity.secret.signatureOnly(login.serialize());
        return setLoginData(login, auth);
    }

    /** Auth signed by login keypair
     *
     * @param username
     * @param authorisedReader
     * @param auth
     * @param mfa
     * @return
     */
    CompletableFuture<Either<UserStaticData, MultiFactorAuthRequest>> getLoginData(String username,
                                                                                   PublicSigningKey authorisedReader,
                                                                                   byte[] auth,
                                                                                   Optional<MultiFactorAuthResponse>  mfa,
                                                                                   boolean cacheMfaLoginData);

    /** Auth signed by identity
     *
     * @param username
     * @param auth
     * @return
     */
    CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, byte[] auth);

    @JsMethod
    default CompletableFuture<List<MultiFactorAuthMethod>> getSecondAuthMethods(String username, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "listMfa", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return getSecondAuthMethods(username, auth);
    }

    CompletableFuture<Boolean> enableTotpFactor(String username, byte[] credentialId, String code, byte[] auth);

    @JsMethod
    default CompletableFuture<Boolean> enableTotpFactor(String username,
                                                        byte[] credentialId,
                                                        String code,
                                                        SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "enableTotp", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return enableTotpFactor(username, credentialId, code, auth);
    }

    CompletableFuture<TotpKey> addTotpFactor(String username, byte[] auth);

    @JsMethod
    default CompletableFuture<TotpKey> addTotpFactor(String username, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "addTotp", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return addTotpFactor(username, auth);
    }

    CompletableFuture<byte[]> registerSecurityKeyStart(String username, byte[] auth);

    @JsMethod
    default CompletableFuture<byte[]> registerSecurityKeyStart(String username, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "registerWebauthnStart", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return registerSecurityKeyStart(username, auth);
    }

    CompletableFuture<Boolean> registerSecurityKeyComplete(String username, String keyName, MultiFactorAuthResponse resp, byte[] auth);

    @JsMethod
    default CompletableFuture<Boolean> registerSecurityKeyComplete(String username,
                                                                   String keyName,
                                                                   MultiFactorAuthResponse resp,
                                                                   SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "registerWebauthnComplete", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return registerSecurityKeyComplete(username, keyName, resp, auth);
    }

    CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, byte[] auth);
    @JsMethod
    default CompletableFuture<Boolean> deleteSecondFactor(String username, byte[] credentialId, SigningPrivateKeyAndPublicHash identity) {
        TimeLimitedClient.SignedRequest req =
                new TimeLimitedClient.SignedRequest(Constants.LOGIN_URL + "deleteMfa", System.currentTimeMillis());
        byte[] auth = req.sign(identity.secret);
        return deleteSecondFactor(username, credentialId, auth);
    }

}
