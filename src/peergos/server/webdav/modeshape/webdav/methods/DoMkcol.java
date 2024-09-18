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
import peergos.server.webdav.modeshape.webdav.locking.IResourceLocks;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Hashtable;
import java.util.logging.Level;

public class DoMkcol extends AbstractMethod {

    private final IWebdavStore store;
    private final IResourceLocks resourceLocks;
    private boolean readOnly;

    public DoMkcol( IWebdavStore store,
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
            return;
        }
        String path = getRelativePath(req);
        String parentPath = getParentPath(getCleanPath(path));

        Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

        if (!isUnlocked(transaction, req, resourceLocks, parentPath)) {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
            return;
        }

        String tempLockOwner = "doMkcol" + System.currentTimeMillis() + req.toString();

        StoredObject parentSo, so = null;
        try {
            if (!resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                logger.finest("Resource lock failed.");
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                return;
            }
            parentSo = store.getStoredObject(transaction, parentPath);
            if (parentSo == null) {
                // parent not exists
                logger.finest("Parent not exists for " + path);
                resp.sendError(WebdavStatus.SC_CONFLICT);
                return;
            }
            if (parentPath != null && parentSo.isFolder()) {
                so = store.getStoredObject(transaction, path);
                if (so == null) {
                    store.createFolder(transaction, path);
                    resp.setStatus(WebdavStatus.SC_CREATED);
                    return;
                }
                // object already exists
                if (so.isNullResource()) {
                    LockedObject nullResourceLo = resourceLocks.getLockedObjectByPath(transaction, path);
                    if (nullResourceLo == null) {
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                        return;
                    }
                    String nullResourceLockToken = nullResourceLo.getID();
                    String[] lockTokens = getLockIdFromIfHeader(req);
                    String lockToken = null;
                    if (lockTokens != null) {
                        lockToken = lockTokens[0];
                    } else {
                        resp.sendError(WebdavStatus.SC_BAD_REQUEST);
                        return;
                    }
                    if (lockToken.equals(nullResourceLockToken)) {
                        so.setNullResource(false);
                        so.setFolder(true);

                        String[] nullResourceLockOwners = nullResourceLo.getOwner();
                        String owner = null;
                        if (nullResourceLockOwners != null) {
                            owner = nullResourceLockOwners[0];
                        }

                        if (resourceLocks.unlock(transaction, lockToken, owner)) {
                            resp.setStatus(WebdavStatus.SC_CREATED);
                        } else {
                            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                        }

                    } else {
                        errorList.put(path, WebdavStatus.SC_LOCKED);
                        sendReport(req, resp, errorList);
                    }

                } else {
                    String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
                    resp.addHeader("Allow", methodsAllowed);
                    resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                }

            } else if (parentPath != null && parentSo.isResource()) {
                String methodsAllowed = DeterminableMethod.determineMethodsAllowed(parentSo);
                resp.addHeader("Allow", methodsAllowed);
                resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);

            } else {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
            }
        } catch (AccessDeniedException e) {
            logger.log(Level.FINEST, e, () -> "Access denied for " + path);
            resp.sendError(WebdavStatus.SC_FORBIDDEN);
        } catch (WebdavException e) {
            logger.log(Level.FINEST, e, () -> "Error for " + path);
            resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
        } finally {
            resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
        }
    }

}
