/*
 * Copyright 2005-2006 webdav-servlet group.
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

package peergos.server.webdav.modeshape.webdav.locking;

import peergos.server.util.Logging;
import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;

import java.util.Enumeration;
import java.util.Hashtable;
import java.util.logging.Logger;

/**
 * simple locking management for concurrent data access, NOT the webdav locking. ( could that be used instead? ) IT IS ACTUALLY
 * USED FOR DOLOCK
 * 
 * @author re
 */
public class ResourceLocks implements IResourceLocks {

    private static Logger LOG = Logging.LOG();

    /**
     * after creating this much LockedObjects, a cleanup deletes unused LockedObjects
     */
    private final int cleanupLimit = 100000;

    protected int cleanupCounter = 0;

    /**
     * keys: path value: LockedObject from that path
     */
    protected Hashtable<String, LockedObject> locks = new Hashtable<String, LockedObject>();

    /**
     * keys: id value: LockedObject from that id
     */
    protected Hashtable<String, LockedObject> locksByID = new Hashtable<String, LockedObject>();

    /**
     * keys: path value: Temporary LockedObject from that path
     */
    protected Hashtable<String, LockedObject> tempLocks = new Hashtable<String, LockedObject>();

    /**
     * keys: id value: Temporary LockedObject from that id
     */
    protected Hashtable<String, LockedObject> tempLocksByID = new Hashtable<String, LockedObject>();

    // REMEMBER TO REMOVE UNUSED LOCKS FROM THE HASHTABLE AS WELL

    protected LockedObject root = null;

    protected LockedObject tempRoot = null;

    private boolean temporary = true;

    public ResourceLocks() {
        root = new LockedObject(this, "/", true);
        tempRoot = new LockedObject(this, "/", false);
    }

    @Override
    public synchronized boolean lock( ITransaction transaction,
                                      String path,
                                      String owner,
                                      boolean exclusive,
                                      int depth,
                                      int timeout,
                                      boolean temporary ) throws LockFailedException {

        LockedObject lo = null;

        if (temporary) {
            lo = generateTempLockedObjects(path);
            lo.type = "read";
        } else {
            lo = generateLockedObjects(path);
            lo.type = "write";
        }

        if (lo.checkLocks(exclusive, depth)) {

            lo.exclusive = exclusive;
            lo.lockDepth = depth;
            lo.expiresAt = System.currentTimeMillis() + (timeout * 1000);
            if (lo.parent != null) {
                lo.parent.expiresAt = lo.expiresAt;
                if (lo.parent.equals(root)) {
                    LockedObject rootLo = getLockedObjectByPath(transaction, root.getPath());
                    rootLo.expiresAt = lo.expiresAt;
                } else if (lo.parent.equals(tempRoot)) {
                    LockedObject tempRootLo = getTempLockedObjectByPath(transaction, tempRoot.getPath());
                    tempRootLo.expiresAt = lo.expiresAt;
                }
            }
            if (lo.addLockedObjectOwner(owner)) {
                return true;
            }
            LOG.fine("Couldn't set owner \"" + owner + "\" to resource at '" + path + "'");
            return false;
        }
        // can not lock
        LOG.fine("Lock resource at " + path + " failed because" + "\na parent or child resource is currently locked");
        return false;
    }

    @Override
    public synchronized boolean unlock( ITransaction transaction,
                                        String id,
                                        String owner ) {

        if (locksByID.containsKey(id)) {
            String path = locksByID.get(id).getPath();
            if (locks.containsKey(path)) {
                LockedObject lo = locks.get(path);
                lo.removeLockedObjectOwner(owner);

                if (lo.children == null && lo.owner == null) {
                    lo.removeLockedObject();
                }

            } else {
                // there is no lock at that path. someone tried to unlock it
                // anyway. could point to a problem
                LOG.fine("org.modeshape.web.webdav.locking.ResourceLocks.unlock(): no lock for path " + path);
                return false;
            }

            if (cleanupCounter > cleanupLimit) {
                cleanupCounter = 0;
                cleanLockedObjects(root, !temporary);
            }
        }
        checkTimeouts(transaction, !temporary);

        return true;

    }

    @Override
    public synchronized void unlockTemporaryLockedObjects( ITransaction transaction,
                                                           String path,
                                                           String owner ) {
        if (tempLocks.containsKey(path)) {
            LockedObject lo = tempLocks.get(path);
            lo.removeLockedObjectOwner(owner);

        } else {
            // there is no lock at that path. someone tried to unlock it
            // anyway. could point to a problem
            LOG.fine("org.modeshape.web.webdav.locking.ResourceLocks.unlock(): no lock for path " + path);
        }

        if (cleanupCounter > cleanupLimit) {
            cleanupCounter = 0;
            cleanLockedObjects(tempRoot, temporary);
        }

        checkTimeouts(transaction, temporary);

    }

