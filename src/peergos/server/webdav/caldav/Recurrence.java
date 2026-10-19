package peergos.server.webdav.caldav;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.function.Function;

/**
 * An RFC 5545 RRULE, expanded over local date-times: a rule repeats on the wall clock of
 * its DTSTART's zone, so "every Monday at 9" stays at 9 across a daylight saving change.
 *
 * Covers the frequencies and parts calendar clients write for events and for VTIMEZONE
 * observances: DAILY to YEARLY, INTERVAL, COUNT, UNTIL, BYDAY with or without an ordinal,
 * BYMONTHDAY, BYMONTH, BYYEARDAY, BYSETPOS and WKST. A rule using anything else, an hourly
 * one or a BYWEEKNO, does not parse, so a caller can tell it was not understood rather
 * than be handed a wrong set of dates.
 */
public final class Recurrence {

    private enum Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** A BYDAY entry: a weekday, and which one of them in the month or year (0 for all). */
    private record Day(int ordinal, DayOfWeek day) {}

    /** However many periods a rule that never matches is tried for before giving up. */
    private static final int MAX_EMPTY_PERIODS = 10_000;

    private final Freq freq;
    private final int interval;
    private final Optional<Integer> count;
    private final Optional<String> until;
    private final List<Day> byDay;
    private final List<Integer> byMonthDay, byMonth, byYearDay, bySetPos;
    private final DayOfWeek weekStart;

    private Recurrence(Freq freq, int interval, Optional<Integer> count, Optional<String> until, List<Day> byDay,
                       List<Integer> byMonthDay, List<Integer> byMonth, List<Integer> byYearDay,
                       List<Integer> bySetPos, DayOfWeek weekStart) {
        this.freq = freq;
        this.interval = interval;
        this.count = count;
        this.until = until;
        this.byDay = byDay;
        this.byMonthDay = byMonthDay;
        this.byMonth = byMonth;
        this.byYearDay = byYearDay;
        this.bySetPos = bySetPos;
        this.weekStart = weekStart;
    }

