package peergos.server.webdav.caldav;

import java.time.*;
import java.util.*;
import java.util.stream.*;

/**
 * Just enough iCalendar to pick a storage shard for a PUT, find a UID, and answer a
 * time-range filter.
 *
 * Anything that cannot be read confidently is reported as absent, and callers treat
 * absent as "matches", so a calendar-query is never wrong by omission: an over-broad
 * result is legal because the client filters again, whereas a wrong exclusion silently
 * hides an event.
 */
public final class ICal {

    public static final class Property {
        public final String name;
        public final Map<String, String> params;
        public final String value;

        public Property(String name, Map<String, String> params, String value) {
            this.name = name;
            this.params = params;
            this.value = value;
        }

        public Optional<String> param(String name) {
            return Optional.ofNullable(params.get(name.toUpperCase()));
        }
    }

    public static final class Component {
        public final String name;
        public final List<Property> properties;
        public final List<Component> children;

        public Component(String name, List<Property> properties, List<Component> children) {
            this.name = name;
            this.properties = properties;
            this.children = children;
        }

        public Optional<Property> property(String name) {
            return properties.stream().filter(p -> p.name.equals(name)).findFirst();
        }

        public Optional<String> value(String name) {
            return property(name).map(p -> p.value);
        }

        public List<Component> children(String name) {
            return children.stream().filter(c -> c.name.equals(name)).collect(Collectors.toList());
        }

        /** The components a CalDAV comp-filter can name inside a VCALENDAR. */
        public List<Component> scheduleComponents() {
            return children.stream()
                    .filter(c -> ! c.name.equals("VTIMEZONE"))
                    .collect(Collectors.toList());
        }
    }

    /** Everything the bridge needs to know about one calendar object. */
    public static final class Summary {
        public final Optional<String> uid;
        /** The component type of the first non-VTIMEZONE component, e.g. VEVENT. */
        public final Optional<String> componentType;
        /** True if any component carries an RRULE or RDATE, so it belongs in recurring/. */
        public final boolean recurring;
        /** Absent when the start could not be read, or when the object recurs without end. */
        public final Optional<Instant> start;
        public final Optional<Instant> end;

        public Summary(Optional<String> uid,
                       Optional<String> componentType,
                       boolean recurring,
                       Optional<Instant> start,
                       Optional<Instant> end) {
            this.uid = uid;
            this.componentType = componentType;
            this.recurring = recurring;
            this.start = start;
            this.end = end;
        }

        /**
         * Conservative overlap test: anything we could not parse, and anything that
         * recurs, is reported as overlapping.
         */
        public boolean overlaps(Optional<Instant> rangeStart, Optional<Instant> rangeEnd) {
            if (recurring || start.isEmpty())
                return true;
            Instant from = start.get();
            Instant to = end.orElse(from);
            if (rangeEnd.isPresent() && ! from.isBefore(rangeEnd.get()))
                return false;
            if (rangeStart.isPresent() && to.isBefore(rangeStart.get()))
                return false;
            return true;
        }
    }

    private ICal() {}

