package peergos.server.webdav.caldav;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;

/**
 * RFC 4791 free-busy-query: the times a calendar's events take up within a range, as one
 * VFREEBUSY, which is how a client shows when someone is free without being shown what
 * they are doing.
 *
 * Every occurrence of a series counts, less the ones it excludes, with an occurrence the
 * same file overrides taken from the override instead. An event marked TRANSPARENT (free)
 * or CANCELLED takes no time; a TENTATIVE one is BUSY-TENTATIVE. With no zone of its own,
 * the server places a floating time in UTC.
 */
public final class FreeBusy {

    /** However many occurrences of one series are looked at, so a daily rule that never ends stays cheap. */
    private static final int MAX_OCCURRENCES = 20_000;
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    public record Period(Instant start, Instant end) {}

    private FreeBusy() {}

    /** The VCALENDAR answering a free-busy-query over these calendar objects. */
    public static String report(List<String> objects, Instant from, Instant to, Instant now) {
        List<Period> busy = new ArrayList<>();
        List<Period> tentative = new ArrayList<>();
        for (String object : objects) {
            Optional<ICal.Component> parsed = ICal.parse(object);
            if (parsed.isEmpty())
                continue;
            collect(parsed.get(), from, to, busy, tentative);
        }
        StringBuilder out = new StringBuilder();
        out.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Peergos//CalDAV//EN\r\nBEGIN:VFREEBUSY\r\n");
        out.append("DTSTAMP:").append(UTC.format(now)).append("\r\n");
        out.append("DTSTART:").append(UTC.format(from)).append("\r\n");
        out.append("DTEND:").append(UTC.format(to)).append("\r\n");
        for (Period p : merge(busy, from, to))
            out.append("FREEBUSY;FBTYPE=BUSY:").append(UTC.format(p.start())).append('/').append(UTC.format(p.end())).append("\r\n");
        for (Period p : merge(tentative, from, to))
            out.append("FREEBUSY;FBTYPE=BUSY-TENTATIVE:").append(UTC.format(p.start())).append('/').append(UTC.format(p.end())).append("\r\n");
        out.append("END:VFREEBUSY\r\nEND:VCALENDAR\r\n");
        return out.toString();
    }

    /** The busy times one calendar object holds, sorted into BUSY and BUSY-TENTATIVE. */
    static void collect(ICal.Component vcalendar, Instant from, Instant to, List<Period> busy, List<Period> tentative) {
        TimeZones zones = TimeZones.of(vcalendar);
        for (ICal.Component published : vcalendar.children("VFREEBUSY"))
            published(published, zones, busy, tentative);

        List<ICal.Component> events = vcalendar.children("VEVENT");
        Optional<ICal.Component> master = events.stream().filter(e -> e.property("RECURRENCE-ID").isEmpty()).findFirst();
        Set<Instant> overridden = new HashSet<>();
        for (ICal.Component event : events) {
            Optional<Instant> instance = event.property("RECURRENCE-ID").flatMap(p -> instant(p, zones));
            if (instance.isEmpty())
                continue;
            overridden.add(instance.get());
            occurrences(event, zones, false, to).forEach(p -> add(event, p, from, busy, tentative));
        }
        if (master.isEmpty())
            return;
        ICal.Component event = master.get();
        Set<Instant> excluded = new HashSet<>(overridden);
        for (ICal.Property exdate : event.properties)
            if (exdate.name.equals("EXDATE"))
                excluded.addAll(values(exdate, zones));
        boolean recurring = event.property("RRULE").isPresent() || event.property("RDATE").isPresent();
        for (Period occurrence : occurrences(event, zones, recurring, to))
            if (! excluded.contains(occurrence.start()))
                add(event, occurrence, from, busy, tentative);
    }

    private static void add(ICal.Component event, Period occurrence, Instant from, List<Period> busy, List<Period> tentative) {
        if (! occurrence.end().isAfter(from) || ! occurrence.end().isAfter(occurrence.start()))
            return;
        if (event.value("TRANSP").map(t -> t.trim().equalsIgnoreCase("TRANSPARENT")).orElse(false))
            return;
        String status = event.value("STATUS").map(s -> s.trim().toUpperCase(Locale.ROOT)).orElse("");
        if (status.equals("CANCELLED"))
            return;
        (status.equals("TENTATIVE") ? tentative : busy).add(occurrence);
    }

