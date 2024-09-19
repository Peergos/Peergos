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
package peergos.server.webdav.modeshape.webdav.locking;

import peergos.server.util.Logging;

import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * a helper class for ResourceLocks, represents the Locks
 * 
 * @author re
 */
public class LockedObject {

    private static final Logger LOGGER = Logging.LOG();

    private ResourceLocks resourceLocks;

    private String path;

    private String id;

    /**
     * Describing the depth of a locked collection. If the locked resource is not a collection, depth is 0 / doesn't matter.
     */
    protected int lockDepth;

    /**
     * Describing the timeout of a locked object (ms)
     */
    protected long expiresAt;

    /**
     * owner of the lock. shared locks can have multiple owners. is null if no owner is present
     */
    protected String[] owner = null;

    /**
     * children of that lock
     */
    protected LockedObject[] children = null;

    protected LockedObject parent = null;

    /**
     * weather the lock is exclusive or not. if owner=null the exclusive value doesn't matter
     */
    protected boolean exclusive = false;

    /**
     * weather the lock is a write or read lock
     */
    protected String type = null;

    /**
     * @param resLocks the resourceLocks where locks are stored
     * @param path the path to the locked object
     * @param temporary indicates if the LockedObject should be temporary or not
     */
    public LockedObject( ResourceLocks resLocks,
                         String path,
                         boolean temporary ) {
        this.path = path;
        id = UUID.randomUUID().toString();
        resourceLocks = resLocks;

        if (!temporary) {
            resourceLocks.locks.put(path, this);
            resourceLocks.locksByID.put(id, this);
        } else {
            resourceLocks.tempLocks.put(path, this);
            resourceLocks.tempLocksByID.put(id, this);
        }
        resourceLocks.cleanupCounter++;
    }

    /**
     * adds a new owner to a lock
     * 
     * @param owner string that represents the owner
     * @return true if the owner was added, false otherwise
     */
    public boolean addLockedObjectOwner( String owner ) {

        if (this.owner == null) {
            this.owner = new String[1];
        } else {

            int size = this.owner.length;
            String[] newLockObjectOwner = new String[size + 1];

            // check if the owner is already here (that should actually not
            // happen)
            for (int i = 0; i < size; i++) {
                if (this.owner[i].equals(owner)) {
                    return false;
                }
            }

            System.arraycopy(this.owner, 0, newLockObjectOwner, 0, size);
            this.owner = newLockObjectOwner;
        }

        this.owner[this.owner.length - 1] = owner;
        return true;
    }

