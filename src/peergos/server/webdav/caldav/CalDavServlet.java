package peergos.server.webdav.caldav;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import peergos.server.util.Logging;
import peergos.server.webdav.caldav.CalendarStore.CalendarInfo;
import peergos.server.webdav.caldav.CalendarStore.ObjectRef;
import peergos.server.webdav.modeshape.webdav.fromcatalina.RequestUtil;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLHelper;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLWriter;
import peergos.shared.user.UserContext;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A CalDAV (RFC 4791) view of the Peergos calendar app's data, served under its own
 * prefix so that a file client mounting / never sees calendar collections.
 *
 * <pre>
 *   /dav/                               root
 *   /dav/principals/&lt;user&gt;/             principal
 *   /dav/calendars/&lt;user&gt;/              calendar-home-set
 *   /dav/calendars/&lt;user&gt;/&lt;dir&gt;/        one calendar collection
 *   /dav/calendars/&lt;user&gt;/&lt;dir&gt;/&lt;n&gt;.ics one event
 * </pre>
 *
 * Read-only for now: the write path (PUT, DELETE, MKCALENDAR) is not registered, and
 * current-user-privilege-set advertises read access only, so clients present the
 * calendars as read-only rather than failing writes at the last moment.
 *
 * Like the file bridge's method handlers this servlet is a single instance shared across
 * request threads, so all per-request state stays on the stack.
 */
public class CalDavServlet extends HttpServlet {

    private static final Logger LOG = Logging.LOG();

    static final String NS_DAV = "DAV:";
    static final String NS_CALDAV = "urn:ietf:params:xml:ns:caldav";
    static final String NS_CALSERVER = "http://calendarserver.org/ns/";
    static final String NS_APPLE_ICAL = "http://apple.com/ns/ical/";

    private static final Map<String, String> NAMESPACES = Map.of(
            NS_DAV, "D",
            NS_CALDAV, "C",
            NS_CALSERVER, "CS",
            NS_APPLE_ICAL, "ICAL");

