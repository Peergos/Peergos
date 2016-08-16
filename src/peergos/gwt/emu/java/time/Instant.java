package java.time;

public class Instant {

    public static final Instant EPOCH = new Instant(0, 0);

    private static final long MIN_SECOND = -31557014167219200L;

    private static final long MAX_SECOND = 31556889864403199L;
    
    private final long seconds;
    private final int nanos;
    
    private Instant(long epochSecond, int nanos) {
        super();
        this.seconds = epochSecond;
        this.nanos = nanos;
    }
    public int getNano() {
    	return nanos;
    }
    public long getEpochSecond() {
    	return seconds;
    }
    
    private static Instant create(long seconds, int nanoOfSecond) {
        if ((seconds | nanoOfSecond) == 0) {
            return EPOCH;
        }
        if (seconds < MIN_SECOND || seconds > MAX_SECOND) {
            throw new DateTimeException("Instant exceeds minimum or maximum instant");
        }
        return new Instant(seconds, nanoOfSecond);
    }
    
    public static Instant ofEpochSecond(long epochSecond) {
        return create(epochSecond, 0);
    }
    
    public static Instant ofEpochMilli(long epochMilli) {
        long secs = Math.floorDiv(epochMilli, 1000);
        int mos = (int)Math.floorMod(epochMilli, 1000);
        return create(secs, mos * 1000_000);
    }
    
}
