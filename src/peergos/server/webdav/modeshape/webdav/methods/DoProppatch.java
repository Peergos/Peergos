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
import peergos.server.webdav.modeshape.webdav.exceptions.AccessDeniedException;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.webdav.modeshape.webdav.fromcatalina.RequestUtil;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLHelper;
import peergos.server.webdav.modeshape.webdav.fromcatalina.XMLWriter;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;
import peergos.server.webdav.modeshape.webdav.locking.ResourceLocks;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class DoProppatch extends AbstractMethod {

    private boolean readOnly;
    private IWebdavStore store;
    private ResourceLocks resourceLocks;

    public DoProppatch( IWebdavStore store,
                        ResourceLocks resLocks,
                        boolean readOnly ) {
        this.readOnly = readOnly;
        this.store = store;
        this.resourceLocks = resLocks;
    }

    @Override
    public void execute( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {
        logger.fine("-- " + this.getClass().getName());

        if (readOnly) {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
            return;
        }

        String path = getRelativePath(req);
        String parentPath = getParentPath(getCleanPath(path));

        Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

        if (!isUnlocked(transaction, req, resourceLocks, parentPath)) {
            resp.setStatus(WebdavStatus.SC_LOCKED);
            return; // parent is locked
        }

        if (!isUnlocked(transaction, req, resourceLocks, path)) {
            resp.setStatus(WebdavStatus.SC_LOCKED);
            return; // resource is locked
        }

        // Retrieve the resources
        String tempLockOwner = "doProppatch" + System.currentTimeMillis() + req.toString();

        if (resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
            StoredObject so = null;
            LockedObject lo = null;
            try {
                so = store.getStoredObject(transaction, path);
                lo = resourceLocks.getLockedObjectByPath(transaction, getCleanPath(path));

                if (so == null) {
                    resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                    return;
                    // we do not to continue since there is no root
                    // resource
                }

                if (so.isNullResource()) {
                    String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
                    resp.addHeader("Allow", methodsAllowed);
                    resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                    return;
                }

                String[] lockTokens = getLockIdFromIfHeader(req);
                boolean lockTokenMatchesIfHeader = (lockTokens != null && lockTokens[0].equals(lo.getID()));
                if (lo != null && lo.isExclusive() && !lockTokenMatchesIfHeader) {
                    // Object on specified path is LOCKED
                    errorList = new Hashtable<String, Integer>();
                    errorList.put(path, WebdavStatus.SC_LOCKED);
                    sendReport(req, resp, errorList);
                    return;
                }

                Map<String, Object> propertiesToSet = new HashMap<String, Object>();
                List<String> propertiesToRemove = new ArrayList<String>();
                List<String> allProperties = new Vector<String>();
                Map<String, String> response = null;

                path = getCleanPath(getRelativePath(req));

                Node tosetNode = null;
                Node toremoveNode = null;

                if (RequestUtil.streamNotConsumed(req)) {
                    DocumentBuilder documentBuilder = getDocumentBuilder();
                    try {
                        Document document = documentBuilder.parse(new InputSource(req.getInputStream()));
                        // Get the root element of the document
                        Element rootElement = document.getDocumentElement();

                        tosetNode = XMLHelper.findSubElement(XMLHelper.findSubElement(rootElement, "set"), "prop");
                        if (tosetNode != null) {
                            propertiesToSet = XMLHelper.getPropertiesWithValuesFromXML(tosetNode);
                        }

                        toremoveNode = XMLHelper.findSubElement(XMLHelper.findSubElement(rootElement, "remove"), "prop");
                        if (toremoveNode != null) {
                            propertiesToRemove = XMLHelper.getPropertiesFromXML(toremoveNode);
                        }
                        if (!propertiesToSet.isEmpty() || !propertiesToRemove.isEmpty()) {
                            response = store.setCustomProperties(transaction, path, propertiesToSet, propertiesToRemove);
                        }
                    } catch (Exception e) {
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                        return;
                    }
                } else {
                    // no content: error
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                    return;
                }

                allProperties.addAll(propertiesToSet.keySet());
                allProperties.addAll(propertiesToRemove);

                HashMap<String, String> namespaces = new HashMap<String, String>();
                namespaces.put("DAV:", "D");

                resp.setStatus(WebdavStatus.SC_MULTI_STATUS);
                resp.setContentType("text/xml; charset=UTF-8");

                // Create multistatus object
                XMLWriter generatedXML = new XMLWriter(resp.getWriter(), namespaces);
                generatedXML.writeXMLHeader();
                generatedXML.writeElement("DAV::multistatus", XMLWriter.OPENING);

                generatedXML.writeElement("DAV::response", XMLWriter.OPENING);

                // Generating href element
                generatedXML.writeElement("DAV::href", XMLWriter.OPENING);

                String href = req.getContextPath();
                if ((href.endsWith("/")) && (path.startsWith("/"))) {
                    href += path.substring(1);
                } else {
                    href += path;
                }
                if ((so.isFolder()) && (!href.endsWith("/"))) {
                    href += "/";
                }

                generatedXML.writeText(rewriteUrl(href));

                generatedXML.writeElement("DAV::href", XMLWriter.CLOSING);

                for (String property : allProperties) {
                    generatedXML.writeElement("DAV::propstat", XMLWriter.OPENING);

                    generatedXML.writeElement("DAV::prop", XMLWriter.OPENING);
                    generatedXML.writeElement(property, XMLWriter.NO_CONTENT);
                    generatedXML.writeElement("DAV::prop", XMLWriter.CLOSING);

                    generatedXML.writeElement("DAV::status", XMLWriter.OPENING);
                    generatedXML.writeText(statusForProperty(property, response));
                    generatedXML.writeElement("DAV::status", XMLWriter.CLOSING);

                    generatedXML.writeElement("DAV::propstat", XMLWriter.CLOSING);
                }

                if (response != null && !response.isEmpty()) {
                    String firstErrorMessage = response.entrySet().iterator().next().getValue();
                    generatedXML.writeProperty("DAV::responsedescription", firstErrorMessage);
                }
                generatedXML.writeElement("DAV::response", XMLWriter.CLOSING);
                generatedXML.writeElement("DAV::multistatus", XMLWriter.CLOSING);

                    logger.fine("Sending response: " + generatedXML);
                generatedXML.sendData();
            } catch (AccessDeniedException e) {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            } catch (WebdavException e) {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            } catch (ServletException e) {
                logger.log(Level.WARNING, e, () -> "Cannot create document builder");
            } finally {
                resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
            }
        } else {
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
        }
    }

    private String statusForProperty( String propertyName,
                                      Map<String, String> response ) {
        if (response == null || response.isEmpty()) {
            return "HTTP/1.1 " + WebdavStatus.SC_OK + " " + WebdavStatus.getStatusText(WebdavStatus.SC_OK);
        } else if (response.containsKey(propertyName)) {
            return "HTTP/1.1 " + WebdavStatus.SC_BAD_REQUEST + " " + WebdavStatus.getStatusText(WebdavStatus.SC_BAD_REQUEST);
        } else {
            return "HTTP/1.1 " + WebdavStatus.SC_FAILED_DEPENDENCY + " "
                   + WebdavStatus.getStatusText(WebdavStatus.SC_FAILED_DEPENDENCY);
        }
    }
}
