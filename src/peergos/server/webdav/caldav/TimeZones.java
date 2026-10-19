package peergos.server.webdav.caldav;

import java.time.*;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.*;

/**
 * The time zones one calendar object names, resolved the way the web calendar app resolves
 * them: an IANA name as it is, then a Windows name (Outlook and Exchange write "W. Europe
 * Standard Time"), then an IANA name buried in a path (some clients write
 * "/mozilla.org/20050126_1/Europe/London"), and last the object's own VTIMEZONE.
 *
 * A TZID none of those place is unknown, and a time in it is read as floating, as the web
 * app does.
 */
public final class TimeZones {

    /** How far ahead a VTIMEZONE's rules are expanded, past any date a calendar holds. */
    private static final Instant HORIZON = Instant.parse("2100-01-01T00:00:00Z");
    private static final int MAX_TRANSITIONS = 5000;
    private static final List<String> REGIONS = List.of("Africa/", "America/", "Antarctica/", "Asia/",
            "Atlantic/", "Australia/", "Europe/", "Indian/", "Pacific/");

    private final Map<String, ICal.Component> defined;
    private final Map<String, Optional<Rules>> rules = new HashMap<>();

    private TimeZones(Map<String, ICal.Component> defined) {
        this.defined = defined;
    }

    public static TimeZones of(ICal.Component vcalendar) {
        Map<String, ICal.Component> defined = new HashMap<>();
        for (ICal.Component zone : vcalendar.children("VTIMEZONE"))
            zone.value("TZID").ifPresent(tzid -> defined.putIfAbsent(tzid, zone));
        return new TimeZones(defined);
    }

    public static TimeZones none() {
        return new TimeZones(Map.of());
    }

    /** Where a local time in this zone falls, or empty if the zone cannot be placed. */
    public Optional<Instant> instant(String tzid, LocalDateTime local) {
        Optional<ZoneId> named = named(tzid);
        if (named.isPresent())
            return Optional.of(local.atZone(named.get()).toInstant());
        Optional<Rules> own = rules(tzid);
        if (own.isEmpty())
            return Optional.empty();
        // Read the offset in force at the local time as if it were UTC, then again at the
        // instant that gives, which settles it everywhere but inside a changeover hour.
        ZoneOffset guess = offsetAt(own.get(), local.toInstant(ZoneOffset.UTC));
        return Optional.of(local.toInstant(offsetAt(own.get(), local.toInstant(guess))));
    }

    /**
     * An IANA zone for this TZID, for a consumer that only takes those, Android's calendar
     * among them. A zone the object defines for itself becomes the IANA zone that changes
     * offset at the same moments, preferring one whose city the TZID mentions.
     */
    public Optional<ZoneId> zone(String tzid) {
        Optional<ZoneId> named = named(tzid);
        if (named.isPresent())
            return named;
        Optional<Rules> own = rules(tzid);
        if (own.isEmpty())
            return Optional.empty();
        Instant from = Instant.now().minus(Duration.ofDays(366));
        Instant to = Instant.now().plus(Duration.ofDays(2 * 366));
        List<Transition> expected = within(own.get(), from, to);
        ZoneOffset initial = offsetAt(own.get(), from);
        String text = tzid.toLowerCase(Locale.ROOT);
        Optional<ZoneId> first = Optional.empty();
        for (String id : candidates()) {
            ZoneRules rules = ZoneId.of(id).getRules();
            if (! rules.getOffset(from).equals(initial) || ! transitionsOf(rules, from, to).equals(expected))
                continue;
            String city = id.substring(id.lastIndexOf('/') + 1).replace('_', ' ').toLowerCase(Locale.ROOT);
            if (text.contains(city))
                return Optional.of(ZoneId.of(id));
            if (first.isEmpty())
                first = Optional.of(ZoneId.of(id));
        }
        return first;
    }

    /** A TZID that names an IANA zone, directly or by one of the names other clients use for one. */
    private static Optional<ZoneId> named(String tzid) {
        String trimmed = tzid.trim();
        Optional<ZoneId> direct = iana(trimmed);
        if (direct.isPresent())
            return direct;
        String windows = WINDOWS.get(trimmed);
        if (windows != null)
            return iana(windows);
        // A path ending in an IANA name: try each tail that starts after a slash.
        for (int slash = trimmed.indexOf('/'); slash >= 0; slash = trimmed.indexOf('/', slash + 1)) {
            String tail = trimmed.substring(slash + 1);
            if (tail.indexOf('/') > 0) {
                Optional<ZoneId> found = iana(tail);
                if (found.isPresent())
                    return found;
            }
        }
        return Optional.empty();
    }

