package peergos.server.webdav;

import org.peergos.config.Jsonable;
import peergos.shared.util.ArrayOps;

import java.util.*;

public class MountConfig implements Jsonable {
    public static final String FILENAME = "mount-config.json";

    public final boolean enabled;
    public final String peergosUsername;
    public final String peergosPassword;
    public final String webdavUsername;
    public final String webdavPassword;
    public final int webdavPort;
    public final String authType;
    /**
     * Dedicated TOTP credential for mount login. Created by the UI when the user has
     * 2FA enabled, so the mount never has to handle interactive MFA challenges.
     *
     * Both fields hex-encoded for round-trip-safe JSON storage; empty string means
     * no TOTP was provisioned (user had no 2FA at mount-create time → password-only login).
     *  - totpCredentialId: the second-factor identifier returned by addTotpFactor.
     *    Matched against MultiFactorAuthRequest.methods[i].credentialId at login time.
     *  - totpSecret:       the shared HMAC-SHA1 key used to generate the 6-digit code.
     */
    public final String totpCredentialId;
    public final String totpSecret;

    public MountConfig(boolean enabled, String peergosUsername, String peergosPassword,
                       String webdavUsername, String webdavPassword, int webdavPort, String authType,
                       String totpCredentialId, String totpSecret) {
        this.enabled = enabled;
        this.peergosUsername = peergosUsername;
        this.peergosPassword = peergosPassword;
        this.webdavUsername = webdavUsername;
        this.webdavPassword = webdavPassword;
        this.webdavPort = webdavPort;
        this.authType = authType;
        this.totpCredentialId = totpCredentialId == null ? "" : totpCredentialId;
        this.totpSecret       = totpSecret       == null ? "" : totpSecret;
    }

    public boolean hasTotp() {
        return totpCredentialId != null && !totpCredentialId.isEmpty()
                && totpSecret != null && !totpSecret.isEmpty();
    }

    public byte[] totpCredentialIdBytes() {
        return hasTotp() ? ArrayOps.hexToBytes(totpCredentialId) : new byte[0];
    }

    public byte[] totpSecretBytes() {
        return hasTotp() ? ArrayOps.hexToBytes(totpSecret) : new byte[0];
    }

    public static MountConfig disabled() {
        return new MountConfig(false, "", "", "", "", 8090, "digest", "", "");
    }

    @Override
    public Map<String, Object> toJson() {
        LinkedHashMap<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", enabled);
        res.put("peergosUsername", peergosUsername);
        res.put("peergosPassword", peergosPassword);
        res.put("webdavUsername", webdavUsername);
        res.put("webdavPassword", webdavPassword);
        res.put("webdavPort", webdavPort);
        res.put("authType", authType);
        res.put("totpCredentialId", totpCredentialId);
        res.put("totpSecret", totpSecret);
        return res;
    }

    public static MountConfig fromJson(Map<String, Object> json) {
        return new MountConfig(
            (Boolean) json.get("enabled"),
            (String) json.get("peergosUsername"),
            (String) json.get("peergosPassword"),
            (String) json.get("webdavUsername"),
            (String) json.get("webdavPassword"),
            ((Number) json.get("webdavPort")).intValue(),
            (String) json.get("authType"),
            (String) json.getOrDefault("totpCredentialId", ""),
            (String) json.getOrDefault("totpSecret", "")
        );
    }
}
