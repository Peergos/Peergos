package peergos.server.webdav.caldav;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Just enough vCard to recognise one and match a property against a CardDAV filter.
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

    /**
     * Every value of a property, across repeats. Empty when the property is absent, which a
     * filter reads as "not defined".
     */
    public static List<String> values(String text, String property) {
        String wanted = property.toUpperCase(Locale.ROOT);
        List<String> found = new ArrayList<>();
        for (String line : ICal.unfold(text)) {
            int colon = colonAt(line);
            if (colon < 0)
                continue;
            String name = line.substring(0, colon);
            int semicolon = name.indexOf(';');
            if (semicolon >= 0)
                name = name.substring(0, semicolon);
            // A vCard property may carry a group prefix, as in "item1.EMAIL".
            int dot = name.lastIndexOf('.');
            if (dot >= 0)
                name = name.substring(dot + 1);
            if (name.trim().toUpperCase(Locale.ROOT).equals(wanted))
                found.add(line.substring(colon + 1));
        }
        return found;
    }

    /** The separating colon, skipping any inside a quoted parameter value. */
    private static int colonAt(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"')
                quoted = ! quoted;
            else if (c == ':' && ! quoted)
                return i;
        }
        return -1;
    }
}
