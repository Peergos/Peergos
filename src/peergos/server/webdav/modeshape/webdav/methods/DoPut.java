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

import org.peergos.util.Pair;
import peergos.server.simulation.InputStreamAsyncReader;
import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.IWebdavStore;
import peergos.server.webdav.modeshape.webdav.StoredObject;
import peergos.server.webdav.modeshape.webdav.WebdavStatus;
import peergos.server.webdav.modeshape.webdav.exceptions.AccessDeniedException;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.webdav.modeshape.webdav.locking.IResourceLocks;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Optional;
import java.util.logging.Level;

public class DoPut extends AbstractMethod {

    private final IWebdavStore store;
    private final IResourceLocks resourceLocks;
    private final boolean readOnly;
    private final boolean lazyFolderCreationOnPut;

    private String userAgent;

    public DoPut( IWebdavStore store,
                  IResourceLocks resLocks,
                  boolean readOnly,
                  boolean lazyFolderCreationOnPut ) {
        this.store = store;
        this.resourceLocks = resLocks;
        this.readOnly = readOnly;
        this.lazyFolderCreationOnPut = lazyFolderCreationOnPut;
    }

    @Override
    public void execute( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {
        logger.fine("-- " + this.getClass().getName());

        if (!readOnly) {
            String path = getRelativePath(req);
            String parentPath = getParentPath(path);

            userAgent = req.getHeader("User-Agent");

            if (isOSXFinder() && req.getContentLength() == 0) {
                // OS X Finder sends 2 PUTs; first has 0 content, second has content.
                // This is the first one, so we'll ignore it ...
                logger.fine("-- First of multiple OS-X Finder PUT calls at " + path);
            }

            Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

            if (isOSXFinder()) {
                // OS X Finder sends 2 PUTs; first has 0 content, second has content.
                // This is the second one that was preceded by a LOCK, so don't need to check the locks ...
            } else {
                if (!isUnlocked(transaction, req, resourceLocks, parentPath)) {
                    logger.fine("-- Locked parent at " + path);
                    resp.setStatus(WebdavStatus.SC_LOCKED);
                    return; // parent is locked
                }

                if (!isUnlocked(transaction, req, resourceLocks, path)) {
                    logger.fine("-- Locked resource at " + path);
                    resp.setStatus(WebdavStatus.SC_LOCKED);
                    return; // resource is locked
                }
            }

            String tempLockOwner = "doPut" + System.currentTimeMillis() + req.toString();
            if (resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                StoredObject parentSo, so = null;
                try {
                    parentSo = store.getStoredObject(transaction, parentPath);
                    if (parentPath != null && parentSo != null && parentSo.isResource()) {
                        resp.sendError(WebdavStatus.SC_FORBIDDEN);
                        return;

                    } else if (parentPath != null && parentSo == null && lazyFolderCreationOnPut) {
                        store.createFolder(transaction, parentPath);

                    } else if (parentPath != null && parentSo == null && !lazyFolderCreationOnPut) {
                        errorList.put(parentPath, WebdavStatus.SC_NOT_FOUND);
                        sendReport(req, resp, errorList);
                        return;
                    }

                    logger.fine("-- Looking for the stored object at " + path);
                    so = store.getStoredObject(transaction, path);

                    if (so == null) {
                        logger.fine("-- Creating resource in the store at " + path);
                        store.createResource(transaction, path);
                        // resp.setStatus(WebdavStatus.SC_CREATED);
                    } else {
                        // This has already been created, just update the data
                        logger.fine("-- There is already a resource at " + path);
                        if (so.isNullResource()) {

                            LockedObject nullResourceLo = resourceLocks.getLockedObjectByPath(transaction, path);
                            if (nullResourceLo == null) {
                                logger.fine("-- Unable to obtain resource lock object at " + path);
                                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                                return;
                            }
                            logger.fine("-- Found resource lock object at " + path);
                            String nullResourceLockToken = nullResourceLo.getID();
                            String[] lockTokens = getLockIdFromIfHeader(req);
                            String lockToken = null;
                            if (lockTokens != null) {
                                lockToken = lockTokens[0];
                            } else {
                                logger.fine("-- No lock tokens found in resource lock object at " + path);
                                resp.sendError(WebdavStatus.SC_BAD_REQUEST);
                                return;
                            }
                            if (lockToken.equals(nullResourceLockToken)) {
                                so.setNullResource(false);
                                so.setFolder(false);

                                String[] nullResourceLockOwners = nullResourceLo.getOwner();
                                String owner = null;
                                if (nullResourceLockOwners != null) {
                                    owner = nullResourceLockOwners[0];
                                }

                                if (!resourceLocks.unlock(transaction, lockToken, owner)) {
                                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                                }
                            } else {
                                errorList.put(path, WebdavStatus.SC_LOCKED);
                                sendReport(req, resp, errorList);
                            }
                        } else {
                            logger.fine("-- Found a lock for the (existing) resource at " + path);
                        }
                    }
                    // User-Agent workarounds
                    doUserAgentWorkaround(resp);

                    // setting resourceContent
                    Optional<String> header = Optional.ofNullable(req.getHeader("Content-Range")).map(t -> t.trim());
                    long start=0, end=0, length=Optional.ofNullable(req.getHeader("X-Expected-Entity-Length"))
                            .map(Long::parseLong)
                            .orElse(0L);
                    if (header.isPresent() && ! header.get().contains("-")) {
                        start = Long.parseLong(header.get().substring("bytes".length()));
                    } else if (header.isPresent()){
                        String[] split = header.get().split("-");
                        start = Long.parseLong(split[0].substring("bytes".length()));
                        if (split[1].contains("/")) {
                            int slash = split[1].indexOf("/");
                            end = Long.parseLong(split[1].substring(0, slash));
                            length = Long.parseLong(split[1].substring(slash));
                        } else {
                            end = Long.parseLong(split[1]);
                            length = end-start;
                        }
                    }
                    Optional<String> lengthHeader = Optional.ofNullable(req.getHeader("Content-Length")).map(t -> t.trim());
                    if (end == 0 && lengthHeader.isPresent()) {
                        end = Long.parseLong(lengthHeader.get());
                        length = end;
                    }
                    System.out.println("start, end, length = " + start + "," + end + "," + length);
                    long resourceLength = store.setResourceContent(transaction, path, new Pair<>(new InputStreamAsyncReader(req.getInputStream()), length), null, null);

                    so = store.getStoredObject(transaction, path);
                    if (so == null) {
                        resp.setStatus(WebdavStatus.SC_NOT_FOUND);
                    } else if (resourceLength != -1) {
                        so.setResourceLength(resourceLength);
                    }
                    // Now lets report back what was actually saved

                } catch (AccessDeniedException e) {
                    logger.log(Level.FINE, e, () -> "Access denied when working with " + path);
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (WebdavException e) {
                    logger.log(Level.FINE, e, () -> "WebDAV exception when working with " + path);
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
                }
            } else {
                logger.fine("Lock was not acquired when working with " + path);
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            logger.fine("Readonly=" + readOnly);
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        }

    }

    /**
     * @param resp
     */
    private void doUserAgentWorkaround( HttpServletResponse resp ) {
        if (isOSXFinder()) {
            logger.fine("DoPut.execute() : do workaround for user agent '" + userAgent + "'");
            resp.setStatus(WebdavStatus.SC_CREATED);
        } else if (userAgent != null && userAgent.contains("Transmit")) {
            // Transmit also uses WEBDAVFS 1.x.x but crashes
            // with SC_CREATED response
            logger.fine("DoPut.execute() : do workaround for user agent '" + userAgent + "'");
            resp.setStatus(WebdavStatus.SC_NO_CONTENT);
        } else {
            resp.setStatus(WebdavStatus.SC_CREATED);
        }
    }

    private boolean isOSXFinder() {
        return (userAgent != null && userAgent.contains("WebDAVFS") && !userAgent.contains("Transmit"));
    }

}
