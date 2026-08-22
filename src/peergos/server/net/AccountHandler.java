package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
import peergos.server.login.*;
import peergos.server.util.*;
import peergos.shared.cbor.*;
import peergos.shared.crypto.asymmetric.*;
import peergos.shared.login.*;
import peergos.shared.login.mfa.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.logging.Logger;

/** This is the http endpoint for getting and setting encrypted login blobs
 *
 */
public class AccountHandler implements HttpHandler {
    private static final Logger LOG = Logging.LOG();
    private final Account account;
    private final boolean isPublicServer;

    public AccountHandler(Account account, boolean isPublicServer) {
        this.account = account;
        this.isPublicServer = isPublicServer;
    }

    /** A client only sends the second factor types it knows about. Anything else is from before
     *  those types existed, and would fail to parse a method it doesn't recognise, so only offer
     *  it the original two.
     *
     *  Non interactive factors are never offered to anybody: a mount answers its own challenge
     *  from the credential it holds, and nothing else can. Leaving them out keeps them from
     *  showing up as a login option a person would have no way to satisfy.
     */
    private static Either<UserStaticData, MultiFactorAuthRequest> filterToSupportedMfaTypes(Either<UserStaticData, MultiFactorAuthRequest> res,
                                                                                            Map<String, List<String>> params) {
        if (res.isA())
            return res;
        Set<Integer> supported = new HashSet<>();
        if (params.containsKey("mfaTypes")) {
            for (String type : params.get("mfaTypes").get(0).split(","))
                supported.add(Integer.parseInt(type));
        } else {
            supported.add(MultiFactorAuthMethod.Type.TOTP.value);
            supported.add(MultiFactorAuthMethod.Type.WEBAUTHN.value);
        }
        MultiFactorAuthRequest req = res.b();
        List<MultiFactorAuthMethod> filtered = new ArrayList<>();
        for (MultiFactorAuthMethod method : req.methods) {
            if (method.type.interactive && supported.contains(method.type.value))
                filtered.add(method);
        }
        if (filtered.size() == req.methods.size())
            return res;
        return Either.b(new MultiFactorAuthRequest(filtered, req.challenge));
    }

    public void handle(HttpExchange exchange) throws IOException {
        long t1 = System.currentTimeMillis();
        DataInputStream din = new DataInputStream(exchange.getRequestBody());

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);

        String path = exchange.getRequestURI().getPath();
        if (path.startsWith("/"))
            path = path.substring(1);
        String[] subComponents = path.substring(Constants.LOGIN_URL.length()).split("/");
        String method = subComponents[0];

