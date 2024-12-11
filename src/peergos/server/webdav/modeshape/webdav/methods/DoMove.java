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
import peergos.server.webdav.modeshape.webdav.exceptions.ObjectAlreadyExistsException;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.webdav.modeshape.webdav.locking.ResourceLocks;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import peergos.shared.util.PathUtil;

import java.io.IOException;
import java.util.Hashtable;

public class DoMove extends AbstractMethod {

    private final ResourceLocks resourceLocks;
    private final IWebdavStore store;
    private final DoDelete doDelete;
    private final boolean readOnly;

    public DoMove( ResourceLocks resourceLocks,
                   IWebdavStore store,
                   DoDelete doDelete,
                   boolean readOnly ) {
        this.resourceLocks = resourceLocks;
        this.store = store;
        this.doDelete = doDelete;
        this.readOnly = readOnly;
    }

    @Override
    public void execute( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {

        if (!readOnly) {
            logger.fine("-- " + this.getClass().getName());

            String sourcePath = getRelativePath(req);
            Hashtable<String, Integer> errorList = new Hashtable<String, Integer>();

            if (!isUnlocked(transaction, req, resourceLocks, sourcePath)) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return;
            }

            if (!isUnlocked(transaction, req, resourceLocks, PathUtil.get(sourcePath).getParent().toString())) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return;
            }

            String destinationPath = DoCopy.parseDestinationHeader(req, resp);

            if (sourcePath.equals(destinationPath)) {
                resp.sendError(WebdavStatus.SC_FORBIDDEN);
                return;
            }

            if (!isUnlocked(transaction, req, resourceLocks, PathUtil.get(destinationPath).getParent().toString())) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return; // parentDestination is locked
            }

            if (!isUnlocked(transaction, req, resourceLocks, destinationPath)) {
                resp.setStatus(WebdavStatus.SC_LOCKED);
                return; // destination is locked
            }
            boolean overwrite = shouldOverwrite(req);

            String tempLockOwner = "doMove" + System.currentTimeMillis() + req.toString();

            if (resourceLocks.lock(transaction, sourcePath, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                try {
                    if (!resourceLocks.lock(transaction, destinationPath, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                        logger.finest("Resource lock failed.");
                        resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                        return;
                    }
                    StoredObject sourceSo = store.getStoredObject(transaction, sourcePath);
                    // Retrieve the resources
                    if (sourceSo == null) {
                        resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                        return;
                    }

                    if (sourceSo.isNullResource()) {
                        String methodsAllowed = DeterminableMethod.determineMethodsAllowed(sourceSo);
                        resp.addHeader("Allow", methodsAllowed);
                        resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                        return;
                    }

                    errorList = new Hashtable<String, Integer>();

                    StoredObject destinationSo = store.getStoredObject(transaction, destinationPath);

                    if (overwrite) {
                        // Delete destination resource, if it exists
                        if (destinationSo != null) {
                            doDelete.deleteResource(transaction, destinationPath, errorList, req, resp);
                        } else {
                            resp.setStatus(WebdavStatus.SC_CREATED);
                        }
                    } else {
                        // If the destination exists, then it's a conflict
                        if (destinationSo != null) {
                            resp.sendError(WebdavStatus.SC_PRECONDITION_FAILED);
                            return;
                        }
                        resp.setStatus(WebdavStatus.SC_CREATED);
                    }

                    store.moveResource(transaction, sourcePath, destinationPath);

                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (ObjectAlreadyExistsException e) {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND, req.getRequestURI());
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resourceLocks.unlockTemporaryLockedObjects(transaction, sourcePath, tempLockOwner);
                    resourceLocks.unlockTemporaryLockedObjects(transaction, destinationPath, tempLockOwner);
                }
            } else {
                errorList.put(req.getHeader("Destination"), WebdavStatus.SC_LOCKED);
                sendReport(req, resp, errorList);
            }
        } else {
            resp.sendError(WebdavStatus.SC_FORBIDDEN);

        }
    }

    private boolean shouldOverwrite( HttpServletRequest req ) {
        boolean overwrite = true;
        String overwriteHeader = req.getHeader("Overwrite");

        if (overwriteHeader != null) {
            overwrite = overwriteHeader.equalsIgnoreCase("T");
        }
        return overwrite;
    }
}
