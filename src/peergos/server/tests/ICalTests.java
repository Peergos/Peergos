package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.webdav.caldav.ICal;

import java.time.*;
import java.util.Optional;

public class ICalTests {

    private static String event(String... properties) {
        StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n");
        for (String property : properties)
            ics.append(property).append("\r\n");
        return ics.append("END:VEVENT\r\nEND:VCALENDAR\r\n").toString();
    }

    @Test
    public void unfoldsContinuationLines() {
        Assert.assertEquals(java.util.List.of("SUMMARY:a long summary", "UID:x"),
                ICal.unfold("SUMMARY:a long\r\n  summary\r\nUID:x\r\n"));
    }

    @Test
    public void readsUidAndStart() {
        ICal.Summary summary = ICal.summarise(event("UID:abc-123", "DTSTART:20240315T090000Z", "DTEND:20240315T100000Z"));
        Assert.assertEquals(Optional.of("abc-123"), summary.uid);
        Assert.assertEquals(Optional.of("VEVENT"), summary.componentType);
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T09:00:00Z")), summary.start);
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T10:00:00Z")), summary.end);
        Assert.assertFalse(summary.recurring);
    }

    @Test
    public void readsZonedAndAllDayStarts() {
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T09:00:00Z")),
                ICal.summarise(event("UID:a", "DTSTART;TZID=Europe/London:20240315T090000")).start);
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T00:00:00Z")),
                ICal.summarise(event("UID:a", "DTSTART;VALUE=DATE:20240315")).start);
    }

    @Test
    public void derivesEndFromDuration() {
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T10:30:00Z")),
                ICal.summarise(event("UID:a", "DTSTART:20240315T090000Z", "DURATION:PT1H30M")).end);
        Assert.assertEquals(Optional.of(Duration.ofDays(7)), ICal.parseDuration("P1W"));
    }

    @Test
    public void shardMatchesTheWebAppsUtcMonth() {
        Assert.assertEquals(Optional.of(YearMonth.of(2024, 3)),
                ICal.shard(ICal.summarise(event("UID:a", "DTSTART:20240315T090000Z"))));
        // The web app takes the UTC calendar fields, so a late-evening event west of UTC
        // rolls forward into the next month's directory.
        Assert.assertEquals(Optional.of(YearMonth.of(2024, 4)),
                ICal.shard(ICal.summarise(event("UID:a", "DTSTART;TZID=America/New_York:20240331T230000"))));
    }

    @Test
    public void recurringEventsAreFlagged() {
        Assert.assertTrue(ICal.summarise(event("UID:a", "DTSTART:20240315T090000Z", "RRULE:FREQ=WEEKLY")).recurring);
    }

    @Test
    public void timeRangeExcludesOnlyWhatItIsSureAbout() {
        ICal.Summary march = ICal.summarise(event("UID:a", "DTSTART:20240315T090000Z", "DTEND:20240315T100000Z"));
        Assert.assertTrue(march.overlaps(Optional.of(Instant.parse("2024-03-01T00:00:00Z")),
                Optional.of(Instant.parse("2024-04-01T00:00:00Z"))));
        Assert.assertFalse(march.overlaps(Optional.of(Instant.parse("2024-05-01T00:00:00Z")), Optional.empty()));
        Assert.assertFalse(march.overlaps(Optional.empty(), Optional.of(Instant.parse("2024-01-01T00:00:00Z"))));

        // Recurring and unparseable objects always match rather than risk hiding an event.
        Assert.assertTrue(ICal.summarise(event("UID:a", "DTSTART:20240315T090000Z", "RRULE:FREQ=WEEKLY"))
                .overlaps(Optional.of(Instant.parse("2030-01-01T00:00:00Z")), Optional.empty()));
        Assert.assertTrue(ICal.summarise("not iCalendar at all")
                .overlaps(Optional.of(Instant.parse("2030-01-01T00:00:00Z")), Optional.empty()));
    }

    @Test
    public void ignoresColonsInsideQuotedParameters() {
        ICal.Summary summary = ICal.summarise(event("UID:a",
                "DTSTART;TZID=\"Europe/London\":20240315T090000",
                "ATTENDEE;CN=\"Smith: J\":mailto:j@example.com"));
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T09:00:00Z")), summary.start);
    }

    @Test
    public void unbalancedComponentsAreRejected() {
        Assert.assertEquals(Optional.empty(), ICal.parse("BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nEND:VCALENDAR\r\n"));
        Assert.assertEquals(Optional.empty(), ICal.summarise("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n").uid);
    }
}
