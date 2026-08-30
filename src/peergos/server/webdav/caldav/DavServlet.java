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
import peergos.server.webdav.caldav.AppDataStore.CollectionInfo;
import peergos.server.webdav.caldav.AppDataStore.ObjectRef;
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
import java.util.function.Supplier;
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
 * Events can be read and written; the calendar list itself is only half writable.
 * MKCALENDAR creates a directory and its calendar.inf but never touches App.config, which
 * the web app holds in memory for the life of a tab and writes back whole — a second
 * writer here would have its entry dropped by the next edit in an open tab. For the same
 * reason DELETE refuses a calendar that App.config lists, since removing the directory
 * would strand an entry only the web app can remove.
 *
 * Like the file bridge's method handlers this servlet is a single instance shared across
 * request threads, so all per-request state stays on the stack.
 */
public class DavServlet extends HttpServlet {

    private static final Logger LOG = Logging.LOG();

    static final String NS_DAV = "DAV:";
    static final String NS_CALDAV = "urn:ietf:params:xml:ns:caldav";
    static final String NS_CARDDAV = "urn:ietf:params:xml:ns:carddav";
    static final String NS_CALSERVER = "http://calendarserver.org/ns/";
    static final String NS_APPLE_ICAL = "http://apple.com/ns/ical/";

    private static final Map<String, String> NAMESPACES = Map.of(
            NS_DAV, "D",
            NS_CALDAV, "C",
            NS_CARDDAV, "CARD",
            NS_CALSERVER, "CS",
            NS_APPLE_ICAL, "ICAL");