    public static Optional<Recurrence> parse(String rule) {
        Map<String, String> parts = new HashMap<>();
        for (String part : rule.trim().split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0)
                parts.put(part.substring(0, eq).trim().toUpperCase(Locale.ROOT), part.substring(eq + 1).trim());
        }
        try {
            Freq freq = Freq.valueOf(parts.getOrDefault("FREQ", "").toUpperCase(Locale.ROOT));
            for (String unsupported : List.of("BYWEEKNO", "BYHOUR", "BYMINUTE", "BYSECOND"))
                if (parts.containsKey(unsupported))
                    return Optional.empty();
            int interval = parts.containsKey("INTERVAL") ? Integer.parseInt(parts.get("INTERVAL")) : 1;
            if (interval < 1)
                return Optional.empty();
            Optional<Integer> count = Optional.ofNullable(parts.get("COUNT")).map(Integer::parseInt);
            List<Day> byDay = new ArrayList<>();
            for (String code : list(parts.get("BYDAY"))) {
                String upper = code.toUpperCase(Locale.ROOT);
                DayOfWeek day = weekday(upper.substring(upper.length() - 2));
                String ordinal = upper.substring(0, upper.length() - 2);
                byDay.add(new Day(ordinal.isEmpty() || ordinal.equals("+") ? 0 : Integer.parseInt(ordinal), day));
            }
            DayOfWeek weekStart = parts.containsKey("WKST") ? weekday(parts.get("WKST").toUpperCase(Locale.ROOT)) : DayOfWeek.MONDAY;
            return Optional.of(new Recurrence(freq, interval, count, Optional.ofNullable(parts.get("UNTIL")), byDay,
                    numbers(parts.get("BYMONTHDAY")), numbers(parts.get("BYMONTH")), numbers(parts.get("BYYEARDAY")),
                    numbers(parts.get("BYSETPOS")), weekStart));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * The local start of every occurrence that begins before {@code end}, in order and at
     * most {@code limit} of them. DTSTART is always the first, as RFC 5545 has it, whether
     * or not the rule would itself pick that date. {@code instant} places a local time in
     * the event's zone, which is what an UNTIL in UTC is compared against.
     */
    public List<LocalDateTime> expand(LocalDateTime start, Function<LocalDateTime, Instant> instant, Instant end, int limit) {
        List<LocalDateTime> found = new ArrayList<>();
        if (! instant.apply(start).isBefore(end) || limit < 1)
            return found;
        found.add(start);
        int emptyPeriods = 0;
        for (long period = 0; ; period++) {
            List<LocalDate> dates = candidates(start.toLocalDate(), period);
            if (dates.isEmpty()) {
                if (++emptyPeriods > MAX_EMPTY_PERIODS)
                    return found;
                continue;
            }
            emptyPeriods = 0;
            for (LocalDate date : dates) {
                LocalDateTime occurrence = date.atTime(start.toLocalTime());
                if (! occurrence.isAfter(start))
                    continue;
                if (count.isPresent() && found.size() >= count.get())
                    return found;
                if (pastUntil(occurrence, instant) || ! instant.apply(occurrence).isBefore(end))
                    return found;
                found.add(occurrence);
                if (found.size() >= limit)
                    return found;
            }
        }
    }

    private boolean pastUntil(LocalDateTime occurrence, Function<LocalDateTime, Instant> instant) {
        if (until.isEmpty())
            return false;
        String value = until.get();
        if (value.length() == 8)
            return occurrence.toLocalDate().isAfter(LocalDate.parse(value, java.time.format.DateTimeFormatter.BASIC_ISO_DATE));
        Optional<LocalDateTime> local = ICal.localDateTime(value.endsWith("Z") ? value.substring(0, value.length() - 1) : value);
        if (local.isEmpty())
            return false;
        if (value.endsWith("Z"))
            return instant.apply(occurrence).isAfter(local.get().toInstant(ZoneOffset.UTC));
        return occurrence.isAfter(local.get());
    }

    /** The dates the rule picks within the {@code period}th period after the one DTSTART is in. */
    private List<LocalDate> candidates(LocalDate start, long period) {
        long step = period * interval;
        List<LocalDate> dates = new ArrayList<>();
        switch (freq) {
            case DAILY: {
                LocalDate day = start.plusDays(step);
                if (matchesMonth(day) && matchesMonthDay(day) && matchesWeekday(day) && matchesYearDay(day))
                    dates.add(day);
                break;
            }
            case WEEKLY: {
                LocalDate first = start.with(TemporalAdjusters.previousOrSame(weekStart)).plusWeeks(step);
                for (int i = 0; i < 7; i++) {
                    LocalDate day = first.plusDays(i);
                    boolean picked = byDay.isEmpty() ? day.getDayOfWeek() == start.getDayOfWeek() : matchesWeekday(day);
                    if (picked && matchesMonth(day))
                        dates.add(day);
                }
                break;
            }
            case MONTHLY: {
                YearMonth month = YearMonth.from(start).plusMonths(step);
                if (byMonth.isEmpty() || byMonth.contains(month.getMonthValue()))
                    dates.addAll(inMonth(month, start));
                break;
            }
            case YEARLY: {
                int year = start.getYear() + (int) step;
                if (! byYearDay.isEmpty()) {
                    int length = Year.of(year).length();
                    for (int n : byYearDay) {
                        int day = n > 0 ? n : length + n + 1;
                        if (day < 1 || day > length)
                            continue;
                        LocalDate date = LocalDate.ofYearDay(year, day);
                        if (matchesMonth(date) && matchesMonthDay(date) && matchesWeekday(date))
                            dates.add(date);
                    }
                } else if (! byMonth.isEmpty() || ! byMonthDay.isEmpty()) {
                    List<Integer> months = byMonth.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12) : byMonth;
                    for (int m : months)
                        if (m >= 1 && m <= 12)
                            dates.addAll(inMonth(YearMonth.of(year, m), start));
                } else if (! byDay.isEmpty()) {
                    dates.addAll(weekdaysIn(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)));
                } else {
                    YearMonth month = YearMonth.of(year, start.getMonth());
                    if (month.isValidDay(start.getDayOfMonth()))
                        dates.add(month.atDay(start.getDayOfMonth()));
                }
                break;
            }
        }
        Collections.sort(dates);
        List<LocalDate> distinct = new ArrayList<>(new LinkedHashSet<>(dates));
        return bySetPos.isEmpty() ? distinct : setPositions(distinct);
    }

    /** What a monthly rule, or a yearly one naming months, picks within one month. */
    private List<LocalDate> inMonth(YearMonth month, LocalDate start) {
        List<LocalDate> dates = new ArrayList<>();
        if (! byMonthDay.isEmpty()) {
            for (int n : byMonthDay) {
                int day = n > 0 ? n : month.lengthOfMonth() + n + 1;
                if (day >= 1 && day <= month.lengthOfMonth() && matchesWeekday(month.atDay(day)))
                    dates.add(month.atDay(day));
            }
        } else if (! byDay.isEmpty()) {
            dates.addAll(weekdaysIn(month.atDay(1), month.atEndOfMonth()));
        } else if (month.isValidDay(start.getDayOfMonth())) {
            dates.add(month.atDay(start.getDayOfMonth()));
        }
        return dates;
    }

    /** The BYDAY days between two dates, an ordinal counting within that span. */
    private List<LocalDate> weekdaysIn(LocalDate first, LocalDate last) {
        List<LocalDate> dates = new ArrayList<>();
        for (Day day : byDay) {
            List<LocalDate> all = new ArrayList<>();
            for (LocalDate d = first.with(TemporalAdjusters.nextOrSame(day.day())); ! d.isAfter(last); d = d.plusWeeks(1))
                all.add(d);
            if (day.ordinal() == 0)
                dates.addAll(all);
            else {
                int index = day.ordinal() > 0 ? day.ordinal() - 1 : all.size() + day.ordinal();
                if (index >= 0 && index < all.size())
                    dates.add(all.get(index));
            }
        }
        return dates;
    }

    private List<LocalDate> setPositions(List<LocalDate> dates) {
        List<LocalDate> picked = new ArrayList<>();
        for (int n : bySetPos) {
            int index = n > 0 ? n - 1 : dates.size() + n;
            if (index >= 0 && index < dates.size())
                picked.add(dates.get(index));
        }
        Collections.sort(picked);
        return new ArrayList<>(new LinkedHashSet<>(picked));
    }

    private boolean matchesMonth(LocalDate day) {
        return byMonth.isEmpty() || byMonth.contains(day.getMonthValue());
    }

    private boolean matchesMonthDay(LocalDate day) {
        if (byMonthDay.isEmpty())
            return true;
        int length = day.lengthOfMonth();
        return byMonthDay.stream().anyMatch(n -> (n > 0 ? n : length + n + 1) == day.getDayOfMonth());
    }

    private boolean matchesWeekday(LocalDate day) {
        return byDay.isEmpty() || byDay.stream().anyMatch(d -> d.day() == day.getDayOfWeek());
    }

    private boolean matchesYearDay(LocalDate day) {
        if (byYearDay.isEmpty())
            return true;
        int length = day.lengthOfYear();
        return byYearDay.stream().anyMatch(n -> (n > 0 ? n : length + n + 1) == day.getDayOfYear());
    }

    private static List<String> list(String value) {
        if (value == null || value.isBlank())
            return List.of();
        List<String> items = new ArrayList<>();
        for (String item : value.split(","))
            if (! item.isBlank())
                items.add(item.trim());
        return items;
    }

    private static List<Integer> numbers(String value) {
        List<Integer> numbers = new ArrayList<>();
        for (String item : list(value)) {
            int n = Integer.parseInt(item.startsWith("+") ? item.substring(1) : item);
            if (n == 0)
                throw new IllegalArgumentException("0 is not a valid " + value);
            numbers.add(n);
        }
        return numbers;
    }

    private static DayOfWeek weekday(String code) {
        switch (code) {
            case "MO": return DayOfWeek.MONDAY;
            case "TU": return DayOfWeek.TUESDAY;
            case "WE": return DayOfWeek.WEDNESDAY;
            case "TH": return DayOfWeek.THURSDAY;
            case "FR": return DayOfWeek.FRIDAY;
            case "SA": return DayOfWeek.SATURDAY;
            case "SU": return DayOfWeek.SUNDAY;
            default: throw new IllegalArgumentException("Not a weekday: " + code);
        }
    }
}
