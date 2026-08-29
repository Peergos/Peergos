package peergos.server.webdav.caldav;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The subset of a CalDAV {@code <C:filter>} the bridge evaluates: which component type is
 * wanted, and the time range it must overlap.
 *
 * Everything it does not understand widens the result rather than narrowing it. An
 * over-broad calendar-query response is legal — the client filters again — whereas a
 * wrong exclusion silently hides an event.
 */
public final class Filter {

    private final Optional<String> componentType;
    private final Optional<Instant> rangeStart;
    private final Optional<Instant> rangeEnd;

    private Filter(Optional<String> componentType, Optional<Instant> rangeStart, Optional<Instant> rangeEnd) {
        this.componentType = componentType;
        this.rangeStart = rangeStart;
        this.rangeEnd = rangeEnd;
    }

    public static Filter everything() {
        return new Filter(Optional.empty(), Optional.empty(), Optional.empty());
    }

    public boolean isEverything() {
        return componentType.isEmpty() && rangeStart.isEmpty() && rangeEnd.isEmpty();
    }

    public static Filter parse(Node filter) {
        if (filter == null)
            return everything();
        Optional<String> componentType = Optional.empty();
        Optional<Node> timeRange = Optional.empty();
        // Descend through the comp-filter chain; VCALENDAR is the envelope, so the
        // component the client is really asking for is the innermost named one.
        for (Node compFilter : descendants(filter, "comp-filter")) {
            String name = attribute(compFilter, "name");
            if (name != null && ! name.equalsIgnoreCase("VCALENDAR"))
                componentType = Optional.of(name.toUpperCase());
        }
        for (Node range : descendants(filter, "time-range"))
            timeRange = Optional.of(range);
        return new Filter(componentType,
                timeRange.flatMap(r -> instant(attribute(r, "start"))),
                timeRange.flatMap(r -> instant(attribute(r, "end"))));
    }

    /** The content is only read when the filter actually constrains something. */
    public boolean matches(Supplier<String> content) {
        if (isEverything())
            return true;
        ICal.Summary summary = ICal.summarise(content.get());
        if (componentType.isPresent() && summary.componentType.isPresent()
                && ! summary.componentType.get().equals(componentType.get()))
            return false;
        return summary.overlaps(rangeStart, rangeEnd);
    }

    private static Optional<Instant> instant(String value) {
        if (value == null)
            return Optional.empty();
        return ICal.toInstant(new ICal.Property("TIME-RANGE", java.util.Collections.emptyMap(), value));
    }

    private static String attribute(Node node, String name) {
        return node instanceof Element ? emptyToNull(((Element) node).getAttribute(name)) : null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<Node> descendants(Node root, String localName) {
        List<Node> found = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE)
                continue;
            if (localName.equals(child.getLocalName()))
                found.add(child);
            found.addAll(descendants(child, localName));
        }
        return found;
    }
}
