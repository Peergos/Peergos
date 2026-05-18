package peergos.server.webdav;

import org.peergos.config.Jsonable;
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

    public MountConfig(boolean enabled, String peergosUsername, String peergosPassword,
                       String webdavUsername, String webdavPassword, int webdavPort, String authType) {
        this.enabled = enabled;
        this.peergosUsername = peergosUsername;
        this.peergosPassword = peergosPassword;
        this.webdavUsername = webdavUsername;
        this.webdavPassword = webdavPassword;
        this.webdavPort = webdavPort;
        this.authType = authType;
    }

    public static MountConfig disabled() {
        return new MountConfig(false, "", "", "", "", 8090, "digest");
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
            (String) json.get("authType")
        );
    }
}
