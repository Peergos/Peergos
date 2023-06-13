package peergos.server.tests;

import com.sun.net.httpserver.*;
import com.webauthn4j.*;
import com.webauthn4j.authenticator.*;
import com.webauthn4j.authenticator.Authenticator;
import com.webauthn4j.converter.exception.*;
import com.webauthn4j.data.*;
import com.webauthn4j.data.client.*;
import com.webauthn4j.data.client.challenge.*;
import com.webauthn4j.server.*;
import com.webauthn4j.validator.exception.*;
import peergos.shared.io.ipfs.api.*;
import peergos.shared.util.*;

import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

public class StandaloneWebauthnDemo {

    public static void main(String[] args) throws Exception {
        // demo webauthn server and client
        // only the most recent user to register can login here
        // requires java 17 for Ed25519
        SecureRandom rnd = new SecureRandom();
        WebAuthnManager webAuthnManager = WebAuthnManager.createNonStrictWebAuthnManager();
        List<Authenticator> users = new ArrayList<>();
        List<byte[]> registerChallenges = new ArrayList<>();
        List<byte[]> loginChallenges = new ArrayList<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 9999), 10);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.createContext("/", httpExchange -> {
            String path = httpExchange.getRequestURI().getPath();
            byte[] html = ("<!DOCTYPE html><html><body>\n" +
                    "<h1>Webauthn test</h1>\n" +
                    "<button onclick=\"register()\">Register</button><label id='register'></label><br/>\n" +
                    "<button onclick=\"login()\">Login</button><label id='login'></label>\n" +
                    "<script type=\"text/javascript\" src=\"webauthn.js\"></script>\n" +
                    "</body></html>").getBytes();
            byte[] js = (
                    "function toHexString(byteArray) {\n" +
                            "   return Array.prototype.map.call(new Uint8Array(byteArray), x => ('00' + x.toString(16)).slice(-2)).join('')\n" +
                            "}\n" +
                            "function hexToBytes(hex) {\n" +
                            "   let res = new Uint8Array(hex.length/2);\n" +
                            "   for (var i=0; i < hex.length/2; i++)\n" +
                            "      res[i] = parseInt(hex.substring(2*i, 2*(i+1)), 16);\n" +
                            "   return res;\n" +
                            "}\n" +
                            "async function register() {\n" +
                            "   let init = await fetch(\"/registerStart\").then(response=>response.json());\n" +
                            "   let challenge = hexToBytes(init);\n" +
                            "   let username = 'peergosuser'\n" +
                            "   let enc = new TextEncoder()\n" +
                            "   let userId = new Uint8Array(username.length)\n" +
                            "   enc.encodeInto(username, userId);\n" +
                            "   let credential = await navigator.credentials.create({\n" +
                            "      publicKey: {\n" +
                            "         challenge: challenge,\n" +
                            "         rp: { name: \"Peergos\" },\n" +
                            "         user: {\n" +
                            "            id: userId,\n" +
                            "            name: username,\n" +
                            "            displayName: username,\n" +
                            "         },\n" +
                            "         timeout: 60000,\n" +
                            "         pubKeyCredParams: [ {type: \"public-key\", alg: -8}, {type: \"public-key\", alg: -7}, {type: \"public-key\", alg: -257}]\n" +
                            "   }\n" +
                            "});\n" +
                            "let res = await fetch(\"/registerComplete\", {'method':'POST','body':JSON.stringify({" +
                            "      'attestationObject':toHexString(credential.response.attestationObject),\n"+
                            "      'clientDataJSON': toHexString(credential.response.clientDataJSON)" +
                            "   })\n" +
                            "}).then(response=>response.json());\n" +
                            "document.getElementById(\"register\").textContent = res.status;\n" +
                            "}\n" +
                            "async function login() {\n" +
                            "   let init = await fetch(\"/loginStart\").then(response=>response.json());\n" +
                            "   let challenge = hexToBytes(init.challenge);\n" +
                            "   let id = hexToBytes(init.id);\n" +
                            "   let credential = await navigator.credentials.get({\n" +
                            "      publicKey: {\n" +
                            "         challenge: challenge,\n" +
                            "         allowCredentials: [{\n" +
                            "            type: \"public-key\",\n" +
                            "            id: id\n" +
                            "         }],\n" +
                            "         timeout: 60000,\n" +
                            "         userVerification: \"preferred\",\n" +
                            "      }\n" +
                            "   });\n" +
                            "   let res = await fetch(\"/loginComplete\", {'method':'POST','body':JSON.stringify({" +
                            "         'authenticatorData':toHexString(credential.response.authenticatorData),\n"+
                            "         'signature':toHexString(credential.response.signature),\n"+
                            "         'clientDataJSON': toHexString(credential.response.clientDataJSON)" +
                            "      })" +
                            "   }).then(response=>response.json());\n" +
                            "   document.getElementById(\"login\").textContent = res.status;\n" +
                            "}").getBytes();
            boolean isHTML = ! path.equals("/webauthn.js");
            byte[] res = isHTML ? html : js;
            if (isHTML)
                httpExchange.getResponseHeaders().set("Content-Type", "text/html");
            else
                httpExchange.getResponseHeaders().set("Content-Type", "text/javascript");
            httpExchange.sendResponseHeaders(200, res.length);
            httpExchange.getResponseBody().write(res);
            httpExchange.getResponseBody().close();
        });
        server.createContext("/registerStart", httpExchange -> {
            byte[] rawChallenge = new byte[32];
            rnd.nextBytes(rawChallenge);
            registerChallenges.add(rawChallenge);
            byte[] res = ("\""+ArrayOps.bytesToHex(rawChallenge)+"\"").getBytes();
            httpExchange.getResponseHeaders().set("Content-Type", "text/json");
            httpExchange.sendResponseHeaders(200, res.length);
            httpExchange.getResponseBody().write(res);
            httpExchange.getResponseBody().close();
        });
        server.createContext("/registerComplete", httpExchange -> {
            String req = new String(Serialize.readFully(httpExchange.getRequestBody()));
            Object json = JSONParser.parse(req);
            // Client properties
            byte[] attestationObject = ArrayOps.hexToBytes((String)((Map)json).get("attestationObject"));
            byte[] clientDataJSON = ArrayOps.hexToBytes((String)((Map)json).get("clientDataJSON"));
            String clientExtensionJSON = null;  /* set clientExtensionJSON */
            Set<String> transports = null /* set transports */;
            try {
                Authenticator registered = register(webAuthnManager, registerChallenges.get(registerChallenges.size() - 1),
                        attestationObject, clientDataJSON, clientExtensionJSON, transports);
                users.add(registered);
                byte[] res = "{\"status\":\"success\"}".getBytes();
                httpExchange.getResponseHeaders().set("Content-Type", "text/json");
                httpExchange.sendResponseHeaders(200, res.length);
                httpExchange.getResponseBody().write(res);
                httpExchange.getResponseBody().close();
            } catch (Throwable e) {
                e.printStackTrace();
                byte[] res = "{\"status\":\"error\"}".getBytes();
                httpExchange.getResponseHeaders().set("Content-Type", "text/json");
                httpExchange.sendResponseHeaders(200, res.length);
                httpExchange.getResponseBody().write(res);
                httpExchange.getResponseBody().close();
            }
        });
        server.createContext("/loginStart", httpExchange -> {
            byte[] rawChallenge = new byte[32];
            rnd.nextBytes(rawChallenge);
            loginChallenges.add(rawChallenge);
            Authenticator user = users.get(users.size() - 1);
            byte[] res = ("{\"challenge\":\""+ArrayOps.bytesToHex(rawChallenge)+"\"," +
                    "\"id\":\""+ArrayOps.bytesToHex(user.getAttestedCredentialData().getCredentialId())+"\"}").getBytes();
            httpExchange.getResponseHeaders().set("Content-Type", "text/json");
            httpExchange.sendResponseHeaders(200, res.length);
            httpExchange.getResponseBody().write(res);
            httpExchange.getResponseBody().close();
        });
        server.createContext("/loginComplete", httpExchange -> {
            String req = new String(Serialize.readFully(httpExchange.getRequestBody()));
            Object json = JSONParser.parse(req);
            byte[] authenticatorData = ArrayOps.hexToBytes((String)((Map)json).get("authenticatorData"));
            byte[] clientDataJSON = ArrayOps.hexToBytes((String)((Map)json).get("clientDataJSON"));
            byte[] signature = ArrayOps.hexToBytes((String)((Map)json).get("signature"));
            Authenticator user = users.get(users.size() - 1);
            try {
                login(webAuthnManager, loginChallenges.get(loginChallenges.size() - 1),
                        user.getAttestedCredentialData().getCredentialId(), "peergosuser".getBytes(), user,
                        authenticatorData, clientDataJSON, signature);
                byte[] res = "{\"status\":\"success\"}".getBytes();
                httpExchange.getResponseHeaders().set("Content-Type", "text/json");
                httpExchange.sendResponseHeaders(200, res.length);
                httpExchange.getResponseBody().write(res);
                httpExchange.getResponseBody().close();
            } catch (Throwable e) {
                e.printStackTrace();
                byte[] res = "{\"status\":\"error\"}".getBytes();
                httpExchange.getResponseHeaders().set("Content-Type", "text/json");
                httpExchange.sendResponseHeaders(200, res.length);
                httpExchange.getResponseBody().write(res);
                httpExchange.getResponseBody().close();
            }
        });
        server.start();
    }

    private static void login(WebAuthnManager webAuthnManager,
                              byte[] rawChallenge,
                              byte[] credentialId,
                              byte[] userHandle,
                              Authenticator user,
                              byte[] authenticatorData,
                              byte[] clientDataJSON,
                              byte[] signature) {
        // Client properties
        String clientExtensionJSON = null /* set clientExtensionJSON */;

        // Server properties
        Origin origin = new Origin("http://localhost:9999");
        String rpId = "localhost";
        Challenge challenge = () -> rawChallenge;
        byte[] tokenBindingId = null /* set tokenBindingId */;
        ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);

        // expectations
        List<byte[]> allowCredentials = null;
        boolean userVerificationRequired = false;
        boolean userPresenceRequired = true;

        AuthenticationRequest authenticationRequest =
                new AuthenticationRequest(
                        credentialId,
                        userHandle,
                        authenticatorData,
                        clientDataJSON,
                        clientExtensionJSON,
                        signature
                );
        AuthenticationParameters authenticationParameters =
                new AuthenticationParameters(
                        serverProperty,
                        user,
                        allowCredentials,
                        userVerificationRequired,
                        userPresenceRequired
                );

        AuthenticationData authenticationData;
        try {
            authenticationData = webAuthnManager.parse(authenticationRequest);
        } catch (DataConversionException e) {
            // If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
            throw e;
        }
        try {
            webAuthnManager.validate(authenticationData, authenticationParameters);
        } catch (ValidationException e) {
            // If you would like to handle WebAuthn data validation error, please catch ValidationException
            throw e;
        }
        // please update the counter of the authenticator record
