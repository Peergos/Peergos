package java.time;

public class ZoneId {

	private final ZoneRules zoneRules= new ZoneRules();
	
	public ZoneId()
	{
		
	}
	public ZoneRules getRules() {
		return zoneRules;
	}
	
    public static ZoneId systemDefault() {
        return null;
    }
}
