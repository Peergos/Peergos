package peergos.server.webdav.caldav;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The subset of a CardDAV {@code <CARD:filter>} the bridge evaluates: property filters,
 * each either a substring match or a test that the property is absent.
 *
 * As with the calendar filter, anything it does not understand widens the result. An
 * over-broad addressbook-query response is legal — the client filters again — whereas a
 * wrong exclusion silently hides a contact.
 */
public final class VCardFilter {

    private static final class PropertyTest {
        final String property;
        final String contains;
        final boolean mustBeAbsent;

        PropertyTest(String property, String contains, boolean mustBeAbsent) {
            this.property = property;
            this.contains = contains;
            this.mustBeAbsent = mustBeAbsent;
        }

        boolean matches(String card) {
            List<String> values = VCard.values(card, property);
            if (mustBeAbsent)
                return values.isEmpty();
            if (contains == null)
                return ! values.isEmpty();
            String wanted = contains.toLowerCase(Locale.ROOT);
            return values.stream().anyMatch(v -> v.toLowerCase(Locale.ROOT).contains(wanted));
        }
    }

    private final List<PropertyTest> tests;
    /** anyof rather than allof, as the test attribute on the filter element asks. */
    private final boolean any;

    private VCardFilter(List<PropertyTest> tests, boolean any) {
        this.tests = tests;
        this.any = any;
    }

    public static VCardFilter parse(Node filter) {
        if (filter == null)
            return new VCardFilter(List.of(), false);
        boolean any = filter instanceof Element
                && "anyof".equalsIgnoreCase(((Element) filter).getAttribute("test"));
        List<PropertyTest> tests = new ArrayList<>();
        for (Node propFilter : Filter.descendants(filter, "prop-filter")) {
            String name = propFilter instanceof Element ? ((Element) propFilter).getAttribute("name") : "";
            if (name.isEmpty())
                continue;
            boolean absent = ! Filter.descendants(propFilter, "is-not-defined").isEmpty();
            List<Node> textMatches = Filter.descendants(propFilter, "text-match");
            String contains = textMatches.isEmpty() ? null : textMatches.get(0).getTextContent();
            tests.add(new PropertyTest(name, contains, absent));
        }
        return new VCardFilter(tests, any);
    }

    public boolean isEverything() {
        return tests.isEmpty();
    }

    /** The content is only read when the filter actually constrains something. */
    public boolean matches(Supplier<String> content) {
        if (isEverything())
            return true;
        String card = content.get();
        return any ? tests.stream().anyMatch(t -> t.matches(card))
                : tests.stream().allMatch(t -> t.matches(card));
    }
}
