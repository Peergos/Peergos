package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.webdav.caldav.FreeBusy;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

public class FreeBusyTests {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-02-01T00:00:00Z");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private static String calendar(String... lines) {
        return "BEGIN:VCALENDAR\r\nVERSION:2.0\r\n" + String.join("\r\n", lines) + "\r\nEND:VCALENDAR\r\n";
    }

    private static List<String> busy(String... objects) {
        return Arrays.stream(FreeBusy.report(List.of(objects), FROM, TO, NOW).split("\r\n"))
                .filter(l -> l.startsWith("FREEBUSY"))
                .toList();
    }

    @Test
    public void aSeriesLessItsExclusionsWithItsOverride() {
        String series = calendar("BEGIN:VEVENT", "UID:w", "DTSTART:20260105T090000Z", "DTEND:20260105T100000Z",
                "RRULE:FREQ=WEEKLY;COUNT=4", "EXDATE:20260112T090000Z", "END:VEVENT",
                "BEGIN:VEVENT", "UID:w", "RECURRENCE-ID:20260119T090000Z", "DTSTART:20260119T140000Z",
                "DTEND:20260119T150000Z", "END:VEVENT");
        Assert.assertEquals(List.of(
                "FREEBUSY;FBTYPE=BUSY:20260105T090000Z/20260105T100000Z",
                "FREEBUSY;FBTYPE=BUSY:20260119T140000Z/20260119T150000Z",
                "FREEBUSY;FBTYPE=BUSY:20260126T090000Z/20260126T100000Z"), busy(series));
    }

    @Test
    public void freeCancelledAndTentativeEvents() {
        Assert.assertEquals(List.of("FREEBUSY;FBTYPE=BUSY-TENTATIVE:20260106T090000Z/20260106T100000Z"), busy(
                calendar("BEGIN:VEVENT", "UID:a", "DTSTART:20260105T090000Z", "DTEND:20260105T100000Z", "TRANSP:TRANSPARENT", "END:VEVENT"),
                calendar("BEGIN:VEVENT", "UID:b", "DTSTART:20260107T090000Z", "DTEND:20260107T100000Z", "STATUS:CANCELLED", "END:VEVENT"),
                calendar("BEGIN:VEVENT", "UID:c", "DTSTART:20260106T090000Z", "DTEND:20260106T100000Z", "STATUS:TENTATIVE", "END:VEVENT")));
    }

    @Test
    public void overlappingEventsAreJoinedAndClippedToTheRange() {
        Assert.assertEquals(List.of(
                "FREEBUSY;FBTYPE=BUSY:20260101T000000Z/20260101T020000Z",
                "FREEBUSY;FBTYPE=BUSY:20260105T090000Z/20260105T113000Z"), busy(
                calendar("BEGIN:VEVENT", "UID:a", "DTSTART:20260105T090000Z", "DTEND:20260105T100000Z", "END:VEVENT"),
                calendar("BEGIN:VEVENT", "UID:b", "DTSTART:20260105T093000Z", "DURATION:PT2H", "END:VEVENT"),
                calendar("BEGIN:VEVENT", "UID:c", "DTSTART:20251231T220000Z", "DTEND:20260101T020000Z", "END:VEVENT")));
    }

    @Test
    public void aZonedSeriesKeepsItsLocalTime() {
        // 09:00 in New York is 14:00 UTC in January and 13:00 after the clocks change in March.
        String series = calendar("BEGIN:VEVENT", "UID:z", "DTSTART;TZID=America/New_York:20260302T090000",
                "DTEND;TZID=America/New_York:20260302T100000", "RRULE:FREQ=WEEKLY;COUNT=2", "END:VEVENT");
        List<String> lines = Arrays.stream(FreeBusy.report(List.of(series), Instant.parse("2026-03-01T00:00:00Z"),
                Instant.parse("2026-04-01T00:00:00Z"), NOW).split("\r\n")).filter(l -> l.startsWith("FREEBUSY")).toList();
        Assert.assertEquals(List.of(
                "FREEBUSY;FBTYPE=BUSY:20260302T140000Z/20260302T150000Z",
                "FREEBUSY;FBTYPE=BUSY:20260309T130000Z/20260309T140000Z"), lines);
    }

    @Test
    public void allDayEventsAndExtraDates() {
        Assert.assertEquals(List.of(
                "FREEBUSY;FBTYPE=BUSY:20260110T000000Z/20260111T000000Z",
                "FREEBUSY;FBTYPE=BUSY:20260120T090000Z/20260120T100000Z",
                "FREEBUSY;FBTYPE=BUSY:20260122T090000Z/20260122T100000Z"), busy(
                calendar("BEGIN:VEVENT", "UID:a", "DTSTART;VALUE=DATE:20260110", "DTEND;VALUE=DATE:20260111", "END:VEVENT"),
                calendar("BEGIN:VEVENT", "UID:r", "DTSTART:20260120T090000Z", "DTEND:20260120T100000Z",
                        "RDATE:20260122T090000Z", "END:VEVENT")));
    }

    @Test
    public void aRangeWithNothingInItIsEmpty() {
        String report = FreeBusy.report(List.of(), FROM, TO, NOW);
        Assert.assertTrue(report, report.contains("BEGIN:VFREEBUSY\r\nDTSTAMP:20260101T000000Z\r\n"
                + "DTSTART:20260101T000000Z\r\nDTEND:20260201T000000Z\r\nEND:VFREEBUSY"));
    }
}
