package peergos.user;

import peergos.util.*;

import java.io.*;
import java.net.*;
import java.util.zip.*;

public interface HttpPoster {

    byte[] post(String url, byte[] payload) throws IOException;

    class Java implements HttpPoster {

        private final URL dht;

        public Java(URL dht) {
            this.dht = dht;
        }

        public URL buildURL(String method) throws IOException {
            try {
                return new URL(dht, method);
            } catch (MalformedURLException mexican) {
                throw new IOException(mexican);
            }
        }

        @Override
        public byte[] post(String url, byte[] payload) throws IOException {
            HttpURLConnection conn = null;
            try
            {
                conn = (HttpURLConnection) buildURL(url).openConnection();
                conn.setDoInput(true);
                conn.setDoOutput(true);
                DataOutputStream dout = new DataOutputStream(conn.getOutputStream());

                dout.write(payload);
                dout.flush();

                String contentEncoding = conn.getContentEncoding();
                boolean isGzipped = "gzip".equals(contentEncoding);
                DataInputStream din = new DataInputStream(isGzipped ? new GZIPInputStream(conn.getInputStream()) : conn.getInputStream());
                return Serialize.readFully(din);
            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        @Override
        public String toString() {
            return dht.toString();
        }
    }

}
