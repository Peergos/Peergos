package peergos.client;


import jsinterop.annotations.JsType;

@JsType(namespace = "initJS", isNative = true)
public class NativeInit {
    public native void init() ;

}
