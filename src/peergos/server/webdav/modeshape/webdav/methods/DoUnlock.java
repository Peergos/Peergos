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
import peergos.server.webdav.modeshape.webdav.locking.IResourceLocks;
import peergos.server.webdav.modeshape.webdav.locking.LockedObject;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.logging.Level;

public class DoUnlock extends DeterminableMethod {

    private final IWebdavStore store;
    private final IResourceLocks resourceLocks;
    private final boolean readOnly;

    public DoUnlock( IWebdavStore store,
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

            String path = getRelativePath(req);
            String tempLockOwner = "doUnlock" + System.currentTimeMillis() + req.toString();
            try {
                if (resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {

                    String lockId = getLockIdFromLockTokenHeader(req);
                    LockedObject lo;
                    if (lockId != null && ((lo = resourceLocks.getLockedObjectByID(transaction, lockId)) != null)) {

                        String[] owners = lo.getOwner();
                        String owner = null;
                        if (lo.isShared()) {
                            // more than one owner is possible
                            if (owners != null) {
                                for (int i = 0; i < owners.length; i++) {
                                    // remove owner from LockedObject
                                    lo.removeLockedObjectOwner(owners[i]);
                                }
                            }
                        } else {
                            // exclusive, only one lock owner
                            if (owners != null) {
                                owner = owners[0];
                            }
                        }

                        if (resourceLocks.unlock(transaction, lockId, owner)) {
                            StoredObject so = store.getStoredObject(transaction, path);
                            if (so == null) {
                                resp.setStatus(WebdavStatus.SC_NOT_FOUND);
                            } else {
                                if (so.isNullResource()) {
                                    store.removeObject(transaction, path);
                                }
                                resp.setStatus(WebdavStatus.SC_NO_CONTENT);
                            }
                        } else {
                            logger.fine("DoUnlock failure at " + lo.getPath());
                            resp.sendError(WebdavStatus.SC_METHOD_FAILURE);
                        }

                    } else {
                        resp.sendError(WebdavStatus.SC_BAD_REQUEST);
                    }
                }
            } catch (LockFailedException e) {
                logger.log(Level.WARNING, e, () -> "Cannot unlock resource");
            } finally {
                resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
            }
        }
    }

}
