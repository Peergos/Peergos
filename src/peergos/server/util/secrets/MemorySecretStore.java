package peergos.server.util.secrets;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory {@link SecretStore} for tests and any other context that doesn't
 * need real persistence. Not used in production.
 */
public final class MemorySecretStore implements SecretStore {

    private final Map<String, String> entries = new HashMap<>();

    private static String key(String service, String account) {
        return service + "\0" + account;
    }

    @Override
    public synchronized void put(String service, String account, String value) {
        entries.put(key(service, account), value);
    }

    @Override
    public synchronized Optional<String> get(String service, String account) {
        return Optional.ofNullable(entries.get(key(service, account)));
    }

    @Override
    public synchronized void delete(String service, String account) {
        entries.remove(key(service, account));
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
