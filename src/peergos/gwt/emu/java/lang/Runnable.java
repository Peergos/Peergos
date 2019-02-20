package java.lang;

import jsinterop.annotations.*;

@FunctionalInterface
public interface Runnable {

    @JsMethod
    void run();
}