    /** Every occurrence of an event that starts before {@code to}, or just the one if it does not recur. */
    private static List<Period> occurrences(ICal.Component event, TimeZones zones, boolean recurring, Instant to) {
        Optional<ICal.Property> dtstart = event.property("DTSTART");
        if (dtstart.isEmpty())
            return List.of();
        String value = dtstart.get().value.trim();
        boolean allDay = value.length() == 8;
        Optional<LocalDateTime> start = allDay
                ? Optional.of(LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay())
                : ICal.localDateTime(value.endsWith("Z") ? value.substring(0, value.length() - 1) : value);
        if (start.isEmpty())
            return List.of();
        Optional<String> tzid = value.endsWith("Z") || allDay ? Optional.empty() : dtstart.get().param("TZID");
        Function<LocalDateTime, Instant> place = local -> tzid.flatMap(z -> zones.instant(z, local))
                .orElse(local.toInstant(ZoneOffset.UTC));
        Duration length = length(event, start.get(), place, allDay, zones);

        List<LocalDateTime> starts = new ArrayList<>();
        if (! recurring) {
            starts.add(start.get());
        } else {
            Optional<Recurrence> rule = event.value("RRULE").flatMap(Recurrence::parse);
            if (rule.isPresent())
                starts.addAll(rule.get().expand(start.get(), place, to, MAX_OCCURRENCES));
            else
                starts.add(start.get());
        }
        List<Period> periods = new ArrayList<>();
        for (LocalDateTime local : starts)
            periods.add(new Period(place.apply(local), place.apply(local.plus(length))));
        if (recurring) {
            for (ICal.Property rdate : event.properties) {
                if (! rdate.name.equals("RDATE"))
                    continue;
                for (String item : rdate.value.split(",")) {
                    String v = item.trim();
                    int slash = v.indexOf('/');
                    ICal.Property one = new ICal.Property("RDATE", rdate.params, slash < 0 ? v : v.substring(0, slash));
                    Optional<Instant> at = instant(one, zones);
                    if (at.isEmpty())
                        continue;
                    if (slash < 0) {
                        periods.add(new Period(at.get(), at.get().plus(Duration.between(place.apply(start.get()),
                                place.apply(start.get().plus(length))))));
                        continue;
                    }
                    // A PERIOD value: start/end or start/duration.
                    String rest = v.substring(slash + 1);
                    Optional<Instant> end = rest.startsWith("P") || rest.startsWith("+P") || rest.startsWith("-P")
                            ? ICal.parseDuration(rest).map(at.get()::plus)
                            : instant(new ICal.Property("RDATE", rdate.params, rest), zones);
                    end.ifPresent(e -> periods.add(new Period(at.get(), e)));
                }
            }
        }
        return periods;
    }

    /** How long each occurrence lasts on the wall clock: DTEND less DTSTART, or DURATION, or a day for an all-day event. */
    private static Duration length(ICal.Component event, LocalDateTime start, Function<LocalDateTime, Instant> place,
                                   boolean allDay, TimeZones zones) {
        Optional<ICal.Property> dtend = event.property("DTEND");
        if (dtend.isPresent()) {
            String v = dtend.get().value.trim();
            if (v.length() == 8)
                return Duration.between(start, LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE).atStartOfDay());
            Optional<Instant> end = instant(dtend.get(), zones);
            if (end.isPresent()) {
                Duration exact = Duration.between(place.apply(start), end.get());
                // An end in the start's own zone keeps the same wall clock length on every
                // occurrence; one in another zone keeps the same elapsed time.
                Optional<LocalDateTime> local = ICal.localDateTime(v);
                boolean sameZone = local.isPresent() && dtend.get().param("TZID").equals(event.property("DTSTART").get().param("TZID"));
                return sameZone ? Duration.between(start, local.get()) : exact;
            }
        }
        Optional<Duration> duration = event.value("DURATION").flatMap(ICal::parseDuration);
        if (duration.isPresent())
            return duration.get();
        return allDay ? Duration.ofDays(1) : Duration.ZERO;
    }

    /** A VFREEBUSY a client stored, which already says when its owner is busy. */
    private static void published(ICal.Component vfreebusy, TimeZones zones, List<Period> busy, List<Period> tentative) {
        for (ICal.Property line : vfreebusy.properties) {
            if (! line.name.equals("FREEBUSY"))
                continue;
            String type = line.param("FBTYPE").orElse("BUSY").toUpperCase(Locale.ROOT);
            if (type.equals("FREE"))
                continue;
            for (String item : line.value.split(",")) {
                String[] parts = item.trim().split("/");
                if (parts.length != 2)
                    continue;
                Optional<Instant> start = instant(new ICal.Property("FREEBUSY", Map.of(), parts[0]), zones);
                Optional<Instant> end = parts[1].contains("P")
                        ? start.flatMap(s -> ICal.parseDuration(parts[1]).map(s::plus))
                        : instant(new ICal.Property("FREEBUSY", Map.of(), parts[1]), zones);
                if (start.isPresent() && end.isPresent())
                    (type.equals("BUSY-TENTATIVE") ? tentative : busy).add(new Period(start.get(), end.get()));
            }
        }
    }

    private static Optional<Instant> instant(ICal.Property property, TimeZones zones) {
        return ICal.toInstant(property, zones, ZoneOffset.UTC);
    }

    /** Each value of a property that may list several, EXDATE among them. */
    private static List<Instant> values(ICal.Property property, TimeZones zones) {
        List<Instant> instants = new ArrayList<>();
        for (String item : property.value.split(","))
            instant(new ICal.Property(property.name, property.params, item.trim()), zones).ifPresent(instants::add);
        return instants;
    }

    /** Periods clipped to the range, sorted, with overlapping and touching ones joined. */
    static List<Period> merge(List<Period> periods, Instant from, Instant to) {
        List<Period> clipped = new ArrayList<>();
        for (Period p : periods) {
            Instant start = p.start().isBefore(from) ? from : p.start();
            Instant end = p.end().isAfter(to) ? to : p.end();
            if (end.isAfter(start))
                clipped.add(new Period(start, end));
        }
        clipped.sort(Comparator.comparing(Period::start));
        List<Period> merged = new ArrayList<>();
        for (Period p : clipped) {
            if (! merged.isEmpty() && ! p.start().isAfter(merged.get(merged.size() - 1).end())) {
                Period last = merged.remove(merged.size() - 1);
                merged.add(new Period(last.start(), p.end().isAfter(last.end()) ? p.end() : last.end()));
            } else {
                merged.add(p);
            }
        }
        return merged;
    }
}
