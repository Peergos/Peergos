package java.time;


public class Clock {

	private final ZoneId zone = new ZoneId();
	
    Clock() {
    }
    public ZoneId getZone() {
    	return zone;
    }
    public static Clock systemDefaultZone() {
        return new Clock();
    }
    public long millis() {
        return System.currentTimeMillis();
    }

    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }
}
