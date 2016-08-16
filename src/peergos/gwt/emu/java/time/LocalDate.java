package java.time;

import java.time.chrono.ChronoLocalDate;
import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;

public class LocalDate {

    private final int year;
    private final short month;
    private final short day;
	
	private static final int DAYS_PER_CYCLE = 146097;
	static final long DAYS_0000_TO_1970 = (DAYS_PER_CYCLE * 5L) - (30L * 365L + 7L);
	public static final int YEAR_MAX_VALUE = 999_999_999;
	public static final int YEAR_MIN_VALUE = -999_999_999;
    static final int HOURS_PER_DAY = 24;
    static final int MINUTES_PER_HOUR = 60;
    static final int MINUTES_PER_DAY = MINUTES_PER_HOUR * HOURS_PER_DAY;
    static final int SECONDS_PER_MINUTE = 60;
    static final int SECONDS_PER_HOUR = SECONDS_PER_MINUTE * MINUTES_PER_HOUR;
    static final int SECONDS_PER_DAY = SECONDS_PER_HOUR * HOURS_PER_DAY;

	
	public static final LocalDate MAX = LocalDate.of(YEAR_MAX_VALUE, 12, 31);
	public static final LocalDate MIN = LocalDate.of(YEAR_MIN_VALUE, 1, 1);
	
    private LocalDate(int year, int month, int dayOfMonth) {
        this.year = year;
        this.month = (short) month;
        this.day = (short) dayOfMonth;
    }
    
    public static LocalDate of(int year, int month, int dayOfMonth) {
    	return new LocalDate(year, month, dayOfMonth);
    }
    
    public static LocalDate parse(String text) {
    	//ie '2011-12-03'
    	try {
	    	if(text.trim().length() == 10) {
	    		String trimmed = text.trim();
	    		int year = Integer.valueOf(trimmed.substring(0,4));
	    		int month = Integer.valueOf(trimmed.substring(5,7));
	    		int dayOfMonth = Integer.valueOf(trimmed.substring(8,10));
	    		return of(year, month, dayOfMonth);
	    	}
    	}catch(Exception e){
    		
    	}
        throw new DateTimeParseException("Unable to parse:" + text);
    }
    
    public static LocalDate now(Clock clock) {
        final Instant now = clock.instant();  // called once
        ZoneOffset offset = clock.getZone().getRules().getOffset(now);
        long epochSec = now.getEpochSecond() + offset.getTotalSeconds();  // overflow caught later
        long epochDay = Math.floorDiv(epochSec, SECONDS_PER_DAY);
        return LocalDate.ofEpochDay(epochDay);
    }
    
    public static LocalDate ofEpochDay(long epochDay) {
        long zeroDay = epochDay + DAYS_0000_TO_1970;
        // find the march-based year
        zeroDay -= 60;  // adjust to 0000-03-01 so leap day is at end of four year cycle
        long adjust = 0;
        if (zeroDay < 0) {
            // adjust negative years to positive for calculation
            long adjustCycles = (zeroDay + 1) / DAYS_PER_CYCLE - 1;
            adjust = adjustCycles * 400;
            zeroDay += -adjustCycles * DAYS_PER_CYCLE;
        }
        long yearEst = (400 * zeroDay + 591) / DAYS_PER_CYCLE;
        long doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        if (doyEst < 0) {
            // fix estimate
            yearEst--;
            doyEst = zeroDay - (365 * yearEst + yearEst / 4 - yearEst / 100 + yearEst / 400);
        }
        yearEst += adjust;  // reset any negative year
        int marchDoy0 = (int) doyEst;

        // convert march-based values back to january-based
        int marchMonth0 = (marchDoy0 * 5 + 2) / 153;
        int month = (marchMonth0 + 2) % 12 + 1;
        int dom = marchDoy0 - (marchMonth0 * 306 + 5) / 10 + 1;
        yearEst += marchMonth0 / 10;

        int year = (int)yearEst;
        return new LocalDate(year, month, dom);
    }
    
    public static LocalDate now() {
        return now(Clock.systemDefaultZone());
    }
    
    public static boolean isLeapYear(long prolepticYear) {
        return ((prolepticYear & 3) == 0) && ((prolepticYear % 100) != 0 || (prolepticYear % 400) == 0);
    }
    
    public long toEpochDay() {
        long y = year;
        long m = month;
        long total = 0;
        total += 365 * y;
        if (y >= 0) {
            total += (y + 3) / 4 - (y + 99) / 100 + (y + 399) / 400;
        } else {
            total -= y / -4 - y / -100 + y / -400;
        }
        total += ((367 * m - 362) / 12);
        total += day - 1;
        if (m > 2) {
            total--;
            if (isLeapYear(year) == false) {
                total--;
            }
        }
        return total - DAYS_0000_TO_1970;
    }
    
    private static LocalDate resolvePreviousValid(int year, int month, int day) {
        switch (month) {
            case 2:
                day = Math.min(day, isLeapYear(year) ? 29 : 28);
                break;
            case 4:
            case 6:
            case 9:
            case 11:
                day = Math.min(day, 30);
                break;
        }
        return new LocalDate(year, month, day);
    }
    
    public LocalDate plusMonths(long monthsToAdd) {
        if (monthsToAdd == 0) {
            return this;
        }
        long monthCount = year * 12L + (month - 1);
        long calcMonths = monthCount + monthsToAdd;  // safe overflow
        int newYear = (int)Math.floorDiv(calcMonths, 12);
        int newMonth = (int)Math.floorMod(calcMonths, 12) + 1;
        return resolvePreviousValid(newYear, newMonth, day);
    }

    
    public boolean isBefore(LocalDate other) {
        return compareTo0((LocalDate) other) < 0;
    }

    public boolean isAfter(LocalDate other) {
        return compareTo0((LocalDate) other) > 0;
    }
    
    public LocalDate plusWeeks(long weeksToAdd) {
    	return null;
    }
    
    public LocalDate plusYears(long yearsToAdd) {
    	return null;
    }
    private int compareTo0(LocalDate otherDate) {
        int cmp = (year - otherDate.year);
        if (cmp == 0) {
            cmp = (month - otherDate.month);
            if (cmp == 0) {
                cmp = (day - otherDate.day);
            }
        }
        return cmp;
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof LocalDate) {
            return compareTo0((LocalDate) obj) == 0;
        }
        return false;
    }

    @Override
    public int hashCode() {
        int yearValue = year;
        int monthValue = month;
        int dayValue = day;
        return (yearValue & 0xFFFFF800) ^ ((yearValue << 11) + (monthValue << 6) + (dayValue));
    }
}
