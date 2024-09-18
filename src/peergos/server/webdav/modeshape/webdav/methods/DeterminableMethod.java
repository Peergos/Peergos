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

import peergos.server.webdav.modeshape.webdav.StoredObject;

public abstract class DeterminableMethod extends AbstractMethod {

    private static final String NULL_RESOURCE_METHODS_ALLOWED = "OPTIONS, MKCOL, PUT, PROPFIND, LOCK, UNLOCK";

    private static final String RESOURCE_METHODS_ALLOWED = "OPTIONS, GET, HEAD, POST, DELETE, TRACE"
                                                           + ", PROPPATCH, COPY, MOVE, LOCK, UNLOCK, PROPFIND";

    private static final String FOLDER_METHOD_ALLOWED = ", PUT";

    private static final String LESS_ALLOWED_METHODS = "OPTIONS, MKCOL, PUT";

    /**
     * Determines the methods normally allowed for the resource.
     * 
     * @param so StoredObject representing the resource
     * @return all allowed methods, separated by commas
     */
    protected static String determineMethodsAllowed( StoredObject so ) {

        try {
            if (so != null) {
                if (so.isNullResource()) {

                    return NULL_RESOURCE_METHODS_ALLOWED;

                } else if (so.isFolder()) {
                    return RESOURCE_METHODS_ALLOWED + FOLDER_METHOD_ALLOWED;
                }
                // else resource
                return RESOURCE_METHODS_ALLOWED;
            }
        } catch (Exception e) {
            // we do nothing, just return less allowed methods
        }

        return LESS_ALLOWED_METHODS;
    }
}