    /** Undoes RFC 5545 line folding: a CRLF followed by a space or tab is a continuation. */
    public static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String raw : ics.split("\r\n|\n|\r")) {
            if (! raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t')) {
                current.append(raw, 1, raw.length());
            } else {
                if (current.length() > 0)
                    lines.add(current.toString());
                current = new StringBuilder(raw);
            }
        }
        if (current.length() > 0)
            lines.add(current.toString());
        return lines;
    }

    /** Parses the outermost VCALENDAR, or empty if the bytes are not iCalendar at all. */
    public static Optional<Component> parse(String ics) {
        Deque<String> names = new ArrayDeque<>();
        Deque<List<Property>> properties = new ArrayDeque<>();
        Deque<List<Component>> children = new ArrayDeque<>();
        Component outermost = null;
        for (String line : unfold(ics)) {
            Optional<Property> parsed = parseLine(line);
            if (parsed.isEmpty())
                continue;
            Property p = parsed.get();
            if (p.name.equals("BEGIN")) {
                names.push(p.value.toUpperCase());
                properties.push(new ArrayList<>());
                children.push(new ArrayList<>());
            } else if (p.name.equals("END")) {
                if (names.isEmpty() || ! names.peek().equals(p.value.toUpperCase()))
                    return Optional.empty();
                Component done = new Component(names.pop(), properties.pop(), children.pop());
                if (children.isEmpty())
                    outermost = done;
                else
                    children.peek().add(done);
            } else if (! properties.isEmpty()) {
                properties.peek().add(p);
            }
        }
        return names.isEmpty() ? Optional.ofNullable(outermost) : Optional.empty();
    }

    /** name[;param=value]*:value, where a colon inside a quoted parameter is not the separator. */
    private static Optional<Property> parseLine(String line) {
        boolean quoted = false;
        int colon = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"')
                quoted = ! quoted;
            else if (c == ':' && ! quoted) {
                colon = i;
                break;
            }
        }
        if (colon < 0)
            return Optional.empty();
        String value = line.substring(colon + 1);
        String[] nameAndParams = splitParams(line.substring(0, colon));
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 1; i < nameAndParams.length; i++) {
            String param = nameAndParams[i];
            int eq = param.indexOf('=');
            if (eq < 0)
                continue;
            String pv = param.substring(eq + 1);
            if (pv.length() > 1 && pv.charAt(0) == '"' && pv.endsWith("\""))
                pv = pv.substring(1, pv.length() - 1);
            params.put(param.substring(0, eq).trim().toUpperCase(), pv);
        }
        return Optional.of(new Property(nameAndParams[0].trim().toUpperCase(), params, value));
    }

    private static String[] splitParams(String nameAndParams) {
        List<String> parts = new ArrayList<>();
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < nameAndParams.length(); i++) {
            char c = nameAndParams.charAt(i);
            if (c == '"')
                quoted = ! quoted;
            else if (c == ';' && ! quoted) {
                parts.add(nameAndParams.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(nameAndParams.substring(start));
        return parts.toArray(new String[0]);
    }

    public static Summary summarise(String ics) {
        return parse(ics).map(ICal::summarise).orElseGet(() ->
                new Summary(Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty()));
    }

    public static Summary summarise(Component vcalendar) {
        List<Component> parts = vcalendar.scheduleComponents();
        if (parts.isEmpty())
            return new Summary(Optional.empty(), Optional.empty(), false, Optional.empty(), Optional.empty());
        boolean recurring = parts.stream()
                .anyMatch(c -> c.property("RRULE").isPresent() || c.property("RDATE").isPresent());
        Component first = parts.get(0);
        Optional<Instant> start = first.property("DTSTART").flatMap(ICal::toInstant);
        Optional<Instant> end = first.property("DTEND").flatMap(ICal::toInstant)
                .or(() -> first.property("DUE").flatMap(ICal::toInstant))
                .or(() -> start.flatMap(s -> first.value("DURATION")
                        .flatMap(ICal::parseDuration)
                        .map(s::plus)));
        return new Summary(first.value("UID"), Optional.of(first.name), recurring, start, end);
    }

    /**
     * The UTC year and month a non-recurring event is stored under, matching what the web
     * calendar app writes: it takes the UTC calendar fields of the event start, and names
     * the month 1-based and unpadded.
     */
    public static Optional<YearMonth> shard(Summary summary) {
        return summary.start.map(i -> YearMonth.from(i.atZone(ZoneOffset.UTC)));
    }

    /**
     * DATE, DATE-TIME with a trailing Z, or a local DATE-TIME read in its TZID. A local
     * time with no TZID is floating; we read it as UTC, which is what the web app does.
     */
    public static Optional<Instant> toInstant(Property property) {
        String v = property.value.trim();
        ZoneId zone = property.param("TZID")
                .flatMap(ICal::zone)
                .orElse(ZoneOffset.UTC);
        try {
            if (v.length() == 8)
                return Optional.of(LocalDate.parse(v, java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
                        .atStartOfDay(zone).toInstant());
            boolean utc = v.endsWith("Z");
            if (utc) {
                v = v.substring(0, v.length() - 1);
                zone = ZoneOffset.UTC;
            }
            if (v.length() != 15 || v.charAt(8) != 'T')
                return Optional.empty();
            LocalDateTime local = LocalDateTime.of(
                    Integer.parseInt(v.substring(0, 4)),
                    Integer.parseInt(v.substring(4, 6)),
                    Integer.parseInt(v.substring(6, 8)),
                    Integer.parseInt(v.substring(9, 11)),
                    Integer.parseInt(v.substring(11, 13)),
                    Integer.parseInt(v.substring(13, 15)));
            return Optional.of(local.atZone(zone).toInstant());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<ZoneId> zone(String tzid) {
        try {
            return Optional.of(ZoneId.of(tzid));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** RFC 5545 durations, which unlike ISO 8601 allow weeks and disallow months. */
    public static Optional<Duration> parseDuration(String value) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?")
                .matcher(value.trim());
        if (! m.matches())
            return Optional.empty();
        Duration d = Duration.ZERO
                .plusDays(7L * number(m.group(2)) + number(m.group(3)))
                .plusHours(number(m.group(4)))
                .plusMinutes(number(m.group(5)))
                .plusSeconds(number(m.group(6)));
        return Optional.of("-".equals(m.group(1)) ? d.negated() : d);
    }

    private static long number(String group) {
        return group == null ? 0 : Long.parseLong(group);
    }
}
