package peergos.shared.login;

import java.util.*;
import java.util.concurrent.*;

/** Where a client keeps the token its home server gave it for logging in from this device. */
public interface DeviceTokenStore {

    Optional<String> get(String username);

    void set(String username, String token);

    static DeviceTokenStore inMemory() {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        return new DeviceTokenStore() {
            @Override
            public Optional<String> get(String username) {
                return Optional.ofNullable(tokens.get(username));
            }

            @Override
            public void set(String username, String token) {
                tokens.put(username, token);
            }
        };
    }
}
