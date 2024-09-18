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

import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.IWebdavStore;
import peergos.server.webdav.modeshape.webdav.StoredObject;
import peergos.server.webdav.modeshape.webdav.WebdavStatus;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLWriter;
import peergos.server.webdav.modeshape.webdav.locking.IResourceLocks;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.logging.Level;

public class DoLock extends AbstractMethod {

    private final IWebdavStore store;
    private final IResourceLocks resourceLocks;
    private final boolean readOnly;

    private boolean macLockRequest = false;

    private boolean exclusive = false;
    private String type = null;
    private String lockOwner = null;

    private String path = null;
    private String parentPath = null;

    private String userAgent = null;

    public DoLock( IWebdavStore store,
                   IResourceLocks resourceLocks,
                   boolean readOnly ) {
        this.store = store;
        this.resourceLocks = resourceLocks;
        this.readOnly = readOnly;
    }

    @Override
    public void execute( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {
        logger.fine("-- " + this.getClass().getName());

        if (readOnly) {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        } else {
            path = getRelativePath(req);
            parentPath = getParentPath(getCleanPath(path));

            if (!isUnlocked(transaction, req, resourceLocks, path)) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return; // resource is locked
            }

            if (!isUnlocked(transaction, req, resourceLocks, parentPath)) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return; // parent is locked
            }

            // Mac OS Finder (whether 10.4.x or 10.5) can't store files
            // because executing a LOCK without lock information causes a
            // SC_BAD_REQUEST
            userAgent = req.getHeader("User-Agent");
            if (userAgent != null && userAgent.contains("Darwin")) {
                macLockRequest = true;

                String timeString = Long.toString(System.currentTimeMillis());
                lockOwner = userAgent.concat(timeString);
            }

            String tempLockOwner = "doLock" + System.currentTimeMillis() + req.toString();
            if (resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                try {
                    if (req.getHeader("If") != null) {
                        doRefreshLock(transaction, req, resp);
                    } else {
                        doLock(transaction, req, resp);
                    }
                } catch (LockFailedException e) {
                    resp.sendError(WebdavStatus.SC_LOCKED);
                    logger.log(Level.WARNING, e, () -> "Lockfailed exception");
                } finally {
                    resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
                }
            }
        }
    }

    private void doLock( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {

        StoredObject so = store.getStoredObject(transaction, path);

        if (so != null) {
            doLocking(transaction, req, resp);
        } else {
            // resource doesn't exist, null-resource lock
            doNullResourceLock(transaction, req, resp);
        }

        so = null;
        exclusive = false;
        type = null;
        lockOwner = null;

    }

    private void doLocking( ITransaction transaction,
                            HttpServletRequest req,
                            HttpServletResponse resp ) throws IOException {

        // Tests if LockObject on requested path exists, and if so, tests
        // exclusivity
        LockedObject lo = resourceLocks.getLockedObjectByPath(transaction, path);
        if (lo != null) {
            if (lo.isExclusive()) {
                sendLockFailError(req, resp);
                return;
            }
        }
        try {
            // Thats the locking itself
            executeLock(transaction, req, resp);

        } catch (ServletException e) {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            logger.fine(e.toString());
        } catch (LockFailedException e) {
            sendLockFailError(req, resp);
        } finally {
            lo = null;
        }

    }

    private void doNullResourceLock( ITransaction transaction,
                                     HttpServletRequest req,
                                     HttpServletResponse resp ) throws IOException {

        StoredObject parentSo, nullSo = null;

        try {
            parentSo = store.getStoredObject(transaction, parentPath);
            if (parentPath != null && parentSo == null) {
                store.createFolder(transaction, parentPath);
            } else if (parentPath != null && parentSo != null && parentSo.isResource()) {
                resp.sendError(WebdavStatus.SC_PRECONDITION_FAILED);
                return;
            }

            nullSo = store.getStoredObject(transaction, path);
            if (nullSo == null) {
                // resource doesn't exist
                store.createResource(transaction, path);

                // Transmit expects 204 response-code, not 201
                if (userAgent != null && userAgent.contains("Transmit")) {
                    logger.fine("DoLock.execute() : do workaround for user agent '" + userAgent + "'");
                    resp.setStatus(WebdavStatus.SC_NO_CONTENT);
                } else {
                    resp.setStatus(WebdavStatus.SC_CREATED);
                }

            } else {
                // resource already exists, could not execute null-resource lock
                sendLockFailError(req, resp);
                return;
            }
            nullSo = store.getStoredObject(transaction, path);
            if (nullSo == null) {
                resp.setStatus(WebdavStatus.SC_NOT_FOUND);
            } else {
                // define the newly created resource as null-resource
                nullSo.setNullResource(true);

                // Thats the locking itself
                executeLock(transaction, req, resp);
            }
        } catch (LockFailedException e) {
            sendLockFailError(req, resp);
        } catch (WebdavException e) {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            logger.log(Level.WARNING, e, () -> "Webdav exception");
        } catch (ServletException e) {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            logger.log(Level.WARNING, e, () -> "Servlet exception");
        } finally {
            parentSo = null;
            nullSo = null;
        }
    }

    private void doRefreshLock( ITransaction transaction,
                                HttpServletRequest req,
                                HttpServletResponse resp ) throws IOException, LockFailedException {

        String[] lockTokens = getLockIdFromIfHeader(req);
        String lockToken = null;
        if (lockTokens != null) {
            lockToken = lockTokens[0];
        }

        if (lockToken != null) {
            // Getting LockObject of specified lockToken in If header
            LockedObject refreshLo = resourceLocks.getLockedObjectByID(transaction, lockToken);
            if (refreshLo != null) {
                int timeout = getTimeout(req);

                refreshLo.refreshTimeout(timeout);
                // sending success response
                generateXMLReport(resp, refreshLo);

                refreshLo = null;
            } else {
                // no LockObject to given lockToken
                resp.sendError(WebdavStatus.SC_PRECONDITION_FAILED);
            }

        } else {
            resp.sendError(WebdavStatus.SC_PRECONDITION_FAILED);
        }
    }

    // ------------------------------------------------- helper methods

    private void executeLock( ITransaction transaction,
                              HttpServletRequest req,
                              HttpServletResponse resp ) throws LockFailedException, IOException, ServletException {

        // Mac OS lock request workaround
        if (macLockRequest) {
            logger.fine("DoLock.execute() : do workaround for user agent '" + userAgent + "'");

            doMacLockRequestWorkaround(transaction, req, resp);
        } else {
            // Getting LockInformation from request
            if (getLockInformation(req, resp)) {
                int depth = getDepth(req);
                int lockDuration = getTimeout(req);

                boolean lockSuccess = false;
                if (exclusive) {
                    lockSuccess = resourceLocks.exclusiveLock(transaction, path, lockOwner, depth, lockDuration);
                } else {
                    lockSuccess = resourceLocks.sharedLock(transaction, path, lockOwner, depth, lockDuration);
                }

                if (lockSuccess) {
                    // Locks successfully placed - return information about
                    LockedObject lo = resourceLocks.getLockedObjectByPath(transaction, path);
                    if (lo != null) {
                        generateXMLReport(resp, lo);
                    } else {
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                    }
                } else {
                    sendLockFailError(req, resp);

                    throw new LockFailedException();
                }
            } else {
                // information for LOCK could not be read successfully
                resp.setContentType("text/xml; charset=UTF-8");
                resp.sendError(WebdavStatus.SC_BAD_REQUEST);
            }
        }
    }

    private boolean getLockInformation( HttpServletRequest req,
                                        HttpServletResponse resp ) throws ServletException, IOException {

        Node lockInfoNode = null;
        DocumentBuilder documentBuilder = null;

        documentBuilder = getDocumentBuilder();
        try {
            Document document = documentBuilder.parse(new InputSource(req.getInputStream()));

            // Get the root element of the document
            lockInfoNode = document.getDocumentElement();

            if (lockInfoNode != null) {
                NodeList childList = lockInfoNode.getChildNodes();
                Node lockScopeNode = null;
                Node lockTypeNode = null;
                Node lockOwnerNode = null;

                Node currentNode = null;
                String nodeName = null;

                for (int i = 0; i < childList.getLength(); i++) {
                    currentNode = childList.item(i);

                    if (currentNode.getNodeType() == Node.ELEMENT_NODE || currentNode.getNodeType() == Node.TEXT_NODE) {

                        nodeName = currentNode.getNodeName();

                        if (nodeName.endsWith("locktype")) {
                            lockTypeNode = currentNode;
                        }
                        if (nodeName.endsWith("lockscope")) {
                            lockScopeNode = currentNode;
                        }
                        if (nodeName.endsWith("owner")) {
                            lockOwnerNode = currentNode;
                        }
                    } else {
                        return false;
                    }
                }

                if (lockScopeNode != null) {
                    String scope = null;
                    childList = lockScopeNode.getChildNodes();
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                            scope = currentNode.getNodeName();

                            if (scope.endsWith("exclusive")) {
                                exclusive = true;
                            } else if (scope.equals("shared")) {
                                exclusive = false;
                            }
                        }
                    }
                    if (scope == null) {
                        return false;
                    }

                } else {
                    return false;
                }

                if (lockTypeNode != null) {
                    childList = lockTypeNode.getChildNodes();
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        if (currentNode.getNodeType() == Node.ELEMENT_NODE) {
                            type = currentNode.getNodeName();

                            if (type.endsWith("write")) {
                                type = "write";
                            } else if (type.equals("read")) {
                                type = "read";
                            }
                        }
                    }
                    if (type == null) {
                        return false;
                    }
                } else {
                    return false;
                }

                if (lockOwnerNode != null) {
                    childList = lockOwnerNode.getChildNodes();
                    for (int i = 0; i < childList.getLength(); i++) {
                        currentNode = childList.item(i);

                        switch (currentNode.getNodeType()) {
                            case Node.ELEMENT_NODE:
                                lockOwner = currentNode.getFirstChild().getNodeValue();
                                break;
                            case Node.TEXT_NODE:
                                lockOwner = currentNode.getNodeValue();
                                break;
                        }
                    }
                }
                if (lockOwner == null) {
                    return false;
                }
            } else {
                return false;
            }

        } catch (DOMException e) {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            logger.log(Level.WARNING, e, () -> "DOM exception");
            return false;
        } catch (SAXException e) {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            logger.log(Level.WARNING, e, () -> "SAX exception");
            return false;
        }

        return true;
    }

    private int getTimeout( HttpServletRequest req ) {

        int lockDuration = DEFAULT_TIMEOUT;
        String lockDurationStr = req.getHeader("Timeout");

        if (lockDurationStr == null) {
            lockDuration = DEFAULT_TIMEOUT;
        } else {
            int commaPos = lockDurationStr.indexOf(',');
            // if multiple timeouts, just use the first one
            if (commaPos != -1) {
                lockDurationStr = lockDurationStr.substring(0, commaPos);
            }
            if (lockDurationStr.startsWith("Second-")) {
                lockDuration = Integer.valueOf(lockDurationStr.substring(7));
            } else {
                if (lockDurationStr.equalsIgnoreCase("infinity")) {
                    lockDuration = MAX_TIMEOUT;
                } else {
                    try {
                        lockDuration = Integer.valueOf(lockDurationStr);
                    } catch (NumberFormatException e) {
                        lockDuration = MAX_TIMEOUT;
                    }
                }
            }
            if (lockDuration <= 0) {
                lockDuration = DEFAULT_TIMEOUT;
            }
            if (lockDuration > MAX_TIMEOUT) {
                lockDuration = MAX_TIMEOUT;
            }
        }
        return lockDuration;
    }

    private void generateXMLReport( HttpServletResponse resp,
                                    LockedObject lockedObject ) throws IOException {

        HashMap<String, String> namespaces = new HashMap<String, String>();
        namespaces.put("DAV:", "D");

        resp.setStatus(WebdavStatus.SC_OK);
        resp.setContentType("text/xml; charset=UTF-8");

        XMLWriter generatedXML = new XMLWriter(resp.getWriter(), namespaces);
        generatedXML.writeXMLHeader();
        generatedXML.writeElement("DAV::prop", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::lockdiscovery", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::activelock", XMLWriter.OPENING);

        generatedXML.writeElement("DAV::locktype", XMLWriter.OPENING);
        generatedXML.writeProperty("DAV::" + type);
        generatedXML.writeElement("DAV::locktype", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::lockscope", XMLWriter.OPENING);
        if (exclusive) {
            generatedXML.writeProperty("DAV::exclusive");
        } else {
            generatedXML.writeProperty("DAV::shared");
        }
        generatedXML.writeElement("DAV::lockscope", XMLWriter.CLOSING);

        int depth = lockedObject.getLockDepth();

        generatedXML.writeElement("DAV::depth", XMLWriter.OPENING);
        if (depth == INFINITY) {
            generatedXML.writeText("Infinity");
        } else {
            generatedXML.writeText(String.valueOf(depth));
        }
        generatedXML.writeElement("DAV::depth", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::owner", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::href", XMLWriter.OPENING);
        generatedXML.writeText(lockOwner);
        generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::owner", XMLWriter.CLOSING);

        long timeout = lockedObject.getTimeoutMillis();
        generatedXML.writeElement("DAV::timeout", XMLWriter.OPENING);
        generatedXML.writeText("Second-" + timeout / 1000);
        generatedXML.writeElement("DAV::timeout", XMLWriter.CLOSING);

        String lockToken = lockedObject.getID();
        generatedXML.writeElement("DAV::locktoken", XMLWriter.OPENING);
        generatedXML.writeElement("DAV::href", XMLWriter.OPENING);
        generatedXML.writeText("opaquelocktoken:" + lockToken);
        generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::locktoken", XMLWriter.CLOSING);

        generatedXML.writeElement("DAV::activelock", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::lockdiscovery", XMLWriter.CLOSING);
        generatedXML.writeElement("DAV::prop", XMLWriter.CLOSING);

        resp.addHeader("Lock-Token", "<opaquelocktoken:" + lockToken + ">");

        generatedXML.sendData();

    }

    private void doMacLockRequestWorkaround( ITransaction transaction,
                                             HttpServletRequest req,
                                             HttpServletResponse resp ) throws LockFailedException, IOException {
        LockedObject lo;
        int depth = getDepth(req);
        int lockDuration = getTimeout(req);
        if (lockDuration < 0 || lockDuration > MAX_TIMEOUT) {
            lockDuration = DEFAULT_TIMEOUT;
        }

        boolean lockSuccess = false;
        lockSuccess = resourceLocks.exclusiveLock(transaction, path, lockOwner, depth, lockDuration);

        if (lockSuccess) {
            // Locks successfully placed - return information about
            lo = resourceLocks.getLockedObjectByPath(transaction, path);
            if (lo != null) {
                generateXMLReport(resp, lo);
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            // Locking was not successful
            sendLockFailError(req, resp);
        }
    }

    private void sendLockFailError( HttpServletRequest req,
                                    HttpServletResponse resp ) throws IOException {
        Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();
        errorList.put(path, WebdavStatus.SC_LOCKED);
        sendReport(req, resp, errorList);
    }

}
