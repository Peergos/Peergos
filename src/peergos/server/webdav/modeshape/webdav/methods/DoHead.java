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

import peergos.server.webdav.modeshape.webdav.*;
import peergos.server.webdav.modeshape.webdav.exceptions.AccessDeniedException;
import peergos.server.webdav.modeshape.webdav.exceptions.LockFailedException;
import peergos.server.webdav.modeshape.webdav.exceptions.ObjectAlreadyExistsException;
import peergos.server.webdav.modeshape.webdav.exceptions.WebdavException;
import peergos.server.webdav.modeshape.webdav.locking.ResourceLocks;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public class DoHead extends AbstractMethod {

    protected final String dftIndexFile;
    protected final IWebdavStore store;
    protected final String insteadOf404;
    protected final ResourceLocks resourceLocks;
    protected final IMimeTyper mimeTyper;
    protected final int contentLength;

    public DoHead( IWebdavStore store,
                   String dftIndexFile,
                   String insteadOf404,
                   ResourceLocks resourceLocks,
                   IMimeTyper mimeTyper,
                   int contentLengthHeader ) {
        this.store = store;
        this.dftIndexFile = dftIndexFile;
        this.insteadOf404 = insteadOf404;
        this.resourceLocks = resourceLocks;
        this.mimeTyper = mimeTyper;
        this.contentLength = contentLengthHeader;
    }

    @Override
    public void execute( ITransaction transaction,
                         HttpServletRequest req,
                         HttpServletResponse resp ) throws IOException, LockFailedException {

        // determines if the uri exists.

        boolean bUriExists = false;

        String path = getRelativePath(req);
        logger.fine("-- " + this.getClass().getName());

        StoredObject so = store.getStoredObject(transaction, path);
        if (so == null) {
            if (this.insteadOf404 != null && !insteadOf404.trim().equals("")) {
                path = this.insteadOf404;
                so = store.getStoredObject(transaction, this.insteadOf404);
            }
        } else {
            bUriExists = true;
        }

        if (so != null) {
            if (so.isFolder()) {
                if (dftIndexFile != null && !dftIndexFile.trim().equals("")) {
                    resp.sendRedirect(resp.encodeRedirectURL(req.getRequestURI() + this.dftIndexFile));
                    return;
                }
            } else if (so.isNullResource()) {
                String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
                resp.addHeader("Allow", methodsAllowed);
                resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                return;
            }

            String tempLockOwner = "doGet" + System.currentTimeMillis() + req.toString();

            if (resourceLocks.lock(transaction, path, tempLockOwner, false, 0, TEMP_TIMEOUT, TEMPORARY)) {
                try {
                    String eTagMatch = req.getHeader("If-None-Match");
                    if (eTagMatch != null) {
                        if (eTagMatch.equals(getETag(so))) {
                            resp.setStatus(WebdavStatus.SC_NOT_MODIFIED);
                            return;
                        }
                    }

                    if (so.isResource()) {
                        // path points to a file but ends with / or \
                        if (path.endsWith("/") || (path.endsWith("\\"))) {
                            resp.sendError(HttpServletResponse.SC_NOT_FOUND, req.getRequestURI());
                        } else {

                            // setting headers
                            long lastModified = so.getLastModified().getTime();
                            resp.setDateHeader("last-modified", lastModified);

                            String eTag = getETag(so);
                            resp.addHeader("ETag", eTag);

                            long resourceLength = so.getResourceLength();

                            if (contentLength == 1) {
                                if (resourceLength > 0) {
                                    if (resourceLength <= Integer.MAX_VALUE) {
                                        resp.setContentLength((int)resourceLength);
                                    } else {
                                        resp.setHeader("content-length", "" + resourceLength);
                                        // is "content-length" the right header?
                                        // is long a valid format?
                                    }
                                }
                            }

                            String mimeType = mimeTyper.getMimeType(transaction, path);
                            if (mimeType != null) {
                                resp.setContentType(mimeType);
                            } else {
                                int lastSlash = path.replace('\\', '/').lastIndexOf('/');
                                int lastDot = path.indexOf(".", lastSlash);
                                if (lastDot == -1) {
                                    resp.setContentType("text/html");
                                }
                            }

                            doBody(transaction, resp, path);
                        }
                    } else {
                        folderBody(transaction, path, resp, req);
                    }
                } catch (AccessDeniedException e) {
                    resp.sendError(WebdavStatus.SC_FORBIDDEN);
                } catch (ObjectAlreadyExistsException e) {
                    resp.sendError(WebdavStatus.SC_NOT_FOUND, req.getRequestURI());
                } catch (WebdavException e) {
                    resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
                } finally {
                    resourceLocks.unlockTemporaryLockedObjects(transaction, path, tempLockOwner);
                }
            } else {
                resp.sendError(WebdavStatus.SC_INTERNAL_SERVER_ERROR);
            }
        } else {
            folderBody(transaction, path, resp, req);
        }

        if (!bUriExists) {
            resp.setStatus(WebdavStatus.SC_NOT_FOUND);
        }

    }

    @SuppressWarnings( "unused" )
    protected void folderBody( ITransaction transaction,
                               String path,
                               HttpServletResponse resp,
                               HttpServletRequest req ) throws IOException {
        // no body for HEAD
    }

    @SuppressWarnings( "unused" )
    protected void doBody( ITransaction transaction,
                           HttpServletResponse resp,
                           String path ) throws IOException {
        // no body for HEAD
    }
}
