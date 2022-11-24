package peergos.shared;

import jsinterop.annotations.*;

@JsType(namespace = "online", isNative = true)
public class NativeJsOnlineState {

    public native boolean isOnline();
}
