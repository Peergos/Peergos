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
package peergos.server.webdav.modeshape.webdav.fromcatalina;

import peergos.server.util.Logging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.BitSet;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This class is very similar to the java.net.URLEncoder class. Unfortunately, with java.net.URLEncoder there is no way to specify
 * to the java.net.URLEncoder which characters should NOT be encoded. This code was moved from DefaultServlet.java
 * 
 * @author Craig R. McClanahan
 * @author Remy Maucherat
 */
public class URLEncoder {
    private static Logger LOG = Logging.LOG();

    protected static final char[] HEXADECIMAL = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    // Array containing the safe characters set.
    protected BitSet safeCharacters = new BitSet(256);

    public URLEncoder() {
        for (char i = 'a'; i <= 'z'; i++) {
            addSafeCharacter(i);
        }
        for (char i = 'A'; i <= 'Z'; i++) {
            addSafeCharacter(i);
        }
        for (char i = '0'; i <= '9'; i++) {
            addSafeCharacter(i);
        }
        for (char c : "$-_.+!*'(),".toCharArray()) {
            addSafeCharacter(c);
        }
    }

    public void addSafeCharacter( char c ) {
        safeCharacters.set(c);
    }

    public String encode( String path ) {
        int maxBytesPerChar = 10;
        // int caseDiff = ('a' - 'A');
        StringBuilder rewrittenPath = new StringBuilder(path.length());
        ByteArrayOutputStream buf = new ByteArrayOutputStream(maxBytesPerChar);
        OutputStreamWriter writer = null;
        try {
            writer = new OutputStreamWriter(buf, "UTF-8");
        } catch (Exception e) {
            LOG.log(Level.WARNING, e, () -> "Error in encode <" + path + ">");
            writer = new OutputStreamWriter(buf);
        }

        for (int i = 0; i < path.length(); i++) {
            int c = path.charAt(i);
            if (safeCharacters.get(c)) {
                rewrittenPath.append((char)c);
            } else {
                // convert to external encoding before hex conversion
                try {
                    writer.write((char)c);
                    writer.flush();
                } catch (IOException e) {
                    buf.reset();
                    continue;
                }
                byte[] ba = buf.toByteArray();
                for (int j = 0; j < ba.length; j++) {
                    // Converting each byte in the buffer
                    byte toEncode = ba[j];
                    rewrittenPath.append('%');
                    int low = toEncode & 0x0f;
                    int high = (toEncode & 0xf0) >> 4;
                    rewrittenPath.append(HEXADECIMAL[high]);
                    rewrittenPath.append(HEXADECIMAL[low]);
                }
                buf.reset();
            }
        }
        return rewrittenPath.toString();
    }
}
