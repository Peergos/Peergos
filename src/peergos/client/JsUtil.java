package peergos.client;

import jsinterop.annotations.*;
import peergos.shared.login.mfa.MultiFactorAuthResponse;
import peergos.shared.login.mfa.WebauthnResponse;
import peergos.shared.util.Either;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/** Utility methods to handle conversion between types necessary for Javascript interop
 *
 */
public class JsUtil {

    @JsMethod
    private static byte[] decodeBase64(String base64data) {
        return Base64.getDecoder().decode(base64data);
    }

    @JsMethod
    private static String encodeBase64(byte[] bytes) {
        return new String(Base64.getEncoder().encode(bytes));
    }
    @JsMethod
    public static <K,V> Map<K,V> asMap(K[] keys, V[] values) {
        HashMap map = new HashMap<K,V>();
        for(int i = 0; i < keys.length; i++) {
            map.put(keys[i], values[i]);
        }
        return map;
    }

    @JsMethod
    public static <T> List<T> asList(T[] array) {
        return Arrays.asList(array);
    }

    @JsMethod
    public static <T> Set<T> asSet(T[] array) {
        return Arrays.asList(array).stream().collect(Collectors.toSet());
    }

    @JsMethod
    public static <T> List<T> emptyList() {
        return Collections.emptyList();
    }

    @JsMethod
    public static <T> Optional<T> emptyOptional() {
        return Optional.empty();
    }

    @JsMethod
    public static <T> Optional<T> optionalOf(T of) {
        return Optional.of(of);
    }


    @JsMethod
    public static LocalDateTime now() {
        return LocalDateTime.now();
    }

    @JsMethod
    public static MultiFactorAuthResponse generateAuthResponse(byte[] credentialId, String code) {
        return new MultiFactorAuthResponse(credentialId, Either.a(code));
    }

    @JsMethod
    public static MultiFactorAuthResponse generateWebAuthnResponse(byte[] credentialId, byte[] authenticatorData,
                                                                   byte[] clientDataJson, byte[] signature) {
        WebauthnResponse resp = new WebauthnResponse(authenticatorData, clientDataJson, signature);
        return new MultiFactorAuthResponse(credentialId, Either.b(resp));
    }

}
