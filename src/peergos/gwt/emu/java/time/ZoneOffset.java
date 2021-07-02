package java.time;

@SuppressWarnings("unusable-by-js")
public class ZoneOffset extends ZoneId{

	public static final ZoneOffset UTC = ZoneOffset.ofTotalSeconds(0);
	
	private int totalSeconds;
	
    private ZoneOffset(int totalSeconds) {
        this.totalSeconds = totalSeconds;
    }
    
	public static ZoneOffset ofTotalSeconds(int totalSeconds) {
			return new ZoneOffset(totalSeconds);
	}
	
    public int getTotalSeconds() {
        return totalSeconds;
    }
}
