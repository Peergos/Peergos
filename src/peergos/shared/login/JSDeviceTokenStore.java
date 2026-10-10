package peergos.shared.login;

import peergos.shared.user.*;

import java.util.*;

public class JSDeviceTokenStore implements DeviceTokenStore {

    private final NativeJSDeviceTokens tokens = new NativeJSDeviceTokens();

    @Override
    public Optional<String> get(String username) {
        return Optional.ofNullable(tokens.get(username));
    }

    @Override
    public void set(String username, String token) {
        tokens.set(username, token);
    }
}
