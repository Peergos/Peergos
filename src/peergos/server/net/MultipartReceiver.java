package peergos.server.net;

import java.io.*;
import java.util.*;

public class MultipartReceiver {
    private static final byte[] DOUBLE_NEW_LINE = "\r\n\r\n".getBytes();

    public static List<byte[]> extractFiles(InputStream rawIn, String boundary) {
        try {
            int maxLineSize = 1024;
            InputStream in = new BufferedInputStream(rawIn);
            String first = readLine(in, maxLineSize);
            if (!first.substring(2).equals(boundary))
                throw new IllegalStateException("Incorrect boundary! " + boundary + " != " + first.substring(2));
            byte[] firstHeaders = readUntil(DOUBLE_NEW_LINE, in);

            byte[] boundaryBytes = ("\r\n--" + boundary).getBytes();
            List<byte[]> files = new ArrayList<>();

            while (true) {
                byte[] file = readUntil(boundaryBytes, in);
                files.add(file);
                byte[] headers = readUntil(DOUBLE_NEW_LINE, in);
                if (headers.length == 0 || Arrays.equals(headers, "--".getBytes()))
                    return files;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     *
     * @param pattern the pattern of bytes to search until
     * @param in
     * @return the bytes in this stream until pattern is encountered, or the end of the stream is reached
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
                // be careful of case where last byte before pattern == first byte of pattern
                if ((byte) r == pattern[0]) {
                    indexInPattern = 1;
                    if (pattern.length == 1)
                        return prior.toByteArray();
                } else
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
