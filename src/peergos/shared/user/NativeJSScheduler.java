package peergos.shared.user;

import jsinterop.annotations.JsType;

import java.util.function.Supplier;


@JsType(namespace = "callback", isNative = true)
public class NativeJSScheduler {

    public native void callAfterDelay(Supplier func, int delayMs);
}