    /**
     * tries to remove the owner from the lock
     * 
     * @param owner string that represents the owner
     */
    public void removeLockedObjectOwner( String owner ) {

        try {
            if (this.owner != null) {
                int size = this.owner.length;
                for (int i = 0; i < size; i++) {
                    // check every owner if it is the requested one
                    if (this.owner[i].equals(owner)) {
                        // remove the owner
                        size -= 1;
                        String[] newLockedObjectOwner = new String[size];
                        for (int j = 0; j < size; j++) {
                            if (j < i) {
                                newLockedObjectOwner[j] = this.owner[j];
                            } else {
                                newLockedObjectOwner[j] = this.owner[j + 1];
                            }
                        }
                        this.owner = newLockedObjectOwner;

                    }
                }
                if (this.owner.length == 0) {
                    this.owner = null;
                }
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            LOGGER.log(Level.WARNING, e, () -> "LockedObject.removeLockedObjectOwner()");
        }
    }

    /**
     * adds a new child lock to this lock
     * 
     * @param newChild new child
     */
    public void addChild( LockedObject newChild ) {
        if (children == null) {
            children = new LockedObject[0];
        }
        int size = children.length;
        LockedObject[] newChildren = new LockedObject[size + 1];
        System.arraycopy(children, 0, newChildren, 0, size);
        newChildren[size] = newChild;
        children = newChildren;
    }

    /**
     * deletes this Lock object. assumes that it has no children and no owners (does not check this itself)
     */
    public void removeLockedObject() {
        if (this != resourceLocks.root && !this.getPath().equals("/")) {

            int size = parent.children.length;
            for (int i = 0; i < size; i++) {
                if (parent.children[i].equals(this)) {
                    LockedObject[] newChildren = new LockedObject[size - 1];
                    for (int i2 = 0; i2 < (size - 1); i2++) {
                        if (i2 < i) {
                            newChildren[i2] = parent.children[i2];
                        } else {
                            newChildren[i2] = parent.children[i2 + 1];
                        }
                    }
                    if (newChildren.length != 0) {
                        parent.children = newChildren;
                    } else {
                        parent.children = null;
                    }
                    break;
                }
            }

            // removing from hashtable
            resourceLocks.locksByID.remove(getID());
            resourceLocks.locks.remove(getPath());

            // now the garbage collector has some work to do
        }
    }

    /**
     * deletes this Lock object. assumes that it has no children and no owners (does not check this itself)
     */
    public void removeTempLockedObject() {
        if (this != resourceLocks.tempRoot) {
            // removing from tree
            if (parent != null && parent.children != null) {
                int size = parent.children.length;
                for (int i = 0; i < size; i++) {
                    if (parent.children[i].equals(this)) {
                        LockedObject[] newChildren = new LockedObject[size - 1];
                        for (int i2 = 0; i2 < (size - 1); i2++) {
                            if (i2 < i) {
                                newChildren[i2] = parent.children[i2];
                            } else {
                                newChildren[i2] = parent.children[i2 + 1];
                            }
                        }
                        if (newChildren.length != 0) {
                            parent.children = newChildren;
                        } else {
                            parent.children = null;
                        }
                        break;
                    }
                }

                // removing from hashtable
                resourceLocks.tempLocksByID.remove(getID());
                resourceLocks.tempLocks.remove(getPath());

                // now the garbage collector has some work to do
            }
        }
    }

    /**
     * checks if a lock of the given exclusivity can be placed, only considering children up to "depth"
     * 
     * @param exclusive wheather the new lock should be exclusive
     * @param depth the depth to which should be checked
     * @return true if the lock can be placed
     */
    public boolean checkLocks( boolean exclusive,
                               int depth ) {
        if (checkParents(exclusive) && checkChildren(exclusive, depth)) {
            return true;
        }
        return false;
    }

    /**
     * helper of checkLocks(). looks if the parents are locked
     * 
     * @param exclusive wheather the new lock should be exclusive
     * @return true if no locks at the parent path are forbidding a new lock
     */
    private boolean checkParents( boolean exclusive ) {
        if (path.equals("/")) {
            return true;
        }
        if (owner == null) {
            // no owner, checking parents
            return parent != null && parent.checkParents(exclusive);
        }
        // there already is a owner
        return !(this.exclusive || exclusive) && parent.checkParents(exclusive);
    }

    /**
     * helper of checkLocks(). looks if the children are locked
     * 
     * @param exclusive whether the new lock should be exclusive
     * @param depth depth
     * @return true if no locks at the children paths are forbidding a new lock
     */
    private boolean checkChildren( boolean exclusive,
                                   int depth ) {
        if (children == null) {
            // a file

            return owner == null || !(this.exclusive || exclusive);
        }
        // a folder

        if (owner == null) {
            // no owner, checking children

            if (depth != 0) {
                boolean canLock = true;
                int limit = children.length;
                for (int i = 0; i < limit; i++) {
                    if (!children[i].checkChildren(exclusive, depth - 1)) {
                        canLock = false;
                    }
                }
                return canLock;
            }
            // depth == 0 -> we don't care for children
            return true;
        }
        // there already is a owner
        return !(this.exclusive || exclusive);

    }

    /**
     * Sets a new timeout for the LockedObject
     * 
     * @param timeout
     */
    public void refreshTimeout( int timeout ) {
        expiresAt = System.currentTimeMillis() + (timeout * 1000);
    }

    /**
     * Gets the timeout for the LockedObject
     * 
     * @return timeout
     */
    public long getTimeoutMillis() {
        return (expiresAt - System.currentTimeMillis());
    }

    /**
     * Return true if the lock has expired.
     * 
     * @return true if timeout has passed
     */
    public boolean hasExpired() {
        if (expiresAt != 0) {
            return (System.currentTimeMillis() > expiresAt);
        }
        return true;
    }

    /**
     * Gets the LockID (locktoken) for the LockedObject
     * 
     * @return locktoken
     */
    public String getID() {
        return id;
    }

    /**
     * Gets the owners for the LockedObject
     * 
     * @return owners
     */
    public String[] getOwner() {
        return owner;
    }

    /**
     * Gets the path for the LockedObject
     * 
     * @return path
     */
    public String getPath() {
        return path;
    }

    /**
     * Sets the exclusivity for the LockedObject
     * 
     * @param exclusive
     */
    public void setExclusive( boolean exclusive ) {
        this.exclusive = exclusive;
    }

    /**
     * Gets the exclusivity for the LockedObject
     * 
     * @return exclusivity
     */
    public boolean isExclusive() {
        return exclusive;
    }

    /**
     * Gets the exclusivity for the LockedObject
     * 
     * @return exclusivity
     */
    public boolean isShared() {
        return !exclusive;
    }

    /**
     * Gets the type of the lock
     * 
     * @return type
     */
    public String getType() {
        return type;
    }

    /**
     * Gets the depth of the lock
     * 
     * @return depth
     */
    public int getLockDepth() {
        return lockDepth;
    }

}
