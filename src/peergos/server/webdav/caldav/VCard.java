package peergos.server.webdav.caldav;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Just enough vCard to recognise one, match a property against a CardDAV filter, and read
 * a property apart into the pieces a contact is made of.
 *
 * vCard shares iCalendar's content-line grammar, so the folding and the name/params/value
 * split are reused from {@link ICal} rather than written twice. As there, anything that
 * cannot be read confidently widens the result rather than narrowing it.
 */
public final class VCard {

    private VCard() {}

    public static boolean isVCard(String text) {
        return ICal.unfold(text).stream().anyMatch(l -> l.trim().toUpperCase(Locale.ROOT).startsWith("BEGIN:VCARD"));
    }

    /** The UID, which clients normally also use as the file name. */
    public static Optional<String> uid(String text) {
        return values(text, "UID").stream().findFirst();
    }

    /** Every property in the card, in the order it appears, parameters included. */
    public static List<ICal.Property> properties(String text) {
        List<ICal.Property> found = new ArrayList<>();
        for (String line : ICal.unfold(text))
            ICal.parseProperty(line).map(VCard::withoutGroup).ifPresent(found::add);
        return found;
    }

    /**
     * Every value of a property, across repeats. Empty when the property is absent, which a
     * filter reads as "not defined".
     */
    public static List<String> values(String text, String property) {
        String wanted = property.toUpperCase(Locale.ROOT);
        return properties(text).stream()
                .filter(p -> p.name.equals(wanted))
                .map(p -> p.value)
                .collect(Collectors.toList());
    }

    /**
     * The TYPE parameter values, upper-cased, which say which of a repeated property this
     * one is: the mobile number rather than the fax, the work address rather than the home.
     */
    public static List<String> types(ICal.Property property) {
        return property.param("TYPE")
                .map(t -> java.util.Arrays.stream(t.split(","))
                        .map(v -> v.trim().toUpperCase(Locale.ROOT))
                        .filter(v -> ! v.isEmpty())
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList());
    }

    /**
     * A structured value split on its unescaped semicolons, each component unescaped: the
     * five of an N, the seven of an ADR. Trailing empty components are kept, so a caller
     * can index into the result by position without checking the length first.
     */
    public static List<String> structured(String value) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                current.append(unescaped(value.charAt(++i)));
            } else if (c == ';') {
                parts.add(current.toString());
                current = new StringBuilder();
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /** One TEXT value with its escapes resolved, semicolons included. */
    public static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length())
                out.append(unescaped(value.charAt(++i)));
            else
                out.append(c);
        }
        return out.toString();
    }

    private static char unescaped(char escaped) {
        switch (escaped) {
            case 'n': case 'N': return '\n';
            default: return escaped;
        }
    }

    /** A vCard property may carry a group prefix, as in "item1.EMAIL". */
    private static ICal.Property withoutGroup(ICal.Property property) {
        int dot = property.name.lastIndexOf('.');
        return dot < 0 ? property
                : new ICal.Property(property.name.substring(dot + 1), property.params, property.value);
    }
}
