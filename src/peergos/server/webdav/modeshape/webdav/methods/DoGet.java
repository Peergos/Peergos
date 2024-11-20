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
import peergos.server.webdav.modeshape.webdav.*;
import peergos.server.webdav.modeshape.webdav.locking.ResourceLocks;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import peergos.shared.user.fs.AsyncReader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.logging.Level;

public class DoGet extends DoHead {

    public DoGet( IWebdavStore store,
                  String dftIndexFile,
                  String insteadOf404,
                  ResourceLocks resourceLocks,
                  IMimeTyper mimeTyper,
                  int contentLengthHeader ) {
        super(store, dftIndexFile, insteadOf404, resourceLocks, mimeTyper, contentLengthHeader);

    }

    @Override
    protected void doBody( ITransaction transaction,
                           HttpServletResponse resp,
                           String path ) {

        try {
            StoredObject so = store.getStoredObject(transaction, path);
            if (so == null) {
                resp.sendError(HttpServletResponse.SC_NOT_FOUND);
                return;
            }
            if (so.isNullResource()) {
                String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
                resp.addHeader("Allow", methodsAllowed);
                resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                return;
            }
            OutputStream out = resp.getOutputStream();
            Pair<AsyncReader, Long> reader = store.getResourceContent(transaction, path);
            AsyncReader in = reader.left;
            try {
                byte[] copyBuffer = new byte[BUF_SIZE];
                long remaining = reader.right;
                while (remaining > 0 ) {
                    int read = in.readIntoArray(copyBuffer, 0, (int)Math.min(remaining, copyBuffer.length)).join();
                    remaining -= read;
                    out.write(copyBuffer, 0, read);
                }
            } finally {
                // flushing causes a IOE if a file is opened on the webserver
                // client disconnected before server finished sending response
                try {
                    in.close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, e, () -> "Closing InputStream causes Exception!");
                }
                try {
                    out.flush();
                    out.close();
                } catch (Exception e) {
                    logger.log(Level.WARNING, e, () -> "Flushing OutputStream causes Exception!");
                }
            }
        } catch (Exception e) {
            logger.fine(e.toString());
        }
    }

    @Override
    protected void folderBody( ITransaction transaction,
                               String path,
                               HttpServletResponse resp,
                               HttpServletRequest req ) throws IOException {

        StoredObject so = store.getStoredObject(transaction, path);
        if (so == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND, req.getRequestURI());
        } else {

            if (so.isNullResource()) {
                String methodsAllowed = DeterminableMethod.determineMethodsAllowed(so);
                resp.addHeader("Allow", methodsAllowed);
                resp.sendError(WebdavStatus.SC_METHOD_NOT_ALLOWED);
                return;
            }

            if (so.isFolder()) {
                // TODO some folder response (for browsers, DAV tools use propfind) in html?
                DateFormat shortDF = getDateTimeFormat(req.getLocale());
                resp.setContentType("text/html");
                resp.setCharacterEncoding("UTF-8");
                OutputStream out = resp.getOutputStream();
                String[] children = store.getChildrenNames(transaction, path);
                // Make sure it's not null
                children = children == null ? new String[] {} : children;
                // Sort by name
                Arrays.sort(children);
                StringBuilder childrenTemp = new StringBuilder();
                childrenTemp.append("<html><head><title>Content of folder");
                childrenTemp.append(path);
                childrenTemp.append("</title><style type=\"text/css\">");
                childrenTemp.append(getCSS());
                childrenTemp.append("</style></head>");
                childrenTemp.append("<body>");
                childrenTemp.append(getHeader(transaction, path, resp, req));
                childrenTemp.append("<table>");
                childrenTemp.append("<tr><th>Name</th><th>Size</th><th>Created</th><th>Modified</th></tr>");
                childrenTemp.append("<tr>");
                childrenTemp.append("<td colspan=\"4\"><a href=\"../\">Parent</a></td></tr>");
                boolean isEven = false;
                for (String child : children) {
                    isEven = !isEven;
                    childrenTemp.append("<tr class=\"");
                    childrenTemp.append(isEven ? "even" : "odd");
                    childrenTemp.append("\">");
                    childrenTemp.append("<td>");
                    childrenTemp.append("<a href=\"");

                    // CHECKSTYLE IGNORE check FOR NEXT 1 LINES
                    StringBuffer childURL = req.getRequestURL();
                    if (!(childURL.charAt(childURL.length() - 1) == '/')) {
                        childURL.append("/");
                    }

                    // we need to URL encode the child, but just the special chars, UTF-8 encoding is done at the end of this
                    // method
                    childURL.append(URL_ENCODER.encode(child));

                    StoredObject obj = store.getStoredObject(transaction, path + "/" + child);
                    if (obj == null) {
                        logger.warning("Should not return null for " + path + "/" + child);
                    }
                    if (obj != null && obj.isFolder()) {
                        childURL.append("/");
                    }

                    childrenTemp.append(childURL);

                    childrenTemp.append("\">");
                    childrenTemp.append(child);
                    childrenTemp.append("</a></td>");
                    if (obj != null && obj.isFolder()) {
                        childrenTemp.append("<td>Folder</td>");
                    } else {
                        childrenTemp.append("<td>");
                        if (obj != null) {
                            childrenTemp.append(obj.getResourceLength());
                        } else {
                            childrenTemp.append("Unknown");
                        }
                        childrenTemp.append(" Bytes</td>");
                    }
                    if (obj != null && obj.getCreationDate() != null) {
                        childrenTemp.append("<td>");
                        childrenTemp.append(shortDF.format(obj.getCreationDate()));
                        childrenTemp.append("</td>");
                    } else {
                        childrenTemp.append("<td></td>");
                    }
                    if (obj != null && obj.getLastModified() != null) {
                        childrenTemp.append("<td>");
                        childrenTemp.append(shortDF.format(obj.getLastModified()));
                        childrenTemp.append("</td>");
                    } else {
                        childrenTemp.append("<td></td>");
                    }
                    childrenTemp.append("</tr>");
                }
                childrenTemp.append("</table>");
                childrenTemp.append(getFooter(transaction, path, resp, req));
                childrenTemp.append("</body></html>");
                String response = childrenTemp.toString();
                logger.fine("Sending response " + response);
                out.write(response.getBytes("UTF-8"));
            }
        }
    }

    /**
     * Return the CSS styles used to display the HTML representation of the webdav content.
     * 
     * @return the HTML body
     */
    protected String getCSS() {
        // The default styles to use
        String retVal = "body {\n" + "    font-family: Arial, Helvetica, sans-serif;\n" + "}\n" + "h1 {\n" + "    font-size: 1.5em;\n"
                        + "}\n" + "th {\n" + "    background-color: #9DACBF;\n" + "}\n" + "table {\n"
                        + "    border-top-style: solid;\n" + "    border-right-style: solid;\n" + "    border-bottom-style: solid;\n"
                        + "    border-left-style: solid;\n" + "}\n" + "td {\n" + "    margin: 0px;\n" + "    padding-top: 2px;\n"
                        + "    padding-right: 5px;\n" + "    padding-bottom: 2px;\n" + "    padding-left: 5px;\n" + "}\n" + "tr.even {\n"
                        + "    background-color: #CCCCCC;\n" + "}\n" + "tr.odd {\n" + "    background-color: #FFFFFF;\n" + "}\n" + "";
        try {
            // Try loading one via class loader and use that one instead
            ClassLoader cl = getClass().getClassLoader();
            InputStream iStream = cl.getResourceAsStream("webdav.css");
            if (iStream != null) {
                // Found css via class loader, use that one
                StringBuilder out = new StringBuilder();
                byte[] b = new byte[4096];
                for (int n; (n = iStream.read(b)) != -1;) {
                    out.append(new String(b, 0, n));
                }
                retVal = out.toString();
            }
        } catch (Exception ex) {
            logger.log(Level.WARNING, ex, () -> "Error in reading webdav.css");
        }

        return retVal;
    }

    /**
     * Return the header to be displayed in front of the folder content
     * 
     * @param transaction
     * @param path
     * @param resp
     * @param req
     * @return the header string
     */
    protected String getHeader( ITransaction transaction,
                                String path,
                                HttpServletResponse resp,
                                HttpServletRequest req ) {
        return "<h1>Content of folder " + path + "</h1>";
    }

    /**
     * Return the footer to be displayed after the folder content
     * 
     * @param transaction
     * @param path
     * @param resp
     * @param req
     * @return the footer string
     */
    protected String getFooter( ITransaction transaction,
                                String path,
                                HttpServletResponse resp,
                                HttpServletRequest req ) {
        return "";
    }

    /**
     * Return this as the Date/Time format for displaying Creation + Modification dates
     * 
     * @param browserLocale
     * @return DateFormat used to display creation and modification dates
     */
    protected DateFormat getDateTimeFormat( Locale browserLocale ) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, browserLocale);
    }
}