    private static final DateTimeFormatter LAST_MODIFIED = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter CREATION_DATE = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).withZone(ZoneOffset.UTC);

    private static final long MAX_RESOURCE_SIZE = 10 * 1024 * 1024L;

    private final CalendarStore calendars;
    private final ContactStore contacts;
    /** Which collections this user asked for. A disabled one is absent from the home set and
     *  unreachable by URL, so a client that already knows the path gets a 404 rather than data. */
    private final Set<Type> served;

    public DavServlet(UserContext context) {
        this(context, true, true);
    }

    public DavServlet(UserContext context, boolean calendars, boolean contacts) {
        this(new CalendarStore(context), new ContactStore(context), served(calendars, contacts));
    }

    public DavServlet(CalendarStore calendars, ContactStore contacts) {
        this(calendars, contacts, EnumSet.allOf(Type.class));
    }

    public DavServlet(CalendarStore calendars, ContactStore contacts, Set<Type> served) {
        this.calendars = calendars;
        this.contacts = contacts;
        this.served = served;
    }

    private static Set<Type> served(boolean calendars, boolean contacts) {
        Set<Type> res = EnumSet.noneOf(Type.class);
        if (calendars)
            res.add(Type.CALENDAR);
        if (contacts)
            res.add(Type.ADDRESSBOOK);
        return res;
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
                case "PUT":
                    doPut(req, resp);
                    return;
                case "DELETE":
                    doDelete(req, resp);
                    return;
                case "MKCALENDAR":
                    doMakeCollection(req, resp, Type.CALENDAR);
                    return;
                case "MKCOL":
                    doMkcol(req, resp);
                    return;
                default:
                    resp.addHeader("Allow", allowed(resolve(req)));
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
        resp.addHeader("DAV", "1, 2, 3, calendar-access, addressbook");
        resp.addHeader("Allow", allowed(resolve(req)));
        resp.addHeader("MS-Author-Via", "DAV");
        resp.setContentLength(0);
    }

    /** RFC 7231 wants the methods allowed for this resource, not for the servlet. */
    private static String allowed(Optional<Resource> resource) {
        Kind kind = resource.map(r -> r.kind).orElse(Kind.ROOT);
        Type type = resource.map(r -> r.type).orElse(null);
        switch (kind) {
            case OBJECT:
                return "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, REPORT";
            case COLLECTION:
                return "OPTIONS, PUT, DELETE, PROPFIND, REPORT";
            case HOME:
                return type == Type.ADDRESSBOOK ? "OPTIONS, MKCOL, PROPFIND, REPORT"
                        : "OPTIONS, MKCALENDAR, PROPFIND, REPORT";
            default:
                return "OPTIONS, PROPFIND, REPORT";
        }
    }

    private void doRead(HttpServletRequest req, HttpServletResponse resp, boolean withBody) throws IOException {
        Optional<Resource> resolved = resolve(req);
        if (resolved.isEmpty() || resolved.get().kind != Kind.OBJECT) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        ObjectRef object = resolved.get().object;
        byte[] content = resolved.get().store.read(object);
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(resolved.get().type.contentType);
        resp.setContentLength(content.length);
        resp.setHeader("ETag", object.etag());
        if (withBody)
            resp.getOutputStream().write(content);
    }

    // ------------------------------------------------------------------ writing

    @Override
    protected void doPut(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<Slot> resolved = resolveSlot(req);
        if (resolved.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Slot slot = resolved.get();
        if (! slot.name.endsWith(slot.type.suffix) || slot.name.contains("/")) {
            // Anything else would be stored but never listed, since the flat view only
            // surfaces .ics files; better to say so than to swallow it.
            sendPrecondition(resp, HttpServletResponse.SC_FORBIDDEN, slot.type == Type.CALENDAR ?
                    caldav("valid-calendar-object-resource") : carddav("valid-address-data"));
            return;
        }
        if (failsPreconditions(req, slot.existing)) {
            resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
            return;
        }
        Optional<byte[]> content = readBody(req, resp);
        if (content.isEmpty())
            return;
        if (! slot.store.canStore(content.get())) {
            // A calendar object with no start date has no directory the web app would ever
            // read it from, so storing it would hide it rather than keep it; a vCard that
            // does not parse as one has nothing to identify it by.
            sendPrecondition(resp, HttpServletResponse.SC_FORBIDDEN, slot.type == Type.CALENDAR ?
                    caldav("valid-calendar-data") : carddav("valid-address-data"));
            return;
        }
        slot.store.putObject(slot.collection.directory, slot.name, content.get(), slot.existing);
        slot.store.getObject(slot.collection.directory, slot.name)
                .ifPresent(written -> resp.setHeader("ETag", written.etag()));
        resp.setStatus(slot.existing.isPresent()
                ? HttpServletResponse.SC_NO_CONTENT : HttpServletResponse.SC_CREATED);
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Optional<Resource> resolved = resolve(req);
        if (resolved.isEmpty()) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        Resource resource = resolved.get();
        if (resource.kind == Kind.OBJECT) {
            if (failsPreconditions(req, Optional.of(resource.object))) {
                resp.sendError(HttpServletResponse.SC_PRECONDITION_FAILED);
                return;
            }
            resource.store.deleteObject(resource.collection.directory, resource.object);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        if (resource.kind == Kind.COLLECTION) {
            if (resource.collection.configured) {
                // Removing the directory would leave the web app with an App.config entry
                // it cannot satisfy and we will not rewrite. Deleting it there works.
                resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                        "This calendar is listed in the web app; delete it there instead");
                return;
            }
            resource.store.deleteCollection(resource.collection.directory);
            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
            return;
        }
        resp.addHeader("Allow", allowed(resolved));
        resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
    }

    /**
     * MKCALENDAR (RFC 4791) for calendars, and extended MKCOL (RFC 5689) for address books,
     * which is how a CardDAV client asks for one: the collection kind comes from the
     * resourcetype in the body, so a plain MKCOL under /addressbooks/ means the same thing.
     */
    private void doMakeCollection(HttpServletRequest req, HttpServletResponse resp, Type method)
            throws IOException {
        List<String> segments = segments(req.getPathInfo());
        Optional<Type> type = segments.isEmpty() ? Optional.empty() : Type.forSegment(segments.get(0), served);
        if (type.isEmpty() || segments.size() != 3 || ! segments.get(1).equals(username())) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Collections live under /" + method.segment + "/" + username() + "/");
            return;
        }
        if (type.get() != method) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    "Wrong method for a " + type.get().segment + " collection");
            return;
        }
        String directory = segments.get(2);
        if (directory.contains("/") || directory.startsWith(".")) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Illegal collection name");
            return;
        }
        AppDataStore store = store(type.get());
        if (store.getCollection(directory).isPresent()) {
            resp.addHeader("Allow", "OPTIONS, PROPFIND, REPORT");
            resp.sendError(HttpServletResponse.SC_METHOD_NOT_ALLOWED, "Already exists");
            return;
        }
        Element body = parseBody(req).map(Document::getDocumentElement).orElse(null);
        store.createCollection(directory,
                property(body, "displayname").orElse(directory),
                type.get() == Type.CALENDAR ? property(body, "calendar-color").orElse("#00a9ff") : "");
        resp.setStatus(HttpServletResponse.SC_CREATED);
    }

    /** A MKCOL naming an addressbook resourcetype is a CardDAV create; anything else is not. */
    private void doMkcol(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        doMakeCollection(req, resp, Type.ADDRESSBOOK);
    }

    /** Reads a value out of a MKCALENDAR or PROPPATCH style {@code <set><prop>} body. */
    private static Optional<String> property(Element body, String localName) {
        if (body == null)
            return Optional.empty();
        for (Node node : Filter.descendants(body, localName)) {
            String text = node.getTextContent().trim();
            if (! text.isEmpty())
                return Optional.of(text);
        }
        return Optional.empty();
    }

    private Optional<byte[]> readBody(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        byte[] content = req.getInputStream().readNBytes((int) MAX_RESOURCE_SIZE + 1);
        if (content.length > MAX_RESOURCE_SIZE) {
            sendPrecondition(resp, HttpServletResponse.SC_FORBIDDEN, caldav("max-resource-size"));
            return Optional.empty();
        }
        return Optional.of(content);
    }

    /**
     * If-Match and If-None-Match, which is how a CalDAV client avoids overwriting an edit
     * it has not seen. Only the forms clients actually send are honoured.
     */
    private static boolean failsPreconditions(HttpServletRequest req, Optional<ObjectRef> existing) {
        String ifNoneMatch = req.getHeader("If-None-Match");
        if (ifNoneMatch != null && ifNoneMatch.trim().equals("*") && existing.isPresent())
            return true;
        String ifMatch = req.getHeader("If-Match");
        if (ifMatch == null)
            return false;
        if (existing.isEmpty())
            return true;
        if (ifMatch.trim().equals("*"))
            return false;
        String etag = existing.get().etag();
        for (String candidate : ifMatch.split(",")) {
            if (candidate.trim().equals(etag))
                return false;
        }
        return true;
    }

    private void sendPrecondition(HttpServletResponse resp, int status, String precondition) throws IOException {
        resp.setStatus(status);
        resp.setContentType("text/xml; charset=UTF-8");
        XMLWriter out = new XMLWriter(resp.getWriter(), NAMESPACES);
        out.writeXMLHeader();
        out.writeElement(dav("error"), XMLWriter.OPENING);
        out.writeElement(precondition, XMLWriter.NO_CONTENT);
        out.writeElement(dav("error"), XMLWriter.CLOSING);
        out.sendData();
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
            case "addressbook-multiget":
                multiget(req, resp, root);
                return;
            case "calendar-query":
                query(req, resp, resolved.get(), root, Type.CALENDAR);
                return;
            case "addressbook-query":
                query(req, resp, resolved.get(), root, Type.ADDRESSBOOK);
                return;
            case "sync-collection":
                syncCollection(req, resp, resolved.get(), root);
                return;
            default:
                resp.sendError(HttpServletResponse.SC_FORBIDDEN, "Unsupported report: " + report);
        }
    }

    private void multiget(HttpServletRequest req, HttpServletResponse resp, Element root) throws IOException {
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

    private void query(HttpServletRequest req,
                       HttpServletResponse resp,
                       Resource resource,
                       Element root,
                       Type expected) throws IOException {
        if (resource.kind != Kind.COLLECTION || resource.type != expected) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN,
                    expected.segment + " query needs a matching collection");
            return;
        }
        Optional<List<String>> requested = requestedProperties(root);
        Node filterNode = XMLHelper.findSubElement(root, "filter");
        Filter events = expected == Type.CALENDAR ? Filter.parse(filterNode) : null;
        VCardFilter cards = expected == Type.ADDRESSBOOK ? VCardFilter.parse(filterNode) : null;
        XMLWriter out = beginMultistatus(resp);
        for (ObjectRef object : resource.store.listObjects(resource.collection.directory)) {
            Supplier<String> content = () -> new String(resource.store.read(object), StandardCharsets.UTF_8);
            if (events != null ? events.matches(content) : cards.matches(content))
                writeResponse(out, req, resource.child(object), requested);
        }
        endMultistatus(out);
    }

    /**
     * RFC 6578 sync-collection. An empty token enumerates the collection; a token we still
     * remember gets just what changed since, with removed members reported as 404 responses
     * so the client can drop them. A token we no longer remember — the history is bounded,
     * and it does not survive a restart — is answered with DAV:valid-sync-token, which tells
     * the client to resync in full.
     */
    private void syncCollection(HttpServletRequest req,
                                HttpServletResponse resp,
                                Resource resource,
                                Element root) throws IOException {
        if (resource.kind != Kind.COLLECTION) {
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "sync-collection needs a calendar collection");
            return;
        }
        Node token = XMLHelper.findSubElement(root, "sync-token");
        String supplied = token == null ? "" : token.getTextContent().trim();
        Optional<List<String>> requested = requestedProperties(root);
        String directory = resource.collection.directory;

        if (supplied.isEmpty()) {
            AppDataStore.Listing listing = resource.store.listing(directory);
            XMLWriter out = beginMultistatus(resp);
            for (ObjectRef object : listing.objects)
                writeResponse(out, req, resource.child(object), requested);
            writeSyncToken(out, listing.token);
            endMultistatus(out);
            return;
        }

        Optional<AppDataStore.Changes> changes = tokenValue(supplied)
                .flatMap(value -> resource.store.changesSince(directory, value));
        if (changes.isEmpty()) {
            sendPrecondition(resp, HttpServletResponse.SC_FORBIDDEN, dav("valid-sync-token"));
            return;
        }
        XMLWriter out = beginMultistatus(resp);
        for (ObjectRef object : changes.get().changed)
            writeResponse(out, req, resource.child(object), requested);
        for (String removed : changes.get().removed)
            writeNotFound(out, base(req) + resource.path + encode(removed));
        writeSyncToken(out, changes.get().token);
        endMultistatus(out);
    }

    private void writeSyncToken(XMLWriter out, String token) {
        out.writeElement(dav("sync-token"), XMLWriter.OPENING);
        out.writeText(escape(syncToken(token)));
        out.writeElement(dav("sync-token"), XMLWriter.CLOSING);
    }

    private static final String SYNC_TOKEN_PREFIX = "https://peergos.org/ns/dav/sync/";

    private static String syncToken(String value) {
        return SYNC_TOKEN_PREFIX + value;
    }

    /** Anything not in the form we issue is a token we cannot have produced. */
    private static Optional<String> tokenValue(String syncToken) {
        return syncToken.startsWith(SYNC_TOKEN_PREFIX) ?
                Optional.of(syncToken.substring(SYNC_TOKEN_PREFIX.length())) : Optional.empty();
    }

    // -------------------------------------------------------------- properties

    private void writeResponse(XMLWriter out,
                               HttpServletRequest req,
                               Resource resource,
                               Optional<List<String>> requested) {
        List<String> properties = requested.orElseGet(() -> allprop(resource.kind, resource.type));
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
            if (resource.kind == Kind.COLLECTION)
                out.writeElement(resource.type == Type.CALENDAR ? caldav("calendar")
                        : carddav("addressbook"), XMLWriter.NO_CONTENT);
            out.writeElement(dav("resourcetype"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(dav("displayname"))) {
            writeText(out, dav("displayname"), resource.displayName(username()));
            return true;
        }
        if (property.equals(dav("current-user-principal")) || property.equals(dav("principal-URL"))) {
            writeHref(out, property, base(req) + principalPath());
            return true;
        }
        if (property.equals(dav("owner"))) {
            writeHref(out, dav("owner"), base(req) + principalPath());
            return true;
        }
        if (property.equals(dav("principal-collection-set"))) {
            writeHref(out, dav("principal-collection-set"), base(req) + "/principals/");
            return true;
        }
        if (property.equals(dav("current-user-privilege-set"))) {
            out.writeElement(dav("current-user-privilege-set"), XMLWriter.OPENING);
            for (String privilege : privileges(resource.kind)) {
                out.writeElement(dav("privilege"), XMLWriter.OPENING);
                out.writeElement(dav(privilege), XMLWriter.NO_CONTENT);
                out.writeElement(dav("privilege"), XMLWriter.CLOSING);
            }
            out.writeElement(dav("current-user-privilege-set"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(dav("supported-report-set"))) {
            out.writeElement(dav("supported-report-set"), XMLWriter.OPENING);
            if (resource.kind == Kind.COLLECTION) {
                String namespace = resource.type == Type.CALENDAR ? NS_CALDAV : NS_CARDDAV;
                String kind = resource.type == Type.CALENDAR ? "calendar" : "addressbook";
                writeSupportedReport(out, namespace + ":" + kind + "-multiget");
                writeSupportedReport(out, namespace + ":" + kind + "-query");
                writeSupportedReport(out, dav("sync-collection"));
            }
            out.writeElement(dav("supported-report-set"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(caldav("calendar-home-set"))) {
            if (resource.kind != Kind.PRINCIPAL && resource.kind != Kind.ROOT)
                return false;
            writeHref(out, caldav("calendar-home-set"), base(req) + homePath(Type.CALENDAR));
            return true;
        }
        if (property.equals(carddav("addressbook-home-set"))) {
            if (resource.kind != Kind.PRINCIPAL && resource.kind != Kind.ROOT)
                return false;
            writeHref(out, carddav("addressbook-home-set"), base(req) + homePath(Type.ADDRESSBOOK));
            return true;
        }
        if (property.equals(caldav("calendar-user-address-set"))) {
            if (resource.kind != Kind.PRINCIPAL)
                return false;
            writeHref(out, caldav("calendar-user-address-set"),
                    base(req) + principalPath());
            return true;
        }
        if (resource.kind == Kind.COLLECTION)
            return writeCollectionProperty(out, resource, property);
        if (resource.kind == Kind.OBJECT)
            return writeObjectProperty(out, resource, property);
        return false;
    }

    private boolean writeCollectionProperty(XMLWriter out, Resource resource, String property) {
        // Shared between the two kinds: both are versioned collections with a sync token.
        if (property.equals(calserver("getctag"))) {
            out.writeProperty(calserver("getctag"), escape(syncToken(resource.store.token(resource.collection.directory))));
            return true;
        }
        if (property.equals(dav("sync-token"))) {
            out.writeProperty(dav("sync-token"), escape(syncToken(resource.store.token(resource.collection.directory))));
            return true;
        }
        if (resource.type == Type.ADDRESSBOOK)
            return writeAddressBookProperty(out, resource, property);
        if (property.equals(caldav("supported-calendar-component-set"))) {
            // Both, on every calendar: a client only offers a collection as a task list if
            // VTODO is named here, and CalDAV has no separate home set to put one in.
            out.writeElement(caldav("supported-calendar-component-set"), XMLWriter.OPENING);
            out.writeText("<C:comp name=\"VEVENT\"/><C:comp name=\"" + CalendarStore.TASK_COMPONENT + "\"/>");
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
            writeText(out, caldav("calendar-description"), resource.collection.name);
            return true;
        }
        if (property.equals(caldav("max-resource-size"))) {
            out.writeProperty(caldav("max-resource-size"), Long.toString(MAX_RESOURCE_SIZE));
            return true;
        }
        if (property.equals(appleIcal("calendar-color"))) {
            writeText(out, appleIcal("calendar-color"), resource.collection.colour);
            return true;
        }
        return false;
    }

    private boolean writeAddressBookProperty(XMLWriter out, Resource resource, String property) {
        if (property.equals(carddav("supported-address-data"))) {
            out.writeElement(carddav("supported-address-data"), XMLWriter.OPENING);
            out.writeText("<CARD:address-data-type content-type=\"text/vcard\" version=\"3.0\"/>"
                    + "<CARD:address-data-type content-type=\"text/vcard\" version=\"4.0\"/>");
            out.writeElement(carddav("supported-address-data"), XMLWriter.CLOSING);
            return true;
        }
        if (property.equals(carddav("addressbook-description"))) {
            writeText(out, carddav("addressbook-description"), resource.collection.name);
            return true;
        }
        if (property.equals(carddav("max-resource-size"))) {
            out.writeProperty(carddav("max-resource-size"), Long.toString(MAX_RESOURCE_SIZE));
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
            out.writeProperty(dav("getcontenttype"), resource.type.contentType);
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
        String data = resource.type == Type.CALENDAR ? caldav("calendar-data") : carddav("address-data");
        if (property.equals(data)) {
            out.writeElement(data, XMLWriter.OPENING);
            out.writeData(new String(resource.store.read(object), StandardCharsets.UTF_8));
            out.writeElement(data, XMLWriter.CLOSING);
            return true;
        }
        return false;
    }

    /**
     * What the client may do here. A calendar and its members are writable; the home only
     * accepts new collections; everything above it is fixed structure.
     */
    private static List<String> privileges(Kind kind) {
        List<String> privileges = new ArrayList<>(List.of("read", "read-acl", "read-current-user-privilege-set"));
        switch (kind) {
            case COLLECTION:
                privileges.addAll(List.of("write", "write-content", "write-properties", "bind", "unbind"));
                break;
            case OBJECT:
                privileges.addAll(List.of("write", "write-content"));
                break;
            case HOME:
                privileges.addAll(List.of("bind", "unbind"));
                break;
            default:
                break;
        }
        return privileges;
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

    private static List<String> allprop(Kind kind, Type type) {
        List<String> properties = new ArrayList<>(List.of(
                dav("resourcetype"), dav("displayname"), dav("current-user-principal"),
                dav("principal-collection-set"), dav("supported-report-set")));
        switch (kind) {
            case PRINCIPAL:
                properties.addAll(List.of(dav("principal-URL"), caldav("calendar-home-set"),
                        carddav("addressbook-home-set"), caldav("calendar-user-address-set")));
                break;
            case COLLECTION:
                properties.addAll(List.of(dav("owner"), dav("sync-token"), calserver("getctag"),
                        dav("current-user-privilege-set")));
                properties.addAll(type == Type.ADDRESSBOOK ?
                        List.of(carddav("supported-address-data"), carddav("addressbook-description"),
                                carddav("max-resource-size")) :
                        List.of(caldav("supported-calendar-component-set"), caldav("supported-calendar-data"),
                                caldav("calendar-description"), caldav("max-resource-size"),
                                appleIcal("calendar-color")));
                break;
            case OBJECT:
                properties.addAll(List.of(dav("getetag"), dav("getcontenttype"), dav("getcontentlength"),
                        dav("getlastmodified"), dav("creationdate"), dav("current-user-privilege-set")));
                break;
            case HOME:
                properties.add(dav("current-user-privilege-set"));
                break;
            default:
                break;
        }
        return properties;
    }

    // ----------------------------------------------------------- URL space

    private enum Kind { ROOT, PRINCIPALS, PRINCIPAL, HOME_ROOT, HOME, COLLECTION, OBJECT }

    /** The two kinds of collection, and everything that differs between them. */
    private enum Type {
        CALENDAR("calendars", "Calendars", CalendarStore.ICS_SUFFIX, "text/calendar; charset=utf-8"),
        ADDRESSBOOK("addressbooks", "Address Books", ContactStore.VCF_SUFFIX, "text/vcard; charset=utf-8");

        final String segment;
        final String plural;
        final String suffix;
        final String contentType;

        Type(String segment, String plural, String suffix, String contentType) {
            this.segment = segment;
            this.plural = plural;
            this.suffix = suffix;
            this.contentType = contentType;
        }

        static Optional<Type> forSegment(String segment, Set<Type> served) {
            return served.stream().filter(t -> t.segment.equals(segment)).findFirst();
        }
    }

    private static final class Resource {
        final Kind kind;
        final Type type;
        final AppDataStore store;
        final String path;
        final CollectionInfo collection;
        final ObjectRef object;

        Resource(Kind kind, Type type, AppDataStore store, String path,
                 CollectionInfo collection, ObjectRef object) {
            this.kind = kind;
            this.type = type;
            this.store = store;
            this.path = path;
            this.collection = collection;
            this.object = object;
        }

        Resource child(ObjectRef object) {
            return new Resource(Kind.OBJECT, type, store, path + encode(object.name), collection, object);
        }

        String displayName(String username) {
            switch (kind) {
                case ROOT: return "Peergos";
                case PRINCIPALS: return "Principals";
                case PRINCIPAL: return username;
                case HOME_ROOT: return type.plural;
                case HOME: return username;
                case COLLECTION: return collection.name;
                default: return object.name;
            }
        }
    }

    /**
     * Where a PUT would land. Unlike {@link #resolve} this succeeds for a member that does
     * not exist yet, which is the whole point of a create.
     */
    private static final class Slot {
        final Type type;
        final AppDataStore store;
        final CollectionInfo collection;
        final String name;
        final Optional<ObjectRef> existing;

        Slot(Type type, AppDataStore store, CollectionInfo collection,
             String name, Optional<ObjectRef> existing) {
            this.type = type;
            this.store = store;
            this.collection = collection;
            this.name = name;
            this.existing = existing;
        }
    }

    private Optional<Slot> resolveSlot(HttpServletRequest req) {
        List<String> segments = segments(req.getPathInfo());
        if (segments.size() != 4 || ! segments.get(1).equals(username()))
            return Optional.empty();
        Optional<Type> type = Type.forSegment(segments.get(0), served);
        if (type.isEmpty())
            return Optional.empty();
        AppDataStore store = store(type.get());
        return store.getCollection(segments.get(2)).map(collection ->
                new Slot(type.get(), store, collection, segments.get(3),
                        store.getObject(collection.directory, segments.get(3))));
    }

    private Optional<Resource> resolve(HttpServletRequest req) {
        String pathInfo = req.getPathInfo();
        return resolvePath(pathInfo == null ? "/" : pathInfo);
    }

    private static List<String> segments(String pathInfo) {
        List<String> segments = new ArrayList<>();
        for (String segment : (pathInfo == null ? "/" : pathInfo).split("/")) {
            if (! segment.isEmpty())
                segments.add(segment);
        }
        return segments;
    }

    private Optional<Resource> resolvePath(String pathInfo) {
        List<String> segments = segments(pathInfo);
        if (segments.isEmpty())
            return Optional.of(fixed(Kind.ROOT, null, "/"));
        String first = segments.get(0);
        if (first.equals("principals")) {
            if (segments.size() == 1)
                return Optional.of(fixed(Kind.PRINCIPALS, null, "/principals/"));
            if (segments.size() == 2 && segments.get(1).equals(username()))
                return Optional.of(fixed(Kind.PRINCIPAL, null, principalPath()));
            return Optional.empty();
        }
        Optional<Type> maybeType = Type.forSegment(first, served);
        if (maybeType.isEmpty() || segments.size() > 4)
            return Optional.empty();
        Type type = maybeType.get();
        AppDataStore store = store(type);
        if (segments.size() == 1)
            return Optional.of(fixed(Kind.HOME_ROOT, type, "/" + type.segment + "/"));
        if (! segments.get(1).equals(username()))
            return Optional.empty();
        String home = homePath(type);
        if (segments.size() == 2)
            return Optional.of(fixed(Kind.HOME, type, home));
        Optional<CollectionInfo> collection = store.getCollection(segments.get(2));
        if (collection.isEmpty())
            return Optional.empty();
        String path = home + encode(collection.get().directory) + "/";
        if (segments.size() == 3)
            return Optional.of(new Resource(Kind.COLLECTION, type, store, path, collection.get(), null));
        return store.getObject(collection.get().directory, segments.get(3))
                .map(object -> new Resource(Kind.OBJECT, type, store,
                        path + encode(object.name), collection.get(), object));
    }

    private Resource fixed(Kind kind, Type type, String path) {
        return new Resource(kind, type, type == null ? null : store(type), path, null, null);
    }

    private String username() {
        return calendars.username();
    }

    private AppDataStore store(Type type) {
        return type == Type.CALENDAR ? calendars : contacts;
    }

    private String principalPath() {
        return "/principals/" + encode(username()) + "/";
    }

    private String homePath(Type type) {
        return "/" + type.segment + "/" + encode(username()) + "/";
    }

    private List<Resource> children(Resource resource) {
        switch (resource.kind) {
            case ROOT: {
                List<Resource> roots = new ArrayList<>();
                roots.add(fixed(Kind.PRINCIPALS, null, "/principals/"));
                for (Type type : served)
                    roots.add(fixed(Kind.HOME_ROOT, type, "/" + type.segment + "/"));
                return roots;
            }
            case PRINCIPALS:
                return List.of(fixed(Kind.PRINCIPAL, null, principalPath()));
            case HOME_ROOT:
                return List.of(fixed(Kind.HOME, resource.type, homePath(resource.type)));
            case HOME: {
                List<Resource> collections = new ArrayList<>();
                for (CollectionInfo info : resource.store.listCollections())
                    collections.add(new Resource(Kind.COLLECTION, resource.type, resource.store,
                            resource.path + encode(info.directory) + "/", info, null));
                return collections;
            }
            case COLLECTION: {
                List<Resource> objects = new ArrayList<>();
                for (ObjectRef object : resource.store.listObjects(resource.collection.directory))
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

    static String carddav(String local) {
        return NS_CARDDAV + ":" + local;
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
