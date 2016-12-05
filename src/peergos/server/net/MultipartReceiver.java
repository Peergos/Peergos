package peergos.server.net;

import java.io.*;
import java.util.*;

public class MultipartReceiver {

    public static byte[] extractFile(InputStream in, String boundary) {
        try {
            String first = readLine(in);
            if (!first.substring(2).equals(boundary))
                throw new IllegalStateException("Incorrect boundary! " + boundary + " != " + first.substring(2));
            String filenameLine = readLine(in);
            String contentType = readLine(in);
            String encoding = readLine(in);
            String blank = readLine(in);

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

    public static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        int r;
        while ((r = in.read()) >= 0) {
            if (r == '\r') {
                int next = in.read();
                if (next == '\n')
                    return new String(bout.toByteArray());
                bout.write(r);
                bout.write(next);
            } else
                bout.write(r);
        }
        return new String(bout.toByteArray());
    }
}
