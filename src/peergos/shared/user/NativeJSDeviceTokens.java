package peergos.shared.user;

import jsinterop.annotations.JsType;

@JsType(namespace = "deviceTokens", isNative = true)
public class NativeJSDeviceTokens {

    public native String get(String username);

    public native void set(String username, String token);
}
