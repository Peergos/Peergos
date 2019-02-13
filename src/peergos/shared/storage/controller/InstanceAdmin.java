package peergos.shared.storage.controller;

import peergos.shared.io.ipfs.api.*;
import peergos.shared.user.*;
import peergos.shared.util.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * This is how the administrator of a Peergos instance can control it including:
 *
 * 1) Upgrade the Peergos version
 * 2) Change local user quotas
 * 3) Change policy on allowing new user sign ups
 * 4) Grant/revoke admin rights to another user
 * 5) Add/remove ipfs instances to the storage cluster
 * 6) Change the erasure code/raid policy for data storage per user
 */
public interface InstanceAdmin {

    CompletableFuture<VersionInfo> getVersionInfo();

    class VersionInfo {
        public final Version version;

        public VersionInfo(Version version) {
            this.version = version;
        }

        public Object toJSON() {
            Map<String, Object> res = new TreeMap<>();
            res.put("Version", version.toString());
            return res;
        }

        public static VersionInfo fromJSON(Object json) {
            if (! (json instanceof Map))
                throw new IllegalStateException("Invalid json for VersionInfo");
            return new VersionInfo(Version.parse((String)((Map) json).get("Version")));
        }
    }

    class HTTP implements InstanceAdmin {
        public static final String VERSION = "version";

        private final HttpPoster poster;

        public HTTP(HttpPoster poster) {
            this.poster = poster;
        }

        @Override
        public CompletableFuture<VersionInfo> getVersionInfo() {
            return poster.get(Constants.ADMIN_URL + VERSION)
                    .thenApply(raw -> VersionInfo.fromJSON(JSONParser.parse(new String(raw))));
        }
    }
}