        Map<String, List<String>> params = HttpUtil.parseQuery(exchange.getRequestURI().getQuery());
        byte[] auth = ArrayOps.hexToBytes(params.get("auth").get(0));
        String username = "";
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case "setLogin":
                    AggregatedMetrics.LOGIN_SET.inc();
                    byte[] payload = Serialize.readFully(din, 16384);
                    boolean forceLocal = params.containsKey("local") ? Boolean.parseBoolean(params.get("local").get(0)) : false;
                    boolean isAdded = account.setLoginData(LoginData.fromCbor(CborObject.fromByteArray(payload)), auth, forceLocal).join();
                    dout.writeBoolean(isAdded);
                    break;
                case "getLogin": {
                    try {
                        username = params.get("username").get(0);
                        PublicSigningKey authorisedReader = PublicSigningKey.fromByteArray(ArrayOps.hexToBytes(params.get("author").get(0)));
                        Optional<MultiFactorAuthResponse> mfa = params.containsKey("mfa") ?
                                Optional.of(MultiFactorAuthResponse.fromCbor(CborObject.fromByteArray(ArrayOps.hexToBytes(params.get("mfa").get(0))))) :
                                Optional.empty();
                        boolean forceProxy = params.containsKey("proxy") ? Boolean.parseBoolean(params.get("proxy").get(0)) : false;
                        Either<UserStaticData, MultiFactorAuthRequest> res = account.getLoginData(username, authorisedReader, auth, mfa, false, forceProxy, false).join();
                        res = filterToSupportedMfaTypes(res, params);
                        AggregatedMetrics.LOGIN_GET.inc();
                        byte[] resBytes = new LoginResponse(res).serialize();
                        dout.write(resBytes);
                        byte[] b = bout.toByteArray();
                        exchange.sendResponseHeaders(200, b.length);
                        exchange.getResponseBody().write(b);
                    } catch (Exception e) {
                        e.printStackTrace();
                        String msg = e.getMessage();
                        if (msg != null && msg.contains("Incorrect password")) {
                            AggregatedMetrics.LOGIN_GET_FAILURE_PASSWORD.inc();
                        } else if (msg != null && msg.equals(LocalOnlyAccount.EXTERNAL_ERROR)) {
                            AggregatedMetrics.LOGIN_GET_FAILURE_EXTERNAL.inc();
                        } else if (msg != null && msg.equals(LocalOnlyAccount.EXPIRED_ERROR)) {
                            AggregatedMetrics.LOGIN_GET_FAILURE_EXPIRED.inc();
                        }
                        HttpUtil.replyError(exchange, e);
                    }
                    return;
                }
                case "listMfa": {
                    AggregatedMetrics.LOGIN_GET_MFA.inc();
                    username = params.get("username").get(0);
                    List<MultiFactorAuthMethod> res = account.getSecondAuthMethods(username, auth).join();
                    dout.write(new CborObject.CborList(res).serialize());
                    break;
                }
                case "addTotp": {
                    AggregatedMetrics.LOGIN_ADD_TOTP.inc();
                    username = params.get("username").get(0);
                    TotpKey res = account.addTotpFactor(username, auth).join();
                    dout.write(res.encode().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "enableTotp": {
                    AggregatedMetrics.LOGIN_ENABLE_TOTP.inc();
                    username = params.get("username").get(0);
                    byte[] credentialId = ArrayOps.hexToBytes(params.get("credid").get(0));
                    String code = params.get("code").get(0);
                    boolean res = account.enableTotpFactor(username, credentialId, code, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                case "addMount": {
                    AggregatedMetrics.LOGIN_ADD_MOUNT.inc();
                    username = params.get("username").get(0);
                    String name = params.get("name").get(0);
                    TotpKey res = account.addMountFactor(username, name, auth).join();
                    dout.write(res.encode().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "enableMount": {
                    AggregatedMetrics.LOGIN_ENABLE_MOUNT.inc();
                    username = params.get("username").get(0);
                    byte[] credentialId = ArrayOps.hexToBytes(params.get("credid").get(0));
                    String code = params.get("code").get(0);
                    boolean res = account.enableMountFactor(username, credentialId, code, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                case "genBackupCodes": {
                    AggregatedMetrics.LOGIN_GEN_BACKUP_CODES.inc();
                    username = params.get("username").get(0);
                    BackupCodes res = account.generateBackupCodes(username, auth).join();
                    dout.write(res.serialize());
                    break;
                }
                case "registerWebauthnStart": {
                    AggregatedMetrics.LOGIN_WEBAUTHN_START.inc();
                    username = params.get("username").get(0);
                    byte[] res = account.registerSecurityKeyStart(username, auth).join();
                    dout.write(res);
                    break;
                }
                case "registerWebauthnComplete": {
                    AggregatedMetrics.LOGIN_WEBAUTHN_COMPLETE.inc();
                    username = params.get("username").get(0);
                    String keyName = params.get("keyname").get(0);
                    byte[] rawAttestation = Serialize.readFully(din, 2048);
                    MultiFactorAuthResponse keyResponse = MultiFactorAuthResponse.fromCbor(CborObject.fromByteArray(rawAttestation));
                    boolean res = account.registerSecurityKeyComplete(username, keyName, keyResponse, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                case "deleteMfa": {
                    AggregatedMetrics.LOGIN_DELETE_MFA.inc();
                    username = params.get("username").get(0);
                    byte[] credentialId = ArrayOps.hexToBytes(params.get("credid").get(0));
                    boolean res = account.deleteSecondFactor(username, credentialId, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                default:
                    throw new IOException("Unknown method in AccountHandler!");
            }

            byte[] b = bout.toByteArray();
            exchange.sendResponseHeaders(200, b.length);
            exchange.getResponseBody().write(b);
        } catch (Exception e) {
            e.printStackTrace();
            HttpUtil.replyError(exchange, e);
        } finally {
            exchange.close();
            long t2 = System.currentTimeMillis();
            LOG.info("AccountsHandler handled " + method + " request in: " + (t2 - t1) + " mS " + username);
        }
    }
}
