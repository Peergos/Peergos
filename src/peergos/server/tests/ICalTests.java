package peergos.server.tests;

import org.junit.Assert;
import org.junit.Test;
import peergos.server.webdav.caldav.CalendarStore;
import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.TimeZones;

import java.time.*;
import java.util.Optional;

public class ICalTests {

    private static String event(String... properties) {
        StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\n");
        for (String property : properties)
            ics.append(property).append("\r\n");
        return ics.append("END:VEVENT\r\nEND:VCALENDAR\r\n").toString();
    }

    private static String todo(String... properties) {
        StringBuilder ics = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\n");
        for (String property : properties)
            ics.append(property).append("\r\n");
        return ics.append("END:VTODO\r\nEND:VCALENDAR\r\n").toString();
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
    public void tasksAreShardedAwayFromTheWebApp() {
        // Being a task wins over recurring: recurring/ is read by the web calendar app,
        // which would try to draw a repeating task as an event.
        Assert.assertEquals(Optional.of(CalendarStore.TASKS_DIR),
                CalendarStore.shardFor(ICal.summarise(todo("UID:t", "DUE:20240315T170000Z"))));
        Assert.assertEquals(Optional.of(CalendarStore.TASKS_DIR),
                CalendarStore.shardFor(ICal.summarise(todo("UID:t"))));
        Assert.assertEquals(Optional.of(CalendarStore.TASKS_DIR),
                CalendarStore.shardFor(ICal.summarise(todo("UID:t", "DTSTART:20240315T090000Z", "RRULE:FREQ=WEEKLY"))));
        // Events are untouched by any of this.
        Assert.assertEquals(Optional.of("2024/3"),
                CalendarStore.shardFor(ICal.summarise(event("UID:e", "DTSTART:20240315T090000Z"))));
        Assert.assertEquals(Optional.of(CalendarStore.RECURRING_DIR),
                CalendarStore.shardFor(ICal.summarise(event("UID:e", "DTSTART:20240315T090000Z", "RRULE:FREQ=WEEKLY"))));
        // A dateless event still has nowhere to go, which is what makes the PUT a 403.
        Assert.assertEquals(Optional.empty(), CalendarStore.shardFor(ICal.summarise(event("UID:e"))));
    }

    @Test
    public void readsTasks() {
        ICal.Summary undated = ICal.summarise(todo("UID:t1", "SUMMARY:buy milk"));
        Assert.assertEquals(Optional.of("VTODO"), undated.componentType);
        Assert.assertEquals(Optional.of("t1"), undated.uid);
        // A task need carry no date at all, and one that does usually carries only a DUE.
        Assert.assertEquals(Optional.empty(), undated.start);
        Assert.assertEquals(Optional.empty(), undated.end);

        ICal.Summary due = ICal.summarise(todo("UID:t2", "DUE:20240315T170000Z"));
        Assert.assertEquals(Optional.of(Instant.parse("2024-03-15T17:00:00Z")), due.end);
        // With no start there is nothing to be sure about, so it matches every time range
        // rather than risk hiding a task from the client's list.
        Assert.assertTrue(due.overlaps(Optional.of(Instant.parse("2030-01-01T00:00:00Z")), Optional.empty()));
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

    private static final String BERLIN_RULES = "BEGIN:STANDARD\r\nDTSTART:16010101T030000\r\nTZOFFSETFROM:+0200\r\n"
            + "TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=10\r\nEND:STANDARD\r\n"
            + "BEGIN:DAYLIGHT\r\nDTSTART:16010101T020000\r\nTZOFFSETFROM:+0100\r\nTZOFFSETTO:+0200\r\n"
            + "RRULE:FREQ=YEARLY;INTERVAL=1;BYDAY=-1SU;BYMONTH=3\r\nEND:DAYLIGHT\r\n";

    /** As Outlook writes one: a zone of its own naming, defined in the file. */
    private static ICal.Component withOwnZone(String tzid, String start) {
        return ICal.parse("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTIMEZONE\r\nTZID:" + tzid + "\r\n" + BERLIN_RULES
                + "END:VTIMEZONE\r\nBEGIN:VEVENT\r\nUID:a\r\nDTSTART;TZID=\"" + tzid + "\":" + start + "\r\n"
                + "END:VEVENT\r\nEND:VCALENDAR\r\n").orElseThrow();
    }

    private static Optional<Instant> start(ICal.Component vcalendar, ZoneId floating) {
        return ICal.toInstant(vcalendar.scheduleComponents().get(0).property("DTSTART").get(),
                TimeZones.of(vcalendar), floating);
    }

    @Test
    public void readsAZoneTheFileDefines() {
        ICal.Component summer = withOwnZone("Customized Time Zone", "20260715T090000");
        ICal.Component winter = withOwnZone("Customized Time Zone", "20260115T090000");
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T07:00:00Z")), start(summer, ZoneOffset.UTC));
        Assert.assertEquals(Optional.of(Instant.parse("2026-01-15T08:00:00Z")), start(winter, ZoneOffset.UTC));
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T07:00:00Z")), ICal.summarise(summer).start);
        // The changes are on the same days as Berlin's, so whatever zone it is matched with does too.
        ZoneId matched = TimeZones.of(summer).zone("Customized Time Zone").orElseThrow();
        Assert.assertEquals(ZoneId.of("Europe/Berlin").getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z")),
                matched.getRules().nextTransition(Instant.parse("2026-01-01T00:00:00Z")));
    }

    @Test
    public void aZoneTheFileDefinesIsMatchedToACityItNames() {
        String tzid = "(UTC+01:00) Amsterdam, Berlin, Bern, Rome, Stockholm, Vienna";
        Assert.assertEquals(Optional.of(ZoneId.of("Europe/Amsterdam")),
                TimeZones.of(withOwnZone(tzid, "20260715T090000")).zone(tzid));
    }

    @Test
    public void readsWindowsAndPathZoneNames() {
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T07:00:00Z")),
                ICal.summarise(event("UID:a", "DTSTART;TZID=W. Europe Standard Time:20260715T090000")).start);
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T13:00:00Z")),
                ICal.summarise(event("UID:a", "DTSTART;TZID=/mozilla.org/20050126_1/America/New_York:20260715T090000")).start);
        Assert.assertEquals(Optional.of(ZoneId.of("Europe/Berlin")), TimeZones.none().zone("W. Europe Standard Time"));
        Assert.assertEquals(Optional.empty(), TimeZones.none().zone("+02:00"));
    }

