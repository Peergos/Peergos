package peergos.server.util.secrets;

import java.util.Optional;

/**
 * No-op {@link SecretStore} for environments without a usable OS keyring
 * (Linux outside Flatpak). With this store
 * MountConfigHandler keeps {@code peergosPassword} and {@code totpSecret}
 * inline in {@code mount-config.json}.
 */
public final class JsonFileSecretStore implements SecretStore {

    @Override
    public void put(String service, String account, String value) {
        // Secrets are persisted by MountConfigHandler writing the JSON config.
    }

    @Override
    public Optional<String> get(String service, String account) {
        // MountConfigHandler reads the secret directly from the loaded MountConfig.
        return Optional.empty();
    }

    @Override
    public void delete(String service, String account) {
        // The config file is deleted on unmount; nothing to do here.
    }

    @Override
    public boolean embedsInConfigFile() {
        return true;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
