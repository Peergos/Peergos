package peergos.server.webdav;

import org.peergos.config.Jsonable;
import peergos.shared.util.ArrayOps;

import java.util.*;

public class MountConfig implements Jsonable {
    public static final String FILENAME = "mount-config.json";

    public final boolean enabled;
    /** What this login is actually used for. The credential, its keyring entry and its login are
     *  shared, so turning the calendar on does not mean mounting a drive, and a user who only
     *  wants their calendar never gets a second secret store. */
    public final boolean mountDrive;
    public final boolean syncCalendar;
    public final boolean syncContacts;
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

    /** Everything on but contacts, which is the shape every config had before the split. */
    public MountConfig(boolean enabled, String peergosUsername, String peergosPassword,
                       String webdavUsername, String webdavPassword, int webdavPort, String authType,
                       String totpCredentialId, String totpSecret) {
        this(enabled, enabled, false, false, peergosUsername, peergosPassword,
                webdavUsername, webdavPassword, webdavPort, authType, totpCredentialId, totpSecret);
    }

    public MountConfig(boolean enabled, boolean mountDrive, boolean syncCalendar, boolean syncContacts,
                       String peergosUsername, String peergosPassword,
                       String webdavUsername, String webdavPassword, int webdavPort, String authType,
                       String totpCredentialId, String totpSecret) {
        this.enabled = enabled;
        this.mountDrive = mountDrive;
        this.syncCalendar = syncCalendar;
        this.syncContacts = syncContacts;
        this.peergosUsername = peergosUsername;
        this.peergosPassword = peergosPassword;
        this.webdavUsername = webdavUsername;
        this.webdavPassword = webdavPassword;
        this.webdavPort = webdavPort;
        this.authType = authType;
        this.totpCredentialId = totpCredentialId == null ? "" : totpCredentialId;
        this.totpSecret       = totpSecret       == null ? "" : totpSecret;
    }

    /** Whether this login is being used for anything at all. */
    public boolean anyFeature() {
        return mountDrive || syncCalendar || syncContacts;
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
        return new MountConfig(false, false, false, false, "", "", "", "", 8090, "digest", "", "");
    }

    public MountConfig withoutSecrets() {
        return new MountConfig(enabled, mountDrive, syncCalendar, syncContacts, peergosUsername, "",
                webdavUsername, webdavPassword, webdavPort, authType,
                totpCredentialId, "");
    }

    public MountConfig withSecrets(String peergosPassword, String totpSecret) {
        return new MountConfig(enabled, mountDrive, syncCalendar, syncContacts, peergosUsername, peergosPassword,
                webdavUsername, webdavPassword, webdavPort, authType,
                totpCredentialId, totpSecret);
    }

    public static final class Credentials {
        public final String peergosPassword;
        public final String totpSecret;

        public Credentials(String peergosPassword, String totpSecret) {
            this.peergosPassword = peergosPassword == null ? "" : peergosPassword;
            this.totpSecret      = totpSecret      == null ? "" : totpSecret;
        }
    }

    public Credentials credentials() {
        return new Credentials(peergosPassword, totpSecret);
    }

    @Override
    public Map<String, Object> toJson() {
        LinkedHashMap<String, Object> res = new LinkedHashMap<>();
        res.put("enabled", enabled);
        res.put("mountDrive", mountDrive);
        res.put("syncCalendar", syncCalendar);
        res.put("syncContacts", syncContacts);
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
        boolean enabled = (Boolean) json.get("enabled");
        return new MountConfig(
            enabled,
            // a config written before the split only ever meant the drive
            (Boolean) json.getOrDefault("mountDrive", enabled),
            (Boolean) json.getOrDefault("syncCalendar", false),
            (Boolean) json.getOrDefault("syncContacts", false),
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
