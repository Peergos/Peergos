package java.time;

import jsinterop.annotations.*;

import java.time.chrono.ChronoLocalDateTime;
@JsType
@SuppressWarnings("unusable-by-js")
public class LocalDateTime {

	private LocalDate date;
	private LocalTime time;
	
	static final long NANOS_PER_SECOND = 1000_000_000L;
    static final int HOURS_PER_DAY = 24;
    static final int MINUTES_PER_HOUR = 60;
    static final int SECONDS_PER_MINUTE = 60;
    static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;
	
	public static final LocalDateTime MIN = LocalDateTime.of(LocalDate.MIN, LocalTime.MIN);
	
    private LocalDateTime(LocalDate date, LocalTime time) {
        this.date = date;
        this.time = time;
    }
    
    public static LocalDateTime of(LocalDate date, LocalTime time) {
        return new LocalDateTime(date, time);
    }
    
	public LocalTime toLocalTime() {
		return time;
	}
	public LocalDate toLocalDate() {
		return date;
	}
    public long toEpochSecond(ZoneOffset offset) {
        long epochDay = toLocalDate().toEpochDay();
        long secs = epochDay * 86400 + toLocalTime().toSecondOfDay();
        secs -= offset.getTotalSeconds();
        return secs;
    }
    public static LocalDateTime ofEpochSecond(long epochSecond, int nanoOfSecond, ZoneOffset offset) {
        long localSecond = epochSecond + offset.getTotalSeconds();  // overflow caught later
        long localEpochDay = Math.floorDiv(localSecond, SECONDS_PER_DAY);
        int secsOfDay = (int)Math.floorMod(localSecond, SECONDS_PER_DAY);
        LocalDate date = LocalDate.ofEpochDay(localEpochDay);
        LocalTime time = LocalTime.ofNanoOfDay(secsOfDay * NANOS_PER_SECOND + nanoOfSecond);
        return new LocalDateTime(date, time);
    }
    @JsIgnore
    public static LocalDateTime now(Clock clock) {
        final Instant now = clock.instant();  // called once
        ZoneOffset offset = clock.getZone().getRules().getOffset(now);
        return ofEpochSecond(now.getEpochSecond(), now.getNano(), offset);
    }
    
    public static LocalDateTime now() {
        return now(Clock.systemDefaultZone());
    }
    
    public LocalDateTime plusNanos(long nanos) {
        return null;
    }
    
    public static LocalDateTime ofInstant(Instant instant, ZoneId zone) {
    	return null;
    }
    
    public Instant toInstant(ZoneOffset offset) {
        return null;
    }
    public boolean isBefore(LocalDateTime other) {
    	return false;
    }
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LocalDateTime) {
            LocalDateTime other = (LocalDateTime) obj;
            return date.equals(other.date) && time.equals(other.time);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return date.hashCode() ^ time.hashCode();
    }

}
