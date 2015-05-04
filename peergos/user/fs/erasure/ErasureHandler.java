package peergos.user.fs.erasure;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


import peergos.user.fs.Chunk;
import peergos.user.fs.EncryptedChunk;
import peergos.user.fs.Fragment;
import peergos.util.Serialize;

import java.io.*;
import java.net.*;
import java.util.Random;

import org.junit.Before;
import static org.junit.Assert.*;

public class ErasureHandler implements HttpHandler {

    public static final int MAX_SPLIT_LENGTH = Chunk.MAX_SIZE;
    public static final int MAX_RECOMBINE_LENGTH = Chunk.MAX_SIZE;

    private ErasureHandler(){}

    private static class Split {
        private final byte[] input;
        private final int originalBlobs, allowedFailures;

        public Split(byte[] input, int originalBlobs, int allowedFailures) {
            this.input = input;
            this.originalBlobs = originalBlobs;
            this.allowedFailures = allowedFailures;
        }

        public static Split build(DataInputStream din) throws IOException {
            byte[] input = Serialize.deserializeByteArray(din, MAX_SPLIT_LENGTH);
            int originalBlobs = EncryptedChunk.ERASURE_ORIGINAL;
            int allowedFailures = EncryptedChunk.ERASURE_ALLOWED_FAILURES;
            try {
                originalBlobs = din.readInt();
                allowedFailures = din.readInt();
            } catch (EOFException oefe){}

            return new Split(input, originalBlobs, allowedFailures);
        }
    }

    private static class Recombine {
        public final byte[][] encoded;
        public final int truncateTo,  originalBlobs, allowedFailures;

        public Recombine(byte[][] encoded, int truncateTo, int originalBlobs, int allowedFailures) {
            this.encoded = encoded;
            this.truncateTo = truncateTo;
            this.originalBlobs = originalBlobs;
            this.allowedFailures = allowedFailures;
        }

        public static Recombine build(DataInputStream din) throws IOException {
            int nEncoded = din.readInt();
            byte[][] encoded = new byte[nEncoded][];
            for (int i=0; i < nEncoded; i++)
                encoded[i] = Serialize.deserializeByteArray(din, MAX_RECOMBINE_LENGTH);

            int truncateTo = Chunk.MAX_SIZE;
            int originalBlobs = EncryptedChunk.ERASURE_ORIGINAL;
            int allowedFailures = EncryptedChunk.ERASURE_ALLOWED_FAILURES;

            try {
                truncateTo = din.readInt();
                originalBlobs = din.readInt();
                allowedFailures = din.readInt();
            } catch (EOFException eofe){}

            return new Recombine(encoded, truncateTo, originalBlobs, allowedFailures);
        }
    }
    @Override
    public void handle(HttpExchange httpExchange) throws IOException {

        DataInputStream din = new DataInputStream(httpExchange.getRequestBody());

        String path = httpExchange.getRequestURI().getPath();
        String method = path.substring(path.lastIndexOf("/") + 1);
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            switch (method) {
                case "split":
                    Split split = Split.build(din);
                    byte[][] resultHashes = Erasure.split(split.input,
                            split.originalBlobs,
                            split.allowedFailures);

                    dout.writeInt(resultHashes.length);

                    for (byte[] hash : resultHashes)
                        Serialize.serialize(hash, dout);
                    break;
                case "recombine":
                    Recombine recombine = Recombine.build(din);

                    byte[] result = Erasure.recombine(recombine.encoded,
                            recombine.truncateTo,
                            recombine.originalBlobs,
                            recombine.allowedFailures);

                    Serialize.serialize(result, dout);
                    break;
                default:
                    throw new IllegalStateException("Unknown method request: "+ method);
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        } finally {
            din.close();
            dout.close();
        }
        byte[] bytes = bout.toByteArray();
        httpExchange.sendResponseHeaders(200, bytes.length);
        httpExchange.getResponseBody().write(bytes);
        httpExchange.close();
    }

    private static final ErasureHandler INSTANCE = new ErasureHandler();

    public static ErasureHandler getInstance(){return INSTANCE;}

    public static class Test {

        public Test(){}

        @Before public void init() throws IOException {
            InetAddress localhost = InetAddress.getByName("localhost");
            int port = 8800;

            HttpServer httpServer = HttpServer.create(new InetSocketAddress(localhost, port), 10);
            httpServer.createContext("/erasure/", ErasureHandler.getInstance());
            httpServer.start();
        }

        @org.junit.Test public void test() throws IOException {
            Random random = new Random();
            byte[] input = new byte[Chunk.MAX_SIZE];
            random.nextBytes(input);

            URL splitUrl = new URL("http://localhost:8800/erasure/split");
            URLConnection splitConn = splitUrl.openConnection();
            splitConn.setDoOutput(true);
            try (DataOutputStream dout = new DataOutputStream(splitConn.getOutputStream())) {
                Serialize.serialize(input, dout);
                dout.flush();
            }

            byte[][] hashes = null;
            try (DataInputStream din = new DataInputStream(splitConn.getInputStream())) {
                int nHashes = din.readInt();
                assertTrue("hash hashes", nHashes > 0);
                hashes = new byte[nHashes][];
                for (int iHash = 0; iHash < nHashes; iHash++) {
                    hashes[iHash] = Serialize.deserializeByteArray(din, Fragment.SIZE);
                    assertEquals(hashes[iHash].length, Fragment.SIZE);
                }
            }

            URL recombineUrl = new URL("http://localhost:8800/erasure/recombine");
            URLConnection recombineConn= recombineUrl.openConnection();
            recombineConn.setDoOutput(true);

            try (DataOutputStream dout = new DataOutputStream(recombineConn.getOutputStream())) {
                dout.writeInt(hashes.length);
                for (byte[] hash: hashes)
                    Serialize.serialize(hash, dout);
                dout.flush();
            }

            byte[] result = null;
            try(DataInputStream din = new DataInputStream(recombineConn.getInputStream())) {
                result = Serialize.deserializeByteArray(din, input.length);
            }

            assertArrayEquals(result, input);
        }
    }

}
