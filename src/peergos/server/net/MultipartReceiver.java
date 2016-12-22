package peergos.server.net;

import java.io.*;
import java.util.*;

public class MultipartReceiver {

    public static byte[] extractFile(InputStream in, String boundary) {
        try {
            int maxLineSize = 1024;
            String first = readLine(in, maxLineSize);
            if (!first.substring(2).equals(boundary))
                throw new IllegalStateException("Incorrect boundary! " + boundary + " != " + first.substring(2));
            String filenameLine = readLine(in, maxLineSize);
            String contentType = readLine(in, maxLineSize);
            String encoding = contentType.length() > 0 ? readLine(in, maxLineSize) : "";
            String blank = encoding.length() > 0 ? readLine(in, maxLineSize) : "";

            ByteArrayOutputStream file = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int r;
            while ((r = in.read(buffer)) != -1)
                file.write(buffer, 0, r);
            byte[] bytes = file.toByteArray();
            // 8 is two \r\n and two -- at end of stream
            return Arrays.copyOfRange(bytes, 0, bytes.length - (8 + boundary.length()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String readLine(InputStream in, int maxSize) throws IOException {
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