    private static final DateTimeFormatter LAST_MODIFIED = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CREATION_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC);

    private static final String ALLOWED = "OPTIONS, GET, HEAD, PROPFIND, REPORT";
    private static final long MAX_RESOURCE_SIZE = 10 * 1024 * 1024L;

    private final CalendarStore store;

    public CalDavServlet(UserContext context) {
        this(new CalendarStore(context));
    }

    public CalDavServlet(CalendarStore store) {
        this.store = store;
    }

    // ---------------------------------------------------------------- dispatch

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            switch (req.getMethod()) {
                case "OPTIONS":
                    doOptions(req, resp);
                    return;
                case "PROPFIND":
                    doPropfind(req, resp);
                    return;
                case "REPORT":
                    doReport(req, resp);
                    return;
                case "GET":
                case "HEAD":
                    doRead(req, resp, req.getMethod().equals("GET"));
                    return;
                default:
                    resp.addHeader("Allow", ALLOWED);
                    resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            }
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> "CalDAV " + req.getMethod() + " " + req.getRequestURI() + " failed");
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doOptions(HttpServletRequest req, HttpServletResponse resp) {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.addHeader("DAV", "1, 2, 3, calendar-access");
        resp.addHeader("Allow", ALLOWED);
        resp.addHeader("MS-Author-Via", "DAV");
        resp.setContentLength(0);
    }

    private void doRead(HttpServletRequest req, HttpServletResponse resp, boolean withBody) throws IOException {
        Optional<Resource> resolved = resolve(req);
        if (resolved.isEmpty() || resolved.get().kind != Kind.OBJECT) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        ObjectRef object = resolved.get().object;
        byte[] content = store.read(object);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/calendar; charset=utf-8");
        resp.setContentLength(content.length);
        resp.setHeader("ETag", object.etag());
        if (withBody)
            resp.getOutputStream().write(content);
    }

    // ---------------------------------------------------------------- PROPFIND

    private void doPropfind(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<Resource> resolved = resolve(req);
        if (resolved.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Resource resource = resolved.get();
        // An absent or unreadable body is an allprop request.
        Optional<Document> body = parseBody(req);
        Optional<List<String>> requested = body.map(d -> requestedProperties(d.getDocumentElement()))
                .orElse(Optional.empty());
        int depth = depth(req);

        XMLWriter out = beginMultistatus(resp);
        writeResponse(out, req, resource, requested);
        if (depth > 0) {
            for (Resource child : children(resource))
                writeResponse(out, req, child, requested);
        }
        endMultistatus(out);
    }

    /** Empty means allprop: the request had no {@code <prop>} element to filter on. */
    private static Optional<List<String>> requestedProperties(Element root) {
        Node prop = XMLHelper.findSubElement(root, "prop");
        if (prop == null)
            return Optional.empty();
        return Optional.of(new ArrayList<>(XMLHelper.getPropertiesFromXML(prop)));
    }

    // ------------------------------------------------------------------ REPORT

    private void doReport(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<Resource> resolved = resolve(req);
        if (resolved.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Optional<Document> body = parseBody(req);
        if (body.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        Element root = body.get().getDocumentElement();
        String report = root.getLocalName();
        switch (report) {
            case "calendar-multiget":
                calendarMultiget(req, resp, root);
                return;
            case "calendar-query":
                calendarQuery(req, resp, resolved.get(), root);
                return;
            case "sync-collection":
                syncCollection(req, resp, resolved.get(), root);
                return;
            default:
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Unsupported report: " + report);
        }
    }

    private void calendarMultiget(HttpServletRequest req, HttpServletResponse resp, Element root) throws IOException {
        Optional<List<String>> requested = requestedProperties(root);
        XMLWriter out = beginMultistatus(resp);
        for (String href : hrefs(root)) {
            Optional<Resource> target = resolveHref(req, href);
            if (target.isPresent() && target.get().kind == Kind.OBJECT)
                writeResponse(out, req, target.get(), requested);
            else
                writeNotFound(out, href);
        }
        endMultistatus(out);
    }

    private void calendarQuery(HttpServletRequest req,
                               HttpServletResponse resp,
                               Resource resource,
                               Element root) throws IOException {
        if (resource.kind != Kind.CALENDAR) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "calendar-query needs a calendar collection");
            return;
        }
        Optional<List<String>> requested = requestedProperties(root);
        Filter filter = Filter.parse(XMLHelper.findSubElement(root, "filter"));
        XMLWriter out = beginMultistatus(resp);
        for (ObjectRef object : store.listObjects(resource.calendar.directory)) {
            if (filter.matches(() -> new String(store.read(object), StandardCharsets.UTF_8)))
                writeResponse(out, req, resource.child(object), requested);
        }
        endMultistatus(out);
    }

    /**
     * Without a per-collection change log there is no way to answer incrementally, so an
     * initial (empty) token gets the full listing and any later token is rejected with
     * DAV:valid-sync-token, which tells the client to resync in full. That is legal, and
     * costs one extra listing per change rather than risking a missed deletion.
     */
    private void syncCollection(HttpServletRequest req,
                                HttpServletResponse resp,
                                Resource resource,
                                Element root) throws IOException {
        if (resource.kind != Kind.CALENDAR) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "sync-collection needs a calendar collection");
            return;
        }
        Node token = XMLHelper.findSubElement(root, "sync-token");
        String supplied = token == null ? "" : token.getTextContent().trim();
        String current = syncToken(resource.calendar.directory);
        if (! supplied.isEmpty() && ! supplied.equals(current)) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.setContentType("text/xml; charset=UTF-8");
            XMLWriter out = new XMLWriter(resp.getWriter(), NAMESPACES);
            out.writeXMLHeader();
            out.writeElement(dav("error"), XMLWriter.OPENING);
            out.writeElement(dav("valid-sync-token"), XMLWriter.NO_CONTENT);
            out.writeElement(dav("error"), XMLWriter.CLOSING);
            out.sendData();
            return;
        }
        Optional<List<String>> requested = requestedProperties(root);
        XMLWriter out = beginMultistatus(resp);
        if (supplied.isEmpty()) {
            for (ObjectRef object : store.listObjects(resource.calendar.directory))
                writeResponse(out, req, resource.child(object), requested);
        }
        out.writeElement(dav("sync-token"), XMLWriter.OPENING);
        out.writeText(escape(current));
        out.writeElement(dav("sync-token"), XMLWriter.CLOSING);
        endMultistatus(out);
    }

    private String syncToken(String directory) {
        return "https://peergos.org/ns/dav/sync/" + store.ctag(directory);
    }

    // -------------------------------------------------------------- properties

    private void writeResponse(XMLWriter out,
                               HttpServletRequest req,
                               Resource resource,
                               Optional<List<String>> requested) {
        List<String> properties = requested.orElseGet(() -> allprop(resource.kind));
        List<String> notFound = new ArrayList<>();
        XMLWriter found = nested();

        for (String property : properties) {
            if (! writeProperty(found, req, resource, property))
                notFound.add(property);
        }
        String foundXml = found.toString();

        out.writeElement(dav("response"), XMLWriter.OPENING);
        out.writeElement(dav("href"), XMLWriter.OPENING);
        out.writeText(escape(href(req, resource)));
        out.writeElement(dav("href"), XMLWriter.CLOSING);

        if (! foundXml.isEmpty()) {
            out.writeElement(dav("propstat"), XMLWriter.OPENING);
            out.writeElement(dav("prop"), XMLWriter.OPENING);
            out.writeText(foundXml);
            out.writeElement(dav("prop"), XMLWriter.CLOSING);
            writeStatus(out, HttpServletResponse.SC_OK, "OK");
            out.writeElement(dav("propstat"), XMLWriter.CLOSING);
        }
        if (! notFound.isEmpty()) {
            out.writeElement(dav("propstat"), XMLWriter.OPENING);
            out.writeElement(dav("prop"), XMLWriter.OPENING);
            for (String property : notFound)
                out.writeElement(property, XMLWriter.NO_CONTENT);
            out.writeElement(dav("prop"), XMLWriter.CLOSING);
            writeStatus(out, HttpServletResponse.SC_NOT_FOUND, "Not Found");
            out.writeElement(dav("propstat"), XMLWriter.CLOSING);
        }
        out.writeElement(dav("response"), XMLWriter.CLOSING);
    }

    private void writeNotFound(XMLWriter out, String href) {
        out.writeElement(dav("response"), XMLWriter.OPENING);
        out.writeElement(dav("href"), XMLWriter.OPENING);
        out.writeText(escape(href));
        out.writeElement(dav("href"), XMLWriter.CLOSING);
        writeStatus(out, HttpServletResponse.SC_NOT_FOUND, "Not Found");
        out.writeElement(dav("response"), XMLWriter.CLOSING);
    }

    private static void writeStatus(XMLWriter out, int code, String text) {
        out.writeElement(dav("status"), XMLWriter.OPENING);
        out.writeText("HTTP/1.1 " + code + " " + text);
        out.writeElement(dav("status"), XMLWriter.CLOSING);
    }

    private boolean writeProperty(XMLWriter out, HttpServletRequest req, Resource resource, String property) {
        if (property.equals(dav("resourcetype"))) {
            out.writeElement(dav("resourcetype"), resource.kind == Kind.OBJECT ? XMLWriter.NO_CONTENT : XMLWriter.OPENING);
            if (resource.kind == Kind.OBJECT)
                return true;
            out.writeElement(dav("collection"), XMLWriter.NO_CONTENT);
            if (resource.kind == Kind.PRINCIPAL)
                out.writeElement(dav("principal"), XMLWriter.NO_CONTENT);
            if (resource.kind == Kind.CALENDAR)
                out.writeElement(caldav("calendar"), XMLWriter.NO_CONTENT);
            out.writeElement(dav("resourcetype"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(dav("displayname"))) {
            writeText(out, dav("displayname"), resource.displayName(store.username()));
            return true;
        }
        if (property.equals(dav("current-user-principal")) || property.equals(dav("principal-URL"))) {
            writeHref(out, property, base(req) + "/principals/" + encode(store.username()) + "/");
            return true;
        }
        if (property.equals(dav("owner"))) {
            writeHref(out, dav("owner"), base(req) + "/principals/" + encode(store.username()) + "/");
            return true;
        }
        if (property.equals(dav("principal-collection-set"))) {
            writeHref(out, dav("principal-collection-set"), base(req) + "/principals/");
            return true;
        }
        if (property.equals(dav("current-user-privilege-set"))) {
            out.writeElement(dav("current-user-privilege-set"), XMLWriter.OPENING);
            for (String privilege : List.of("read", "read-acl", "read-current-user-privilege-set")) {
                out.writeElement(dav("privilege"), XMLWriter.OPENING);
                out.writeElement(dav(privilege), XMLWriter.NO_CONTENT);
                out.writeElement(dav("privilege"), XMLWriter.CLOSING);
            }
            out.writeElement(dav("current-user-privilege-set"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(dav("supported-report-set"))) {
            out.writeElement(dav("supported-report-set"), XMLWriter.OPENING);
            if (resource.kind == Kind.CALENDAR) {
                writeSupportedReport(out, caldav("calendar-multiget"));
                writeSupportedReport(out, caldav("calendar-query"));
                writeSupportedReport(out, dav("sync-collection"));
            }
            out.writeElement(dav("supported-report-set"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(caldav("calendar-home-set"))) {
            if (resource.kind != Kind.PRINCIPAL && resource.kind != Kind.ROOT)
                return false;
            writeHref(out, caldav("calendar-home-set"), base(req) + "/calendars/" + encode(store.username()) + "/");
            return true;
        }
        if (property.equals(caldav("calendar-user-address-set"))) {
            if (resource.kind != Kind.PRINCIPAL)
                return false;
            writeHref(out, caldav("calendar-user-address-set"),
                    base(req) + "/principals/" + encode(store.username()) + "/");
            return true;
        }
        if (resource.kind == Kind.CALENDAR)
            return writeCalendarProperty(out, resource, property);
        if (resource.kind == Kind.OBJECT)
            return writeObjectProperty(out, resource, property);
        return false;
    }

    private boolean writeCalendarProperty(XMLWriter out, Resource resource, String property) {
        if (property.equals(caldav("supported-calendar-component-set"))) {
            out.writeElement(caldav("supported-calendar-component-set"), XMLWriter.OPENING);
            out.writeText("<C:comp name=\"VEVENT\"/>");
            out.writeElement(caldav("supported-calendar-component-set"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(caldav("supported-calendar-data"))) {
            out.writeElement(caldav("supported-calendar-data"), XMLWriter.OPENING);
            out.writeText("<C:calendar-data content-type=\"text/calendar\" version=\"2.0\"/>");
            out.writeElement(caldav("supported-calendar-data"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(caldav("calendar-description"))) {
            writeText(out, caldav("calendar-description"), resource.calendar.name);
            return true;
        }
        if (property.equals(caldav("max-resource-size"))) {
            out.writeProperty(caldav("max-resource-size"), Long.toString(MAX_RESOURCE_SIZE));
            return true;
        }
        if (property.equals(calserver("getctag"))) {
            out.writeProperty(calserver("getctag"), escape(syncToken(resource.calendar.directory)));
            return true;
        }
        if (property.equals(dav("sync-token"))) {
            out.writeProperty(dav("sync-token"), escape(syncToken(resource.calendar.directory)));
            return true;
        }
        if (property.equals(appleIcal("calendar-color"))) {
            writeText(out, appleIcal("calendar-color"), resource.calendar.colour);
            return true;
        }
        return false;
    }

    private boolean writeObjectProperty(XMLWriter out, Resource resource, String property) {
        ObjectRef object = resource.object;
        if (property.equals(dav("getetag"))) {
            out.writeProperty(dav("getetag"), escape(object.etag()));
            return true;
        }
        if (property.equals(dav("getcontenttype"))) {
            out.writeProperty(dav("getcontenttype"), "text/calendar; charset=utf-8");
            return true;
        }
        if (property.equals(dav("getcontentlength"))) {
            out.writeProperty(dav("getcontentlength"), Long.toString(object.file.getSize()));
            return true;
        }
        if (property.equals(dav("getlastmodified"))) {
            out.writeProperty(dav("getlastmodified"), LAST_MODIFIED.format(
                    object.file.getFileProperties().modified.toInstant(ZoneOffset.UTC)));
            return true;
        }
        if (property.equals(dav("creationdate"))) {
            out.writeProperty(dav("creationdate"), CREATION_DATE.format(
                    object.file.getFileProperties().created.toInstant(ZoneOffset.UTC)));
            return true;
        }
        if (property.equals(caldav("calendar-data"))) {
            out.writeElement(caldav("calendar-data"), XMLWriter.OPENING);
            out.writeData(new String(store.read(object), StandardCharsets.UTF_8));
            out.writeElement(caldav("calendar-data"), XMLWriter.CLOSING);
            return true;
        }
        return false;
    }

    private static void writeSupportedReport(XMLWriter out, String report) {
        out.writeElement(dav("supported-report"), XMLWriter.OPENING);
        out.writeElement(dav("report"), XMLWriter.OPENING);
        out.writeElement(report, XMLWriter.NO_CONTENT);
        out.writeElement(dav("report"), XMLWriter.CLOSING);
        out.writeElement(dav("supported-report"), XMLWriter.CLOSING);
    }

    private static void writeHref(XMLWriter out, String property, String href) {
        out.writeElement(property, XMLWriter.OPENING);
        out.writeElement(dav("href"), XMLWriter.OPENING);
        out.writeText(escape(href));
        out.writeElement(dav("href"), XMLWriter.CLOSING);
        out.writeElement(property, XMLWriter.CLOSING);
    }

    private static void writeText(XMLWriter out, String property, String value) {
        out.writeElement(property, XMLWriter.OPENING);
        out.writeText(escape(value));
        out.writeElement(property, XMLWriter.CLOSING);
    }

    private static List<String> allprop(Kind kind) {
        List<String> properties = new ArrayList<>(List.of(
                dav("resourcetype"), dav("displayname"), dav("current-user-principal"),
                dav("principal-collection-set"), dav("supported-report-set")));
        switch (kind) {
            case PRINCIPAL:
                properties.addAll(List.of(dav("principal-URL"), caldav("calendar-home-set"),
                        caldav("calendar-user-address-set")));
                break;
            case CALENDAR:
                properties.addAll(List.of(dav("owner"), dav("sync-token"), calserver("getctag"),
                        caldav("supported-calendar-component-set"), caldav("supported-calendar-data"),
                        caldav("calendar-description"), caldav("max-resource-size"),
                        appleIcal("calendar-color"), dav("current-user-privilege-set")));
                break;
            case OBJECT:
                properties.addAll(List.of(dav("getetag"), dav("getcontenttype"), dav("getcontentlength"),
                        dav("getlastmodified"), dav("creationdate")));
                break;
            default:
                break;
        }
        return properties;
    }

    // ----------------------------------------------------------- URL space

    private enum Kind { ROOT, PRINCIPALS, PRINCIPAL, CALENDARS, CALENDAR_HOME, CALENDAR, OBJECT }

    private static final class Resource {
        final Kind kind;
        final String path;
        final CalendarInfo calendar;
        final ObjectRef object;

        Resource(Kind kind, String path, CalendarInfo calendar, ObjectRef object) {
            this.kind = kind;
            this.path = path;
            this.calendar = calendar;
            this.object = object;
        }

        static Resource collection(Kind kind, String path) {
            return new Resource(kind, path, null, null);
        }

        Resource child(ObjectRef object) {
            return new Resource(Kind.OBJECT, path + encode(object.name), calendar, object);
        }

        String displayName(String username) {
            switch (kind) {
                case ROOT: return "Peergos";
                case PRINCIPALS: return "Principals";
                case PRINCIPAL: return username;
                case CALENDARS: return "Calendars";
                case CALENDAR_HOME: return username;
                case CALENDAR: return calendar.name;
                default: return object.name;
            }
        }
    }

    private Optional<Resource> resolve(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        return resolvePath(pathInfo == null ? "/" : pathInfo);
    }

    private Optional<Resource> resolvePath(String pathInfo) {
        List<String> segments = new ArrayList<>();
        for (String segment : pathInfo.split("/")) {
            if (! segment.isEmpty())
                segments.add(segment);
        }
        if (segments.isEmpty())
            return Optional.of(Resource.collection(Kind.ROOT, "/"));
        String first = segments.get(0);
        if (first.equals("principals")) {
            if (segments.size() == 1)
                return Optional.of(Resource.collection(Kind.PRINCIPALS, "/principals/"));
            if (segments.size() == 2 && segments.get(1).equals(store.username()))
                return Optional.of(Resource.collection(Kind.PRINCIPAL,
                        "/principals/" + encode(store.username()) + "/"));
            return Optional.empty();
        }
        if (! first.equals("calendars"))
            return Optional.empty();
        if (segments.size() == 1)
            return Optional.of(Resource.collection(Kind.CALENDARS, "/calendars/"));
        if (! segments.get(1).equals(store.username()))
            return Optional.empty();
        String home = "/calendars/" + encode(store.username()) + "/";
        if (segments.size() == 2)
            return Optional.of(Resource.collection(Kind.CALENDAR_HOME, home));
        Optional<CalendarInfo> calendar = store.getCalendar(segments.get(2));
        if (calendar.isEmpty() || segments.size() > 4)
            return Optional.empty();
        String collection = home + encode(calendar.get().directory) + "/";
        if (segments.size() == 3)
            return Optional.of(new Resource(Kind.CALENDAR, collection, calendar.get(), null));
        return store.getObject(calendar.get().directory, segments.get(3))
                .map(object -> new Resource(Kind.OBJECT, collection + encode(object.name), calendar.get(), object));
    }

    private List<Resource> children(Resource resource) {
        switch (resource.kind) {
            case ROOT:
                return List.of(Resource.collection(Kind.PRINCIPALS, "/principals/"),
                        Resource.collection(Kind.CALENDARS, "/calendars/"));
            case PRINCIPALS:
                return List.of(Resource.collection(Kind.PRINCIPAL,
                        "/principals/" + encode(store.username()) + "/"));
            case CALENDARS:
                return List.of(Resource.collection(Kind.CALENDAR_HOME,
                        "/calendars/" + encode(store.username()) + "/"));
            case CALENDAR_HOME: {
                List<Resource> calendars = new ArrayList<>();
                for (CalendarInfo calendar : store.listCalendars())
                    calendars.add(new Resource(Kind.CALENDAR,
                            resource.path + encode(calendar.directory) + "/", calendar, null));
                return calendars;
            }
            case CALENDAR: {
                List<Resource> objects = new ArrayList<>();
                for (ObjectRef object : store.listObjects(resource.calendar.directory))
                    objects.add(resource.child(object));
                return objects;
            }
            default:
                return Collections.emptyList();
        }
    }

    private String base(HttpServletRequest req) {
        String context = req.getContextPath() == null ? "" : req.getContextPath();
        String servlet = req.getServletPath() == null ? "" : req.getServletPath();
        return context + servlet;
    }

    private String href(HttpServletRequest req, Resource resource) {
        return base(req) + resource.path;
    }

    /** Maps an href from a report body back onto a resource, tolerating an absolute URL. */
    private Optional<Resource> resolveHref(HttpServletRequest req, String href) {
        String path = href;
        int schemeEnd = path.indexOf("://");
        if (schemeEnd >= 0) {
            int slash = path.indexOf('/', schemeEnd + 3);
            path = slash < 0 ? "/" : path.substring(slash);
        }
        String base = base(req);
        if (! base.isEmpty() && path.startsWith(base))
            path = path.substring(base.length());
        return resolvePath(URLDecoder.decode(path, StandardCharsets.UTF_8));
    }

    // -------------------------------------------------------------- XML basics

    private XMLWriter beginMultistatus(HttpServletResponse resp) throws IOException {
        resp.setStatus(207);
        resp.setContentType("text/xml; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        XMLWriter out = new XMLWriter(resp.getWriter(), NAMESPACES);
        out.writeXMLHeader();
        out.writeElement(dav("multistatus"), XMLWriter.OPENING);
        return out;
    }

    private void endMultistatus(XMLWriter out) throws IOException {
        out.writeElement(dav("multistatus"), XMLWriter.CLOSING);
        out.sendData();
    }

    private Optional<Document> parseBody(HttpServletRequest req) {
        if (! RequestUtil.streamNotConsumed(req))
            return Optional.empty();
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setExpandEntityReferences(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return Optional.of(builder.parse(new InputSource(req.getInputStream())));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static List<String> hrefs(Element root) {
        List<String> hrefs = new ArrayList<>();
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "href".equals(child.getLocalName()))
                hrefs.add(child.getTextContent().trim());
        }
        return hrefs;
    }

    private static int depth(HttpServletRequest req) {
        String depth = req.getHeader("Depth");
        if (depth == null || depth.equals("0"))
            return 0;
        return 1;
    }

    static String dav(String local) {
        return NS_DAV + ":" + local;
    }

    static String caldav(String local) {
        return NS_CALDAV + ":" + local;
    }

    static String calserver(String local) {
        return NS_CALSERVER + ":" + local;
    }

    static String appleIcal(String local) {
        return NS_APPLE_ICAL + ":" + local;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Percent-encodes one path segment, leaving the characters hrefs may carry unescaped. */
    static String encode(String segment) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : segment.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if (Character.isLetterOrDigit(c) || "-_.~!$&'()*+,;=:@".indexOf(c) >= 0)
                encoded.append(c);
            else
                encoded.append('%').append(String.format("%02X", b & 0xff));
        }
        return encoded.toString();
    }

    /**
     * A writer for content spliced into a document that has already declared the
     * namespaces, used to buffer the found properties: a resource with none must not emit
     * an empty propstat, and we only know that after trying them all.
     */
    private static XMLWriter nested() {
        return new XMLWriter(NAMESPACES) {{ isRootElement = false; }};
    }
}
