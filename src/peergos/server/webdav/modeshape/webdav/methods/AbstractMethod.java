/*
 * Copyright 1999,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package peergos.server.webdav.modeshape.webdav.methods;

import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.IMethodExecutor;
import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.StoredObject;
import peergos.server.webdav.modeshape.webdav.WebdavStatus;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;
import peergos.server.webdav.modeshape.webdav.fromcatalina.URLEncoder;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLWriter;
import peergos.server.webdav.modeshape.webdav.locking.IResourceLocks;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.Writer;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractMethod implements IMethodExecutor {
    
    
    private static final Pattern MULTI_LOCK_PATTERN = Pattern.compile("(.*<.*locktoken\\:.+\\>.*).*(<.*locktoken\\:.+\\>.*)");
    private static final Pattern SINGLE_LOCK_PATTERN = Pattern.compile("(.*<.*locktoken\\:.+\\>.*)");
    private static final Pattern LOCK_TOKEN_PATTERN = Pattern.compile("(.*<??.*locktoken:)([a-zA-z0-9\\-]+)(>??.*)");
    
    /**
     * Array containing the safe characters set.
     */
    protected static URLEncoder URL_ENCODER;

    /**
     * Default depth is infite.
     */
    protected static final int INFINITY = 3;

    /**
     * Simple date format for the creation date ISO 8601 representation (partial).
     */
    protected static final DateTimeFormatter CREATION_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                                                                              .withZone(ZoneId.of("GMT")); 

    /**
     * Simple date format for the last modified date. (RFC 822 updated by RFC 1123)
     */
    protected static final DateTimeFormatter LAST_MODIFIED_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z",
                                                                                                     Locale.US).withZone(ZoneId.of("GMT")); 

    static {
        /**
         * GMT timezone - all HTTP dates are on GMT
         */
        URL_ENCODER = new URLEncoder();
        URL_ENCODER.addSafeCharacter('-');
        URL_ENCODER.addSafeCharacter('_');
        URL_ENCODER.addSafeCharacter('.');
        URL_ENCODER.addSafeCharacter('*');
        URL_ENCODER.addSafeCharacter('/');
    }

    /**
     * size of the io-buffer
     */
    protected static int BUF_SIZE = 65536;

    /**
     * Default lock timeout value.
     */
    protected static final int DEFAULT_TIMEOUT = 3600;

    /**
     * Maximum lock timeout.
     */
    protected static final int MAX_TIMEOUT = 604800;

    /**
     * Boolean value to temporary lock resources (for method locks)
     */
    protected static final boolean TEMPORARY = true;

    /**
     * Timeout for temporary locks
     */
    protected static final int TEMP_TIMEOUT = 10;

    protected Logger logger;

    protected AbstractMethod() {
        logger = Logging.LOG();
    }

    public static String lastModifiedDateFormat( final Date date ) {
        return LAST_MODIFIED_DATE_FORMAT.format(date.toInstant());
    }

    public static String creationDateFormat( final Date date ) {
        return CREATION_DATE_FORMAT.format(date.toInstant());
    }

    /**
     * Return the relative path associated with this servlet.
     * 
     * @param request The servlet request we are processing
     * @return the relative path
     */
    protected String getRelativePath( HttpServletRequest request ) {

        // Are we being processed by a RequestDispatcher.include()?
        if (request.getAttribute("jakarta.servlet.include.request_uri") != null) {
            String result = (String)request.getAttribute("jakarta.servlet.include.path_info");
            // if (result == null)
            // result = (String) request
            // .getAttribute("jakarta.servlet.include.servlet_path");
            if ((result == null) || (result.equals(""))) {
                result = "/";
            }
            return result;
        }

        // No, extract the desired path directly from the request
        String result = request.getPathInfo();

        if ((result == null) || (result.equals(""))) {
            result = "/";
        }
        return result;
    }

    /**
     * creates the parent path from the given path by removing the last '/' and everything after that
     * 
     * @param path the path
     * @return parent path
     */
    protected String getParentPath( String path ) {
        int slash = path.lastIndexOf('/');
        if (slash != -1) {
            return path.substring(0, slash);
        }
        return null;
    }

    /**
     * removes a / at the end of the path string, if present
     * 
     * @param path the path
     * @return the path without trailing /
     */
    protected String getCleanPath( String path ) {

        if (path.endsWith("/") && path.length() > 1) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Return JAXP document builder instance.
     * 
     * @return the builder
     * @throws ServletException
     */
    protected DocumentBuilder getDocumentBuilder() throws ServletException {
        DocumentBuilder documentBuilder = null;
        DocumentBuilderFactory documentBuilderFactory = null;
        try {
            documentBuilderFactory = DocumentBuilderFactory.newInstance();
            documentBuilderFactory.setNamespaceAware(true);
            documentBuilderFactory.setExpandEntityReferences(false);
            documentBuilder = documentBuilderFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new ServletException("jaxp failed");
        }
        return documentBuilder;
    }

    /**
     * reads the depth header from the request and returns it as a int
     * 
     * @param req
     * @return the depth from the depth header
     */
    protected int getDepth( HttpServletRequest req ) {
        int depth = INFINITY;
        String depthStr = req.getHeader("Depth");
        if (depthStr != null) {
            if (depthStr.equals("0")) {
                depth = 0;
            } else if (depthStr.equals("1")) {
                depth = 1;
            }
        }
        return depth;
    }

    /**
     * URL rewriter.
     * 
     * @param path Path which has to be rewiten
     * @return the rewritten path
     */
    protected String rewriteUrl( String path ) {
        return URL_ENCODER.encode(path);
    }

    /**
     * Get the ETag associated with a file.
     * 
     * @param so StoredObject to get resourceLength, lastModified and a hashCode of StoredObject
     * @return the ETag
     */
    protected String getETag( StoredObject so ) {

        String resourceLength = "";
        String lastModified = "";

        if (so != null && so.isResource()) {
            resourceLength = Long.toString(so.getResourceLength());
            lastModified = Long.toString(so.getLastModified().getTime());
        }

        return "W/\"" + resourceLength + "-" + lastModified + "\"";

    }

    protected String[] getLockIdFromIfHeader( HttpServletRequest req ) {
        String id = req.getHeader("If");
        if (id == null) {
            return null;
        }
        id = id.trim();
        if (id.length() == 0) {
            return null;
        }
        return lockTokensFrom(id.trim());
    }
    
    protected String[] lockTokensFrom(String id) {
        Matcher matcher = MULTI_LOCK_PATTERN.matcher(id);
        if (matcher.matches()) {
            return new String[] {lockIDFromToken(matcher.group(1)), lockIDFromToken(matcher.group(2))};
        }
        matcher = SINGLE_LOCK_PATTERN.matcher(id);
        if (matcher.matches()) {
            return new String[] {lockIDFromToken(matcher.group(1))};
        }
        return null;
    }
    
    protected String lockIDFromToken(String token) {
        Matcher matcher = LOCK_TOKEN_PATTERN.matcher(token);
        return matcher.matches() ? matcher.group(2) : token;
    }

    protected String getLockIdFromLockTokenHeader( HttpServletRequest req ) {
        String id = req.getHeader("Lock-Token");
        if (id == null || id.trim().length() == 0) {
            return id;
        }
        return lockIDFromToken(id);
    }

    /**
     * Checks if locks on resources at the given path exists and if so checks the If-Header to make sure the If-Header corresponds
     * to the locked resource. Returning true if no lock exists or the If-Header is corresponding to the locked resource
     * 
     * @param transaction
     * @param req Servlet request
     * @param resourceLocks
     * @param path path to the resource
     * @return true if no lock on a resource with the given path exists or if the If-Header corresponds to the locked resource
     * @throws IOException
     * @throws LockFailedException
     */
    protected boolean isUnlocked( ITransaction transaction,
                                  HttpServletRequest req,
                                  IResourceLocks resourceLocks,
                                  String path ) throws IOException, LockFailedException {

        LockedObject resourceLock = resourceLocks.getLockedObjectByPath(transaction, path);
        if (resourceLock == null || resourceLock.isShared()) {
            return true;
        }

        // the resource is locked
        String[] requestLockTokens = getLockIdFromIfHeader(req);
        String requestLockToken = null;
        if (requestLockTokens != null) {
            requestLockToken = requestLockTokens[0];
            LockedObject lockedObjectByToken = resourceLocks.getLockedObjectByID(transaction, requestLockToken);
            return lockedObjectByToken != null && lockedObjectByToken.equals(resourceLock);
        }
        return false;
    }

    /**
     * Send a multistatus element containing a complete error report to the client. If the errorList contains only one error, send
     * the error directly without wrapping it in a multistatus message.
     * 
     * @param req Servlet request
     * @param resp Servlet response
     * @param errorList List of error to be displayed
     * @throws IOException
     */
    protected void sendReport( HttpServletRequest req,
                               HttpServletResponse resp,
                               Hashtable<String, Integer> errorList ) throws IOException {

        if (errorList.size() == 1) {
            int code = errorList.elements().nextElement();
            String statusText = WebdavStatus.getStatusText(code);
            if (statusText != null && !statusText.trim().isEmpty()) {
                resp.sendError(code, statusText);
            } else {
                resp.sendError(code);
            }
        } else {
            resp.setStatus(WebdavStatus.SC_MULTI_STATUS);

            String absoluteUri = req.getRequestURI();
            // String relativePath = getRelativePath(req);

            HashMap<String, String> namespaces = new HashMap<String, String>();
            namespaces.put("DAV:", "D");

            XMLWriter generatedXML = new XMLWriter(namespaces);
            generatedXML.writeXMLHeader();

            generatedXML.writeElement("DAV::multistatus", XMLWriter.OPENING);

            Enumeration<String> pathList = errorList.keys();
            while (pathList.hasMoreElements()) {

                String errorPath = pathList.nextElement();
                int errorCode = errorList.get(errorPath);

                generatedXML.writeElement("DAV::response", XMLWriter.OPENING);

                generatedXML.writeElement("DAV::href", XMLWriter.OPENING);
                String toAppend = null;
                if (absoluteUri.endsWith(errorPath)) {
                    toAppend = absoluteUri;

                } else if (absoluteUri.contains(errorPath)) {

                    int endIndex = absoluteUri.indexOf(errorPath) + errorPath.length();
                    toAppend = absoluteUri.substring(0, endIndex);
                }

                if (toAppend != null && !toAppend.startsWith("/") && !toAppend.startsWith("http:")) {
                    toAppend = "/" + toAppend;
                }
                generatedXML.writeText(errorPath);
                generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);
                generatedXML.writeElement("DAV::status", XMLWriter.OPENING);
                generatedXML.writeText("HTTP/1.1 " + errorCode + " " + WebdavStatus.getStatusText(errorCode));
                generatedXML.writeElement("DAV::status", XMLWriter.CLOSING);

                generatedXML.writeElement("DAV::response", XMLWriter.CLOSING);

            }

            generatedXML.writeElement("DAV::multistatus", XMLWriter.CLOSING);

            Writer writer = resp.getWriter();
            writer.write(generatedXML.toString());
            writer.close();
        }
    }

}
