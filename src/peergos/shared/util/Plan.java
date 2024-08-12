package peergos.shared.util;

import jsinterop.annotations.JsConstructor;

public class Plan {

    public final long desiredQuota;
    public final boolean annual;

    @JsConstructor
    public Plan(long desiredQuota, boolean annual) {
        this.desiredQuota = desiredQuota;
        this.annual = annual;
    }
}
