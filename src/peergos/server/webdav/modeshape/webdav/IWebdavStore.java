/*
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package peergos.server.webdav.modeshape.webdav;

import org.peergos.util.Pair;
import peergos.server.simulation.InputStreamAsyncReader;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.shared.user.fs.AsyncReader;

import java.io.InputStream;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Interface for simple implementation of any store for the WebdavServlet
 * <p>
 * based on the BasicWebdavStore from Oliver Zeigermann, that was part of the Webdav Construcktion Kit from slide
 */
public interface IWebdavStore {

    /**
     * Life cycle method, called by WebdavServlet's destroy() method. Should be used to clean up resources.
     */
    void destroy();

    /**
     * Indicates that a new request or transaction with this store involved has been started. The request will be terminated by
     * either {@link #commit(ITransaction)} or {@link #rollback(ITransaction)}. If only non-read methods have been called, the
     * request will be terminated by a {@link #commit(ITransaction)}. This method will be called by (@link WebdavStoreAdapter} at
     * the beginning of each request.
     * 
     * @param principal the principal that started this request or <code>null</code> if there is non available
     * @throws WebdavException
     * @return a new {@link ITransaction transaction}
     */
    ITransaction begin( Principal principal );

    /**
     * Checks if authentication information passed in is valid. If not throws an exception.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     */
    void checkAuthentication( ITransaction transaction );

    /**
     * Indicates that all changes done inside this request shall be made permanent and any transactions, connections and other
     * temporary resources shall be terminated.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @throws WebdavException if something goes wrong on the store level
     */
    void commit( ITransaction transaction );

    /**
     * Indicates that all changes done inside this request shall be undone and any transactions, connections and other temporary
     * resources shall be terminated.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @throws WebdavException if something goes wrong on the store level
     */
    void rollback( ITransaction transaction );

    /**
     * Creates a folder at the position specified by <code>folderUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param folderUri URI of the folder
     * @throws WebdavException if something goes wrong on the store level
     */
    void createFolder( ITransaction transaction,
                       String folderUri );

    /**
     * Creates a content resource at the position specified by <code>resourceUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param resourceUri URI of the content resource
     * @throws WebdavException if something goes wrong on the store level
     */
    void createResource( ITransaction transaction,
                         String resourceUri );

    /**
     * Gets the content of the resource specified by <code>resourceUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param resourceUri URI of the content resource
     * @return input stream you can read the content of the resource from
     * @throws WebdavException if something goes wrong on the store level
     */
    Pair<AsyncReader, Long> getResourceContent(ITransaction transaction,
                                               String resourceUri );

    /**
     * Sets / stores the content of the resource specified by <code>resourceUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param resourceUri URI of the resource where the content will be stored
     * @param content input stream from which the content will be read from
     * @param contentType content type of the resource or <code>null</code> if unknown
     * @param characterEncoding character encoding of the resource or <code>null</code> if unknown or not applicable
     * @return lenght of resource
     * @throws WebdavException if something goes wrong on the store level
     */
    long setResourceContent( ITransaction transaction,
                             String resourceUri,
                             Pair<AsyncReader, Long> readerPair,
                             String contentType,
                             String characterEncoding );

    /**
     *
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param sourcePath URI of the resource where the source content is
     * @param destPath URI of the resource where the content will be moved to
     * @throws WebdavException
     */
    void moveResource( ITransaction transaction,
                       String sourcePath,
                       String destPath) throws WebdavException;

    /**
     * Gets the names of the children of the folder specified by <code>folderUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param folderUri URI of the folder
     * @return a (possibly empty) list of children, or <code>null</code> if the uri points to a file
     * @throws WebdavException if something goes wrong on the store level
     */
    String[] getChildrenNames( ITransaction transaction,
                               String folderUri );

    /**
     * Gets the length of the content resource specified by <code>resourceUri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param resourceUri URI of the resource for which the length should be retrieved
     * @return length of the resource in bytes, <code>-1</code> declares this value as invalid and asks the adapter to try to set
     *         it from the properties if possible
     * @throws WebdavException if something goes wrong on the store level
     */
    long getResourceLength( ITransaction transaction,
                            String resourceUri );

    /**
     * Removes the object specified by <code>uri</code>.
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param uri URI of the object, i.e. content resource or folder
     * @throws WebdavException if something goes wrong on the store level
     */
    void removeObject( ITransaction transaction,
                       String uri );

    /**
     * Gets the storedObject specified by <code>uri</code>
     * 
     * @param transaction indicates that the method is within the scope of a WebDAV transaction
     * @param uri URI
     * @return StoredObject
     */
    StoredObject getStoredObject( ITransaction transaction,
                                  String uri );

    /**
     * Updates the custom properties on the given resource. NOTE: Nested properties are <b>not</b> supported
     * 
     * @param transaction the {@link peergos.server.webdav.modeshape.webdav.ITransaction} within which the operation takes place; may not be null
     * @param resourceUri the URI of the object on which the properties should be updated; may not be null
     * @param propertiesToSet a map of (propertyName, propertyValue) pairs which should be set on the object; may not be null; If
     *        the name of a property contains a namespace, it is expected to be in the [namespaceUri]:[localPropertyName] format.
     * @param propertiesToRemove a set of property name representing the properties which should be removed
     * @return a Map of (property name, error message) for the properties which could not changed (either set or removed). If the
     *         operation was successful, this may be null.
     */
    Map<String, String> setCustomProperties( ITransaction transaction,
                                             String resourceUri,
                                             Map<String, Object> propertiesToSet,
                                             List<String> propertiesToRemove );

    /**
     * Returns the map of (propertyName, propertyValue) of custom properties of the given resource. NOTE: Nested properties are
     * <b>not</b> supported
     * 
     * @param transaction the {@link ITransaction} within which the operation takes place; may not be null
     * @param resourceUri the URI of the object on which the properties should be updated; may not be null
     * @return a Map of (property name, property value) pairs; may not be null; if the property name is namespace-aware it is
     *         expected to have the [namespaceUri]:[localPropertyName] format
     */
    Map<String, Object> getCustomProperties( ITransaction transaction,
                                             String resourceUri );

    /**
     * Returns a map of custom namespaces that are specific to the store.
     * 
     * @param transaction the {@link ITransaction} within which the operation takes place; may not be null
     * @param resourceUri resourceUri the URI of the object on which the properties should be updated; may not be null
     * @return a Map of (namespaceUri, namespacePrefix) pairs;may not be null;
     */
    Map<String, String> getCustomNamespaces( ITransaction transaction,
                                             String resourceUri );

}
