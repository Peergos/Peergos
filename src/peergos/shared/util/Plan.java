package peergos.shared.util;

public class Plan {

    public final long desiredQuota;
    public final boolean annual;

    public Plan(long desiredQuota, boolean annual) {
        this.desiredQuota = desiredQuota;
        this.annual = annual;
    }
}