    private static Optional<ZoneId> iana(String name) {
        // ZoneId.of also takes offsets like "+02:00", which are not zone names a TZID means.
        if (name.isEmpty() || name.charAt(0) == '+' || name.charAt(0) == '-')
            return Optional.empty();
        try {
            return Optional.of(ZoneId.of(name));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private record Transition(Instant at, ZoneOffset after) {}

    /** A VTIMEZONE as the offset it starts at and every change from there. */
    private record Rules(ZoneOffset initial, List<Transition> transitions) {}

    private Optional<Rules> rules(String tzid) {
        return rules.computeIfAbsent(tzid, id -> Optional.ofNullable(defined.get(id)).flatMap(TimeZones::expand));
    }

    /** Every change of offset a VTIMEZONE describes up to the horizon, in order. */
    private static Optional<Rules> expand(ICal.Component zone) {
        List<Transition> all = new ArrayList<>();
        Map<Instant, ZoneOffset> before = new HashMap<>();
        for (ICal.Component observance : zone.children) {
            if (! observance.name.equals("STANDARD") && ! observance.name.equals("DAYLIGHT"))
                continue;
            Optional<ZoneOffset> from = observance.value("TZOFFSETFROM").flatMap(TimeZones::offset);
            Optional<ZoneOffset> to = observance.value("TZOFFSETTO").flatMap(TimeZones::offset);
            Optional<LocalDateTime> start = observance.value("DTSTART").flatMap(ICal::localDateTime);
            if (from.isEmpty() || to.isEmpty() || start.isEmpty())
                continue;
            List<LocalDateTime> onsets = new ArrayList<>(List.of(start.get()));
            Optional<Recurrence> rule = observance.value("RRULE").flatMap(Recurrence::parse);
            if (rule.isPresent())
                onsets = rule.get().expand(start.get(), local -> local.toInstant(from.get()), HORIZON, MAX_TRANSITIONS);
            for (ICal.Property rdate : observance.properties) {
                if (! rdate.name.equals("RDATE"))
                    continue;
                for (String value : rdate.value.split(","))
                    ICal.localDateTime(value.trim()).ifPresent(onsets::add);
            }
            for (LocalDateTime onset : onsets) {
                Instant at = onset.toInstant(from.get());
                all.add(new Transition(at, to.get()));
                before.put(at, from.get());
            }
            if (all.size() > MAX_TRANSITIONS)
                return Optional.empty();
        }
        if (all.isEmpty())
            return Optional.empty();
        all.sort(Comparator.comparing(Transition::at));
        return Optional.of(new Rules(before.get(all.get(0).at()), all));
    }

    /** The offset in force at an instant: what the last change before it changed to. */
    private static ZoneOffset offsetAt(Rules rules, Instant at) {
        ZoneOffset offset = rules.initial();
        for (Transition t : rules.transitions()) {
            if (t.at().isAfter(at))
                break;
            offset = t.after();
        }
        return offset;
    }

    /** The changes that actually change the offset, between two instants. */
    private static List<Transition> within(Rules rules, Instant from, Instant to) {
        List<Transition> changes = new ArrayList<>();
        ZoneOffset current = offsetAt(rules, from);
        for (Transition t : rules.transitions()) {
            if (! t.at().isAfter(from) || ! t.at().isBefore(to))
                continue;
            if (! t.after().equals(current))
                changes.add(t);
            current = t.after();
        }
        return changes;
    }

    private static List<Transition> transitionsOf(ZoneRules rules, Instant from, Instant to) {
        List<Transition> changes = new ArrayList<>();
        for (ZoneOffsetTransition t = rules.nextTransition(from); t != null && t.getInstant().isBefore(to);
             t = rules.nextTransition(t.getInstant()))
            changes.add(new Transition(t.getInstant(), t.getOffsetAfter()));
        return changes;
    }

    /** Region zones first, in a fixed order, then the fixed-offset Etc ones. */
    private static List<String> candidates() {
        List<String> regions = new ArrayList<>();
        List<String> fixed = new ArrayList<>();
        for (String id : new TreeSet<>(ZoneId.getAvailableZoneIds())) {
            if (REGIONS.stream().anyMatch(id::startsWith))
                regions.add(id);
            else if (id.startsWith("Etc/"))
                fixed.add(id);
        }
        regions.addAll(fixed);
        return regions;
    }

    /** TZOFFSETFROM and TZOFFSETTO: +HHMM, -HHMM, or with seconds. */
    private static Optional<ZoneOffset> offset(String value) {
        String v = value.trim();
        try {
            if (v.length() != 5 && v.length() != 7)
                return Optional.empty();
            int sign = v.charAt(0) == '-' ? -1 : 1;
            int hours = Integer.parseInt(v.substring(1, 3));
            int minutes = Integer.parseInt(v.substring(3, 5));
            int seconds = v.length() == 7 ? Integer.parseInt(v.substring(5, 7)) : 0;
            return Optional.of(ZoneOffset.ofHoursMinutesSeconds(sign * hours, sign * minutes, sign * seconds));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Windows zone names to IANA, the default zone of each per CLDR territory "001". */
    private static final Map<String, String> WINDOWS = new HashMap<>();
    static {
        String[] pairs = {
                "Dateline Standard Time", "Etc/GMT+12", "UTC-11", "Etc/GMT+11",
                "Aleutian Standard Time", "America/Adak", "Hawaiian Standard Time", "Pacific/Honolulu",
                "Marquesas Standard Time", "Pacific/Marquesas", "Alaskan Standard Time", "America/Anchorage",
                "UTC-09", "Etc/GMT+9", "Pacific Standard Time (Mexico)", "America/Tijuana",
                "UTC-08", "Etc/GMT+8", "Pacific Standard Time", "America/Los_Angeles",
                "US Mountain Standard Time", "America/Phoenix", "Mountain Standard Time (Mexico)", "America/Chihuahua",
                "Mountain Standard Time", "America/Denver", "Central America Standard Time", "America/Guatemala",
                "Central Standard Time", "America/Chicago", "Easter Island Standard Time", "Pacific/Easter",
                "Central Standard Time (Mexico)", "America/Mexico_City", "Canada Central Standard Time", "America/Regina",
                "SA Pacific Standard Time", "America/Bogota", "Eastern Standard Time (Mexico)", "America/Cancun",
                "Eastern Standard Time", "America/New_York", "Haiti Standard Time", "America/Port-au-Prince",
                "Cuba Standard Time", "America/Havana", "US Eastern Standard Time", "America/Indianapolis",
                "Turks And Caicos Standard Time", "America/Grand_Turk", "Paraguay Standard Time", "America/Asuncion",
                "Atlantic Standard Time", "America/Halifax", "Venezuela Standard Time", "America/Caracas",
                "Central Brazilian Standard Time", "America/Cuiaba", "SA Western Standard Time", "America/La_Paz",
                "Pacific SA Standard Time", "America/Santiago", "Newfoundland Standard Time", "America/St_Johns",
                "Tocantins Standard Time", "America/Araguaina", "E. South America Standard Time", "America/Sao_Paulo",
                "SA Eastern Standard Time", "America/Cayenne", "Argentina Standard Time", "America/Buenos_Aires",
                "Greenland Standard Time", "America/Godthab", "Montevideo Standard Time", "America/Montevideo",
                "Magallanes Standard Time", "America/Punta_Arenas", "Saint Pierre Standard Time", "America/Miquelon",
                "Bahia Standard Time", "America/Bahia", "UTC-02", "Etc/GMT+2", "Mid-Atlantic Standard Time", "Etc/GMT+2",
                "Azores Standard Time", "Atlantic/Azores", "Cape Verde Standard Time", "Atlantic/Cape_Verde",
                "UTC", "Etc/UTC", "GMT Standard Time", "Europe/London", "Greenwich Standard Time", "Atlantic/Reykjavik",
                "Sao Tome Standard Time", "Africa/Sao_Tome", "Morocco Standard Time", "Africa/Casablanca",
                "W. Europe Standard Time", "Europe/Berlin", "Central Europe Standard Time", "Europe/Budapest",
                "Romance Standard Time", "Europe/Paris", "Central European Standard Time", "Europe/Warsaw",
                "W. Central Africa Standard Time", "Africa/Lagos", "Jordan Standard Time", "Asia/Amman",
                "GTB Standard Time", "Europe/Bucharest", "Middle East Standard Time", "Asia/Beirut",
                "Egypt Standard Time", "Africa/Cairo", "E. Europe Standard Time", "Europe/Chisinau",
                "Syria Standard Time", "Asia/Damascus", "West Bank Standard Time", "Asia/Hebron",
                "South Africa Standard Time", "Africa/Johannesburg", "FLE Standard Time", "Europe/Kiev",
                "Israel Standard Time", "Asia/Jerusalem", "Kaliningrad Standard Time", "Europe/Kaliningrad",
                "Sudan Standard Time", "Africa/Khartoum", "Libya Standard Time", "Africa/Tripoli",
                "Namibia Standard Time", "Africa/Windhoek", "Arabic Standard Time", "Asia/Baghdad",
                "Turkey Standard Time", "Europe/Istanbul", "Arab Standard Time", "Asia/Riyadh",
                "Belarus Standard Time", "Europe/Minsk", "Russian Standard Time", "Europe/Moscow",
                "E. Africa Standard Time", "Africa/Nairobi", "Iran Standard Time", "Asia/Tehran",
                "Arabian Standard Time", "Asia/Dubai", "Astrakhan Standard Time", "Europe/Astrakhan",
                "Azerbaijan Standard Time", "Asia/Baku", "Russia Time Zone 3", "Europe/Samara",
                "Mauritius Standard Time", "Indian/Mauritius", "Saratov Standard Time", "Europe/Saratov",
                "Georgian Standard Time", "Asia/Tbilisi", "Volgograd Standard Time", "Europe/Volgograd",
                "Caucasus Standard Time", "Asia/Yerevan", "Afghanistan Standard Time", "Asia/Kabul",
                "West Asia Standard Time", "Asia/Tashkent", "Ekaterinburg Standard Time", "Asia/Yekaterinburg",
                "Pakistan Standard Time", "Asia/Karachi", "Qyzylorda Standard Time", "Asia/Qyzylorda",
                "India Standard Time", "Asia/Calcutta", "Sri Lanka Standard Time", "Asia/Colombo",
                "Nepal Standard Time", "Asia/Katmandu", "Central Asia Standard Time", "Asia/Almaty",
                "Bangladesh Standard Time", "Asia/Dhaka", "Omsk Standard Time", "Asia/Omsk",
                "Myanmar Standard Time", "Asia/Rangoon", "SE Asia Standard Time", "Asia/Bangkok",
                "Altai Standard Time", "Asia/Barnaul", "W. Mongolia Standard Time", "Asia/Hovd",
                "Novosibirsk Standard Time", "Asia/Novosibirsk", "Tomsk Standard Time", "Asia/Tomsk",
                "China Standard Time", "Asia/Shanghai", "North Asia Standard Time", "Asia/Krasnoyarsk",
                "Singapore Standard Time", "Asia/Singapore", "W. Australia Standard Time", "Australia/Perth",
                "Taipei Standard Time", "Asia/Taipei", "Ulaanbaatar Standard Time", "Asia/Ulaanbaatar",
                "Aus Central W. Standard Time", "Australia/Eucla", "Transbaikal Standard Time", "Asia/Chita",
                "Tokyo Standard Time", "Asia/Tokyo", "North Korea Standard Time", "Asia/Pyongyang",
                "Korea Standard Time", "Asia/Seoul", "Yakutsk Standard Time", "Asia/Yakutsk",
                "Cen. Australia Standard Time", "Australia/Adelaide", "AUS Central Standard Time", "Australia/Darwin",
                "E. Australia Standard Time", "Australia/Brisbane", "AUS Eastern Standard Time", "Australia/Sydney",
                "West Pacific Standard Time", "Pacific/Port_Moresby", "Tasmania Standard Time", "Australia/Hobart",
                "Vladivostok Standard Time", "Asia/Vladivostok", "Lord Howe Standard Time", "Australia/Lord_Howe",
                "Bougainville Standard Time", "Pacific/Bougainville", "Russia Time Zone 10", "Asia/Srednekolymsk",
                "Magadan Standard Time", "Asia/Magadan", "Norfolk Standard Time", "Pacific/Norfolk",
                "Sakhalin Standard Time", "Asia/Sakhalin", "Central Pacific Standard Time", "Pacific/Guadalcanal",
                "Russia Time Zone 11", "Asia/Kamchatka", "New Zealand Standard Time", "Pacific/Auckland",
                "UTC+12", "Etc/GMT-12", "Fiji Standard Time", "Pacific/Fiji", "Kamchatka Standard Time", "Asia/Kamchatka",
                "Chatham Islands Standard Time", "Pacific/Chatham", "UTC+13", "Etc/GMT-13",
                "Tonga Standard Time", "Pacific/Tongatapu", "Samoa Standard Time", "Pacific/Apia",
                "Line Islands Standard Time", "Pacific/Kiritimati"
        };
        for (int i = 0; i < pairs.length; i += 2)
            WINDOWS.put(pairs[i], pairs[i + 1]);
    }
}
