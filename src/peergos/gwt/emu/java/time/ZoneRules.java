package java.time;

public class ZoneRules {

	public ZoneRules()
	{
		
	}
	public ZoneOffset getOffset(Instant instant) {
		return ZoneOffset.UTC; //always
	}
}
