package peergos.server.net;

import com.sun.net.httpserver.*;
import peergos.server.*;
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

/** This is the http endpoint for getting and setting encrypted login blobs
 *
 */
public class AccountHandler implements HttpHandler {

    private final Account account;
    private final boolean isPublicServer;

    public AccountHandler(Account account, boolean isPublicServer) {
        this.account = account;
        this.isPublicServer = isPublicServer;
    }

    public void handle(HttpExchange exchange) throws IOException {
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
        try {
            if (! HttpUtil.allowedQuery(exchange, isPublicServer)) {
                exchange.sendResponseHeaders(405, 0);
                return;
            }

            switch (method) {
                case "setLogin":
                    AggregatedMetrics.LOGIN_SET.inc();
                    byte[] payload = Serialize.readFully(din, 1024);
                    boolean isAdded = account.setLoginData(LoginData.fromCbor(CborObject.fromByteArray(payload)), auth).join();
                    dout.writeBoolean(isAdded);
                    break;
                case "getLogin": {
                    AggregatedMetrics.LOGIN_GET.inc();
                    String username = params.get("username").get(0);
                    PublicSigningKey authorisedReader = PublicSigningKey.fromByteArray(ArrayOps.hexToBytes(params.get("author").get(0)));
                    Optional<MultiFactorAuthResponse> mfa = params.containsKey("mfa") ?
                            Optional.of(MultiFactorAuthResponse.fromCbor(CborObject.fromByteArray(ArrayOps.hexToBytes(params.get("mfa").get(0))))) :
                            Optional.empty();
                    Either<UserStaticData, MultiFactorAuthRequest> res = account.getLoginData(username, authorisedReader, auth, mfa, false).join();
                    byte[] resBytes = new LoginResponse(res).serialize();
                    dout.write(resBytes);
                    break;
                }
                case "listMfa": {
                    AggregatedMetrics.LOGIN_GET_MFA.inc();
                    String username = params.get("username").get(0);
                    List<MultiFactorAuthMethod> res = account.getSecondAuthMethods(username, auth).join();
                    dout.write(new CborObject.CborList(res).serialize());
                    break;
                }
                case "addTotp": {
                    AggregatedMetrics.LOGIN_ADD_TOTP.inc();
                    String username = params.get("username").get(0);
                    TotpKey res = account.addTotpFactor(username, auth).join();
                    dout.write(res.encode().getBytes(StandardCharsets.UTF_8));
                    break;
                }
                case "enableTotp": {
                    AggregatedMetrics.LOGIN_ENABLE_TOTP.inc();
                    String username = params.get("username").get(0);
                    byte[] credentialId = ArrayOps.hexToBytes(params.get("credid").get(0));
                    String code = params.get("code").get(0);
                    boolean res = account.enableTotpFactor(username, credentialId, code, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                case "registerWebauthnStart": {
                    AggregatedMetrics.LOGIN_WEBAUTHN_START.inc();
                    String username = params.get("username").get(0);
                    byte[] res = account.registerSecurityKeyStart(username, auth).join();
                    dout.write(res);
                    break;
                }
                case "registerWebauthnComplete": {
                    AggregatedMetrics.LOGIN_WEBAUTHN_COMPLETE.inc();
                    String username = params.get("username").get(0);
                    String keyName = params.get("keyname").get(0);
                    byte[] rawAttestation = Serialize.readFully(din, 2048);
                    MultiFactorAuthResponse keyResponse = MultiFactorAuthResponse.fromCbor(CborObject.fromByteArray(rawAttestation));
                    boolean res = account.registerSecurityKeyComplete(username, keyName, keyResponse, auth).join();
                    dout.write(new CborObject.CborBoolean(res).serialize());
                    break;
                }
                case "deleteMfa": {
                    AggregatedMetrics.LOGIN_DELETE_MFA.inc();
                    String username = params.get("username").get(0);
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
        }
    }
}
