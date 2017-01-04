package peergos.server.net;

import java.io.*;
import java.util.*;

public class MultipartReceiver {

    public static List<byte[]> extractFiles(InputStream rawIn, String boundary) {
        try {
            int maxLineSize = 1024;
            InputStream in = new BufferedInputStream(rawIn);
            String first = readLine(in, maxLineSize);
            if (!first.substring(2).equals(boundary))
                throw new IllegalStateException("Incorrect boundary! " + boundary + " != " + first.substring(2));
            byte[] firstHeaders = readUntil("\r\n\r\n".getBytes(), in);

            byte[] boundaryBytes = ("\r\n--" + boundary).getBytes();
            List<byte[]> files = new ArrayList<>();

            while (true) {
                byte[] file = readUntil(boundaryBytes, in);
                files.add(file);
                byte[] headers = readUntil("\r\n\r\n".getBytes(), in);
                if (headers.length == 0 || Arrays.equals(headers, "--".getBytes()))
                    return files;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** returns the bytes in this stream until pattern is encountered, or the end of the stream is reached
     *
     * @param pattern
     * @param in
     * @return
     * @throws IOException
     */
    private static byte[] readUntil(byte[] pattern, InputStream in) throws IOException {
        ByteArrayOutputStream prior = new ByteArrayOutputStream();
        int r;
        int indexInPattern = 0;
        while ((r = in.read()) != -1) {
            if ((byte) r == pattern[indexInPattern]) {
                indexInPattern++;
                if (indexInPattern == pattern.length)
                    return prior.toByteArray();
            } else {
                if (indexInPattern > 0)
                    prior.write(pattern, 0, indexInPattern);
                indexInPattern = 0;
                prior.write(r);
            }
        }
        return prior.toByteArray();
    }

    private static String readLine(InputStream in, int maxSize) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int r, total = 0;
        while ((r = in.read()) >= 0) {
            total++;

            if (r == '\r') {
                int next = in.read();
                if (next == '\n')
                    break;
                bout.write(r);
                bout.write(next);
            } else
                bout.write(r);
            if (total > maxSize)
                break;
        }
        return new String(bout.toByteArray());
    }
}