//        updateCounter(
//                authenticationData.getCredentialId(),
//                authenticationData.getAuthenticatorData().getSignCount()
//        );
    }
    private static Authenticator register(WebAuthnManager webAuthnManager,
                                          byte[] rawChallenge,
                                          byte[] attestationObject,
                                          byte[] clientDataJSON,
                                          String clientExtensionJSON,
                                          Set<String> transports) {
        // Server properties
        Origin origin = new Origin("http://localhost:9999");
        String rpId = "localhost";

        Challenge challenge = () -> rawChallenge;
        byte[] tokenBindingId = null /* set tokenBindingId */;
        ServerProperty serverProperty = new ServerProperty(origin, rpId, challenge, tokenBindingId);

        // expectations
        boolean userVerificationRequired = false;
        boolean userPresenceRequired = true;

        RegistrationRequest registrationRequest = new RegistrationRequest(attestationObject, clientDataJSON, clientExtensionJSON, transports);
        RegistrationParameters registrationParameters = new RegistrationParameters(serverProperty, userVerificationRequired, userPresenceRequired);
        RegistrationData registrationData;
        try {
            registrationData = webAuthnManager.parse(registrationRequest);
        } catch (DataConversionException e) {
            // If you would like to handle WebAuthn data structure parse error, please catch DataConversionException
            throw e;
        }
        try {
            webAuthnManager.validate(registrationData, registrationParameters);
        } catch (ValidationException e) {
            // If you would like to handle WebAuthn data validation error, please catch ValidationException
            throw e;
        }

        // please persist Authenticator object, which will be used in the authentication process.
        return new AuthenticatorImpl(
                registrationData.getAttestationObject().getAuthenticatorData().getAttestedCredentialData(),
                registrationData.getAttestationObject().getAttestationStatement(),
                registrationData.getAttestationObject().getAuthenticatorData().getSignCount()
        );
    }
}