    @Test
    public void floatingTimesAreReadInTheZoneAskedFor() {
        ICal.Component floating = ICal.parse(event("UID:a", "DTSTART:20260715T090000")).orElseThrow();
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T00:00:00Z")), start(floating, ZoneId.of("Asia/Tokyo")));
        Assert.assertTrue(ICal.isFloating(floating.scheduleComponents().get(0).property("DTSTART").get()));
        // A zone nothing can place is floating too, as it is in the web app.
        ICal.Component unknown = ICal.parse(event("UID:a", "DTSTART;TZID=Nowhere:20260715T090000")).orElseThrow();
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T00:00:00Z")), start(unknown, ZoneId.of("Asia/Tokyo")));
        // A date is an all-day event's UTC midnight wherever the viewer is.
        ICal.Component date = ICal.parse(event("UID:a", "DTSTART;VALUE=DATE:20260715")).orElseThrow();
        Assert.assertEquals(Optional.of(Instant.parse("2026-07-15T00:00:00Z")), start(date, ZoneId.of("Asia/Tokyo")));
    }

    @Test
    public void aFloatingEventMatchesARangeItCouldFallInAnywhere() {
        // 23:00 floating is the next day in UTC for anyone west of it.
        ICal.Summary late = ICal.summarise(event("UID:a", "DTSTART:20260715T230000", "DTEND:20260715T233000"));
        Assert.assertTrue(late.floating);
        Assert.assertTrue(late.overlaps(Optional.of(Instant.parse("2026-07-16T00:00:00Z")),
                Optional.of(Instant.parse("2026-07-17T00:00:00Z"))));
        Assert.assertFalse(late.overlaps(Optional.of(Instant.parse("2026-07-18T00:00:00Z")), Optional.empty()));
    }
}
