package java.time;

public class LocalTime {

	static final int SECONDS_PER_MINUTE = 60;
	static final int MINUTES_PER_HOUR = 60;
    static final int HOURS_PER_DAY = 24;
    static final int MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY;
    static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    
    
    static final long NANOS_PER_SECOND = 1000_000_000L;
    static final long NANOS_PER_MINUTE = NANOS_PER_SECOND * SECONDS_PER_MINUTE;
    static final long NANOS_PER_HOUR = NANOS_PER_MINUTE * MINUTES_PER_HOUR;
    
    public static final LocalTime MIN;
    public static final LocalTime MAX;
    public static final LocalTime MIDNIGHT;
    public static final LocalTime NOON;

    private static final LocalTime[] HOURS = new LocalTime[24];
    static {
        for (int i = 0; i < HOURS.length; i++) {
            HOURS[i] = new LocalTime(i, 0, 0, 0);
        }
        MIDNIGHT = HOURS[0];
        NOON = HOURS[12];
        MIN = HOURS[0];
        MAX = new LocalTime(23, 59, 59, 999_999_999);
    }
    
    private final byte hour;
    private final byte minute;
    private final byte second;
    private final int nano;
    
    private LocalTime(int hour, int minute, int second, int nanoOfSecond) {
        this.hour = (byte) hour;
        this.minute = (byte) minute;
        this.second = (byte) second;
        this.nano = nanoOfSecond;
    }

    public int toSecondOfDay() {
        int total = hour * SECONDS_PER_HOUR;
        total += minute * SECONDS_PER_MINUTE;
        total += second;
        return total;
    }
    
    public long toNanoOfDay() {
        long total = hour * NANOS_PER_HOUR;
        total += minute * NANOS_PER_MINUTE;
        total += second * NANOS_PER_SECOND;
        total += nano;
        return total;
    }
    
    public static LocalTime ofNanoOfDay(long nanoOfDay) {
        int hours = (int) (nanoOfDay / NANOS_PER_HOUR);
        nanoOfDay -= hours * NANOS_PER_HOUR;
        int minutes = (int) (nanoOfDay / NANOS_PER_MINUTE);
        nanoOfDay -= minutes * NANOS_PER_MINUTE;
        int seconds = (int) (nanoOfDay / NANOS_PER_SECOND);
        nanoOfDay -= seconds * NANOS_PER_SECOND;
        return create(hours, minutes, seconds, (int) nanoOfDay);
    }
    
    private static LocalTime create(int hour, int minute, int second, int nanoOfSecond) {
        if ((minute | second | nanoOfSecond) == 0) {
            return HOURS[hour];
        }
        return new LocalTime(hour, minute, second, nanoOfSecond);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LocalTime) {
            LocalTime other = (LocalTime) obj;
            return hour == other.hour && minute == other.minute &&
                    second == other.second && nano == other.nano;
        }
        return false;
    }
    
    @Override
    public int hashCode() {
        long nod = toNanoOfDay();
        return (int) (nod ^ (nod >>> 32));
    }

}