    @Override
    public void checkTimeouts( ITransaction transaction,
                               boolean temporary ) {
        if (!temporary) {
            Enumeration<LockedObject> lockedObjects = locks.elements();
            while (lockedObjects.hasMoreElements()) {
                LockedObject currentLockedObject = lockedObjects.nextElement();

                if (currentLockedObject.expiresAt < System.currentTimeMillis()) {
                    currentLockedObject.removeLockedObject();
                }
            }
        } else {
            Enumeration<LockedObject> lockedObjects = tempLocks.elements();
            while (lockedObjects.hasMoreElements()) {
                LockedObject currentLockedObject = lockedObjects.nextElement();

                if (currentLockedObject.expiresAt < System.currentTimeMillis()) {
                    currentLockedObject.removeTempLockedObject();
                }
            }
        }

    }

    @Override
    public boolean exclusiveLock( ITransaction transaction,
                                  String path,
                                  String owner,
                                  int depth,
                                  int timeout ) throws LockFailedException {
        return lock(transaction, path, owner, true, depth, timeout, false);
    }

    @Override
    public boolean sharedLock( ITransaction transaction,
                               String path,
                               String owner,
                               int depth,
                               int timeout ) throws LockFailedException {
        return lock(transaction, path, owner, false, depth, timeout, false);
    }

    @Override
    public LockedObject getLockedObjectByID( ITransaction transaction,
                                             String id ) {
        if (locksByID.containsKey(id)) {
            return locksByID.get(id);
        }
        return null;
    }

    @Override
    public LockedObject getLockedObjectByPath( ITransaction transaction,
                                               String path ) {
        if (locks.containsKey(path)) {
            return this.locks.get(path);
        }
        return null;
    }

    @Override
    public LockedObject getTempLockedObjectByID( ITransaction transaction,
                                                 String id ) {
        if (tempLocksByID.containsKey(id)) {
            return tempLocksByID.get(id);
        }
        return null;
    }

    @Override
    public LockedObject getTempLockedObjectByPath( ITransaction transaction,
                                                   String path ) {
        if (tempLocks.containsKey(path)) {
            return this.tempLocks.get(path);
        }
        return null;
    }

    /**
     * generates real LockedObjects for the resource at path and its parent folders. does not create new LockedObjects if they
     * already exist
     * 
     * @param path path to the (new) LockedObject
     * @return the LockedObject for path.
     */
    private LockedObject generateLockedObjects( String path ) {
        if (!locks.containsKey(path)) {
            LockedObject returnObject = new LockedObject(this, path, !temporary);
            String parentPath = getParentPath(path);
            if (parentPath != null) {
                LockedObject parentLockedObject = generateLockedObjects(parentPath);
                parentLockedObject.addChild(returnObject);
                returnObject.parent = parentLockedObject;
            }
            return returnObject;
        }
        // there is already a LockedObject on the specified path
        return this.locks.get(path);
    }

    /**
     * generates temporary LockedObjects for the resource at path and its parent folders. does not create new LockedObjects if
     * they already exist
     * 
     * @param path path to the (new) LockedObject
     * @return the LockedObject for path.
     */
    private LockedObject generateTempLockedObjects( String path ) {
        if (!tempLocks.containsKey(path)) {
            LockedObject returnObject = new LockedObject(this, path, temporary);
            String parentPath = getParentPath(path);
            if (parentPath != null) {
                LockedObject parentLockedObject = generateTempLockedObjects(parentPath);
                parentLockedObject.addChild(returnObject);
                returnObject.parent = parentLockedObject;
            }
            return returnObject;
        }
        // there is already a LockedObject on the specified path
        return this.tempLocks.get(path);
    }

    /**
     * deletes unused LockedObjects and resets the counter. works recursively starting at the given LockedObject
     * 
     * @param lo LockedObject
     * @param temporary Clean temporary or real locks
     * @return if cleaned
     */
    private boolean cleanLockedObjects( LockedObject lo,
                                        boolean temporary ) {

        if (lo.children == null) {
            if (lo.owner == null) {
                if (temporary) {
                    lo.removeTempLockedObject();
                } else {
                    lo.removeLockedObject();
                }

                return true;
            }
            return false;
        }
        boolean canDelete = true;
        int limit = lo.children.length;
        for (int i = 0; i < limit; i++) {
            if (!cleanLockedObjects(lo.children[i], temporary)) {
                canDelete = false;
            } else {

                // because the deleting shifts the array
                i--;
                limit--;
            }
        }
        if (canDelete) {
            if (lo.owner == null) {
                if (temporary) {
                    lo.removeTempLockedObject();
                } else {
                    lo.removeLockedObject();
                }
                return true;
            }
            return false;
        }
        return false;
    }

    /**
     * creates the parent path from the given path by removing the last '/' and everything after that
     * 
     * @param path the path
     * @return parent path
     */
    private String getParentPath( String path ) {
        int slash = path.lastIndexOf('/');
        if (slash == -1) {
            return null;
        }
        if (slash == 0) {
            // return "root" if parent path is empty string
            return "/";
        }
        return path.substring(0, slash);
    }

}
