package peergos.server.tests;

import com.sun.net.httpserver.*;
import org.junit.*;
import peergos.server.net.*;
import peergos.shared.io.ipfs.api.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;

public class MultipartTests {

    private final int port;
    private final HttpServer server;
    private final Queue<List<byte[]>> received = new LinkedBlockingQueue<>();
    private final Random r = new Random(1);

    public MultipartTests() throws IOException {
        this.port = 5679;
        InetSocketAddress localhost = new InetSocketAddress("localhost", port);
        this.server = HttpServer.create(localhost, 10);
        server.createContext("/multipart", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(1));
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
            e.printStackTrace();
        }
    }

    private byte[] randomArray(int len) {
        byte[] res = new byte[len];
        r.nextBytes(res);
        return res;
    }

    @Test
    public void random() throws IOException {
        for (int power = 5; power < 20; power++) {
            int base =  (int) Math.pow(2, power);
            int length = base + r.nextInt(base);
            try {
                test(IntStream.range(0, 60)
                        .mapToObj(i -> randomArray(length))
                        .collect(Collectors.toList()));
            } catch (AssertionError e) {
                System.err.println("Failed on power: " + power + " and length: " + length);
                throw e;
            }
        }
    }

    private void test(List<byte[]> input) throws IOException {
        Multipart sender = new Multipart("http://localhost:" + port + "/multipart", "UTF-8");
        for (byte[] in : input)
            sender.addFilePart("file", new NamedStreamable.ByteArrayWrapper(in));

        String res = sender.finish();

        List<byte[]> result = received.poll();

        boolean sameLength = result.size() == input.size();

        Assert.assertTrue("Same length on other end", sameLength);

        List<Integer> differences = IntStream.range(0, input.size())
                .filter(i -> !Arrays.equals(input.get(i), result.get(i)))
                .mapToObj(Integer::valueOf)
                .collect(Collectors.toList());
        Assert.assertTrue("Same result on other end: " + differences, differences.size() == 0);
    }
}
