package peergos.server.tests;
import java.util.logging.*;
import peergos.server.util.Logging;

import org.junit.*;
import peergos.shared.util.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class TorTest {
	private static final Logger LOG = Logging.LOG();

    @Ignore
    @Test
    public void connectlToHiddenServiceNatively() throws Exception {
//        boolean tor = true;
        boolean tor = false;
        boolean smallFile = true;
        SocketAddress addr = tor ?
                new InetSocketAddress("localhost", 9050) :
                new InetSocketAddress("localhost", 4444);
        Proxy proxy = tor ?
                new Proxy(Proxy.Type.SOCKS, addr) :
                new Proxy(Proxy.Type.HTTP, addr);
        long t0 = System.currentTimeMillis();

        int respSize = smallFile ?
                128 * 1024 :
                100 * 1024 * 1024;
        int threads = 2;
        AtomicLong requests = new AtomicLong(0);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future> futs = new ArrayList<>();
        for (int t=0; t < threads; t++)
            futs.add(pool.submit(() -> {
                List<String> files = smallFile ?
                        Arrays.asList(
                                "api/v0/block/get?stream-channels=true&arg=zb2rhZ2ME5SAUFnqe8b6sgkPnSAMBUruQBjayJo1p7kE7gdsc",
                                "api/v0/block/get?stream-channels=true&arg=zb2rhfT1FuzXQodxCLYkR76mxnpm2xLtMrTJzYTWvoe9ARZ96"
                                ) :
                        Arrays.asList("big.tar.gz");
                for (int i = 0; i < 10000; i++) {
                    try {
                        URL url = tor ?
                                new URL("http://s2prds2oc2ujvnmm.onion/" + files.get(i % files.size())) :
                                new URL("http://cpozng3fspyr7vg5i3ebqlncnzfn2lmxfkfkqbcyg52xnrs2vydq.b32.i2p/" + files.get(i % files.size()));
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
                        conn.connect();
                        InputStream in = conn.getInputStream();
                        int size = conn.getContentLength();
                        if (size < 0) {
                            LOG.severe("negative body size!");
                            continue;
                        }
                        byte[] bytes = Serialize.read(in, size);
                        long now = System.currentTimeMillis();
                        long reqs = requests.incrementAndGet();
                        if (reqs % 1 == 0) {
                            LOG.info("Average bandwidth: " + reqs * respSize * 1000/ (now - t0)/1024 + " kiB/S, Average " + reqs * 1000.0/ (now - t0) + " requests/s");
                        }
                    } catch (IOException e) {
                        LOG.log(Level.WARNING, e.getMessage(), e);
                    }
                }
            }));
        for (Future fut : futs) {
            fut.get();
        }
    }
}
