package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.webdav.caldav.Recurrence;

import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

public class RecurrenceTests {

    private static final Instant FAR = Instant.parse("2100-01-01T00:00:00Z");

    private static List<LocalDateTime> expand(String rule, String start, ZoneId zone, Instant end, int limit) {
        Function<LocalDateTime, Instant> place = local -> local.atZone(zone).toInstant();
        return Recurrence.parse(rule).orElseThrow().expand(LocalDateTime.parse(start), place, end, limit);
    }

    private static List<LocalDateTime> expand(String rule, String start, int limit) {
        return expand(rule, start, ZoneOffset.UTC, FAR, limit);
    }

    private static List<LocalDateTime> at(String... times) {
        return java.util.Arrays.stream(times).map(LocalDateTime::parse).toList();
    }

    @Test
    public void weeklyWithACount() {
        Assert.assertEquals(at("2026-01-05T09:00", "2026-01-12T09:00", "2026-01-19T09:00"),
                expand("FREQ=WEEKLY;COUNT=3", "2026-01-05T09:00", 100));
    }

    @Test
    public void keepsTheWallClockAcrossADaylightSavingChange() {
        ZoneId london = ZoneId.of("Europe/London");
        List<LocalDateTime> found = expand("FREQ=WEEKLY;COUNT=3", "2026-03-23T09:00", london, FAR, 100);
        Assert.assertEquals(at("2026-03-23T09:00", "2026-03-30T09:00", "2026-04-06T09:00"), found);
    }

    @Test
    public void weeklyOnSeveralDaysEveryOtherWeek() {
        Assert.assertEquals(at("2026-01-05T09:00", "2026-01-07T09:00", "2026-01-19T09:00", "2026-01-21T09:00"),
                expand("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=4", "2026-01-05T09:00", 100));
    }

    @Test
    public void lastFridayOfTheMonth() {
        Assert.assertEquals(at("2026-01-30T10:00", "2026-02-27T10:00", "2026-03-27T10:00"),
                expand("FREQ=MONTHLY;BYDAY=-1FR;COUNT=3", "2026-01-30T10:00", 100));
    }

    @Test
    public void lastWeekdayOfTheMonthBySetPosition() {
        Assert.assertEquals(at("2026-01-30T10:00", "2026-02-27T10:00", "2026-03-31T10:00"),
                expand("FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=3", "2026-01-30T10:00", 100));
    }

    @Test
    public void the31stSkipsShortMonths() {
        Assert.assertEquals(at("2026-01-31T08:00", "2026-03-31T08:00", "2026-05-31T08:00"),
                expand("FREQ=MONTHLY;BYMONTHDAY=31;COUNT=3", "2026-01-31T08:00", 100));
    }

    @Test
    public void lastSundayOfOctoberEachYear() {
        Assert.assertEquals(at("2024-10-27T03:00", "2025-10-26T03:00", "2026-10-25T03:00"),
                expand("FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU;COUNT=3", "2024-10-27T03:00", 100));
    }

    @Test
    public void aBirthdayOnTheLeapDayOnlyComesInLeapYears() {
        Assert.assertEquals(at("2024-02-29T00:00", "2028-02-29T00:00"),
                expand("FREQ=YEARLY;COUNT=2", "2024-02-29T00:00", 100));
    }

    @Test
    public void dailyFilteredToWeekdays() {
        Assert.assertEquals(at("2026-01-09T09:00", "2026-01-12T09:00", "2026-01-13T09:00"),
                expand("FREQ=DAILY;BYDAY=MO,TU,WE,TH,FR;COUNT=3", "2026-01-09T09:00", 100));
    }

    @Test
    public void untilInUtcIsInclusive() {
        Assert.assertEquals(at("2026-01-05T09:00", "2026-01-12T09:00"),
                expand("FREQ=WEEKLY;UNTIL=20260112T090000Z", "2026-01-05T09:00", 100));
        Assert.assertEquals(at("2026-01-05T00:00", "2026-01-12T00:00"),
                expand("FREQ=WEEKLY;UNTIL=20260112", "2026-01-05T00:00", 100));
    }

    @Test
    public void dtstartIsTheFirstOccurrenceEvenOffTheRule() {
        // A Tuesday start on a Monday rule: the start still counts, toward COUNT too.
        Assert.assertEquals(at("2026-01-06T09:00", "2026-01-12T09:00"),
                expand("FREQ=WEEKLY;BYDAY=MO;COUNT=2", "2026-01-06T09:00", 100));
    }

    @Test
    public void stopsAtTheEndOfTheWindowAndTheLimit() {
        Assert.assertEquals(2, expand("FREQ=DAILY", "2026-01-01T09:00", ZoneOffset.UTC,
                Instant.parse("2026-01-02T12:00:00Z"), 100).size());
        Assert.assertEquals(5, expand("FREQ=DAILY", "2026-01-01T09:00", 5).size());
    }

    @Test
    public void aRuleThatNeverMatchesEnds() {
        Assert.assertEquals(at("2026-01-01T09:00"), expand("FREQ=MONTHLY;BYMONTH=2;BYMONTHDAY=30", "2026-01-01T09:00", 100));
    }

    @Test
    public void whatItCannotExpandDoesNotParse() {
        Assert.assertEquals(Optional.empty(), Recurrence.parse("FREQ=YEARLY;BYWEEKNO=20"));
        Assert.assertEquals(Optional.empty(), Recurrence.parse("FREQ=HOURLY"));
        Assert.assertEquals(Optional.empty(), Recurrence.parse("FREQ=WEEKLY;BYDAY=XX"));
        Assert.assertEquals(Optional.empty(), Recurrence.parse("FREQ=WEEKLY;INTERVAL=0"));
    }
}
