package peergos.server.tests.slow;
import java.util.logging.*;

import peergos.server.util.Logging;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.net.*;
import peergos.shared.io.ipfs.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MultipartProfiling {
	private static final Logger LOG = Logging.LOG();

    private final int port;
    private final HttpServer server;
    private final Queue<List<byte[]>> received = new LinkedBlockingQueue<>();
    private final Random r = new Random(1);

    public MultipartProfiling() throws IOException {
        this.port = 5679;
        InetSocketAddress localhost = new InetSocketAddress("localhost", port);
        this.server = HttpServer.create(localhost, 10);
        server.createContext("/multipart", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
    }

    @After
    public void finish() {
        server.stop(0);
    }

    public void handle(HttpExchange httpExchange) throws IOException {
        try {
            String boundary = httpExchange.getRequestHeaders().get("Content-Type")
                    .stream()
                    .filter(s -> s.contains("boundary="))
                    .map(s -> s.substring(s.indexOf("=") + 1))
                    .findAny()
                    .get();
            List<byte[]> data = MultipartReceiver.extractFiles(httpExchange.getRequestBody(), boundary);
            received.add(data);
            httpExchange.sendResponseHeaders(200, 0);
            DataOutputStream dout = new DataOutputStream(httpExchange.getResponseBody());
            dout.write("true".getBytes());
            dout.flush();
            dout.close();
        } catch (Exception e) {
            LOG.log(Level.WARNING, e.getMessage(), e);
        }
    }

    private byte[] randomArray(int len) {
        byte[] res = new byte[len];
        r.nextBytes(res);
        return res;
    }

    @Test
    public void profileAll() throws IOException {
        long t1 = System.currentTimeMillis();
        int requests = 1_000;
        for (int i = 0; i < requests; i++)
            profile(100 * 1024, 1);
        long t2 = System.currentTimeMillis();
        System.out.printf("Did %d multipart requests, averaging %d mS each.\n", requests, (t2 - t1) / requests);
    }

    private void profile(int size, int count) throws IOException {
        Multipart sender = new Multipart("http://localhost:" + port + "/multipart", "UTF-8");
        byte[] data = randomArray(size);
        for (int i = 0; i < count; i++)
            sender.addFilePart("file", new NamedStreamable.ByteArrayWrapper(data));

        String res = sender.finish();

        List<byte[]> result = received.poll();

        boolean sameLength = result.size() == count && result.get(0).length == size;

        Assert.assertTrue("Same length on other end", sameLength);
    }
}
