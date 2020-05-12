package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

@JsType(namespace = "callback", isNative = true)
public class NativeJSCallback {

    public native void callAfterDelay(Callable func, int delay);
}
