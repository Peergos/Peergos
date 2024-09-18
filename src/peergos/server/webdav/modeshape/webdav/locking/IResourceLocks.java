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

import peergos.server.webdav.modeshape.webdav.ITransaction;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;

public interface IResourceLocks {

    /**
     * Tries to lock the resource at "path".
     * 
     * @param transaction
     * @param path what resource to lock
     * @param owner the owner of the lock
     * @param exclusive if the lock should be exclusive (or shared)
     * @param depth depth
     * @param timeout Lock Duration in seconds.
     * @param temporary
     * @return true if the resource at path was successfully locked, false if an existing lock prevented this
     * @throws LockFailedException
     */
    boolean lock( ITransaction transaction,
                  String path,
                  String owner,
                  boolean exclusive,
                  int depth,
                  int timeout,
                  boolean temporary ) throws LockFailedException;

    /**
     * Unlocks all resources at "path" (and all subfolders if existing)
     * <p/>
     * that have the same owner.
     * 
     * @param transaction
     * @param id id to the resource to unlock
     * @param owner who wants to unlock
     * @return true if the resources were unlocked, or false otherwise
     */
    boolean unlock( ITransaction transaction,
                    String id,
                    String owner );

    /**
     * Unlocks all resources at "path" (and all subfolders if existing)
     * <p/>
     * that have the same owner.
     * 
     * @param transaction
     * @param path what resource to unlock
     * @param owner who wants to unlock
     */
    void unlockTemporaryLockedObjects( ITransaction transaction,
                                       String path,
                                       String owner );

    /**
     * Deletes LockedObjects, where timeout has reached.
     * 
     * @param transaction
     * @param temporary Check timeout on temporary or real locks
     */
    void checkTimeouts( ITransaction transaction,
                        boolean temporary );

    /**
     * Tries to lock the resource at "path" exclusively.
     * 
     * @param transaction Transaction
     * @param path what resource to lock
     * @param owner the owner of the lock
     * @param depth depth
     * @param timeout Lock Duration in seconds.
     * @return true if the resource at path was successfully locked, false if an existing lock prevented this
     * @throws LockFailedException
     */
    boolean exclusiveLock( ITransaction transaction,
                           String path,
                           String owner,
                           int depth,
                           int timeout ) throws LockFailedException;

    /**
     * Tries to lock the resource at "path" shared.
     * 
     * @param transaction Transaction
     * @param path what resource to lock
     * @param owner the owner of the lock
     * @param depth depth
     * @param timeout Lock Duration in seconds.
     * @return true if the resource at path was successfully locked, false if an existing lock prevented this
     * @throws LockFailedException
     */
    boolean sharedLock( ITransaction transaction,
                        String path,
                        String owner,
                        int depth,
                        int timeout ) throws LockFailedException;

    /**
     * Gets the LockedObject corresponding to specified id.
     * 
     * @param transaction
     * @param id LockToken to requested resource
     * @return LockedObject or null if no LockedObject on specified path exists
     */
    LockedObject getLockedObjectByID( ITransaction transaction,
                                      String id );

    /**
     * Gets the LockedObject on specified path.
     * 
     * @param transaction
     * @param path Path to requested resource
     * @return LockedObject or null if no LockedObject on specified path exists
     */
    LockedObject getLockedObjectByPath( ITransaction transaction,
                                        String path );

    /**
     * Gets the LockedObject corresponding to specified id (locktoken).
     * 
     * @param transaction
     * @param id LockToken to requested resource
     * @return LockedObject or null if no LockedObject on specified path exists
     */
    LockedObject getTempLockedObjectByID( ITransaction transaction,
                                          String id );

    /**
     * Gets the LockedObject on specified path.
     * 
     * @param transaction
     * @param path Path to requested resource
     * @return LockedObject or null if no LockedObject on specified path exists
     */
    LockedObject getTempLockedObjectByPath( ITransaction transaction,
                                            String path );

}
