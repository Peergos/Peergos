package peergos.user;

import akka.actor.ActorSystem;
import akka.dispatch.Futures;
import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.corenode.HTTPCoreNodeServer;
import peergos.crypto.User;
import peergos.crypto.UserPublicKey;
import peergos.storage.net.IP;
import peergos.user.fs.Chunk;
import peergos.user.fs.EncryptedChunk;
import peergos.user.fs.Fragment;
import peergos.user.fs.Metadata;
import peergos.user.fs.erasure.Erasure;
import peergos.util.ArrayOps;
import scala.PartialFunction;
import scala.collection.JavaConverters;
import scala.concurrent.Await;
import scala.concurrent.ExecutionContext;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.AbstractPartialFunction;

import static akka.dispatch.Futures.sequence;
import static org.junit.Assert.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UserContext
{
    public static final int MAX_USERNAME_SIZE = 1024;
    public static final int MAX_KEY_SIZE = 1024;

    String username;
    User us;
    DHTUserAPI dht;
    AbstractCoreNode core;
    ActorSystem system;

    public UserContext(String username, User user, DHTUserAPI dht, AbstractCoreNode core, ActorSystem system)
    {
        this.username = username;
        this.us = user;
        this.dht = dht;
        this.core = core;
        this.system = system;
    }

    public boolean register()
    {
        byte[] signedHash = us.hashAndSignMessage(username.getBytes());
        return core.addUsername(username, us.getPublicKey(), signedHash);
    }

    public boolean checkRegistered()
    {
        String name = core.getUsername(us.getPublicKey());
        return name.equals(username);
    }

    public boolean addSharingKey(PublicKey pub)
    {
        byte[] signedHash = us.hashAndSignMessage(pub.getEncoded());
        return core.allowSharingKey(username, pub.getEncoded(), signedHash);
    }

    public Future uploadFragment(Fragment f, String targetUser, User sharer, byte[] mapKey)
    {
        return dht.put(f.getHash(), f.getData(), targetUser, sharer.getPublicKey(), mapKey, sharer.hashAndSignMessage(ArrayOps.concat(sharer.getPublicKey(), f.getHash())));
    }

    public Metadata uploadChunk(Metadata meta, Fragment[] fragments, String target, User sharer, byte[] mapKey)
    {

        // tell core node first to allow fragments
        byte[] metaBlob = new byte[256];
        byte[] allHashes = meta.getHashes();
        core.addMetadataBlob(username, sharer.getPublicKey(), mapKey, metaBlob, sharer.hashAndSignMessage(metaBlob));
        core.addFragmentHashes(username, sharer.getPublicKey(), mapKey, metaBlob, meta.getHashes(), sharer.hashAndSignMessage(ArrayOps.concat(mapKey, metaBlob, allHashes)));

        // now upload fragments to DHT
        List<Future<Object>> futures = new ArrayList();
        for (Fragment f: fragments)
            try {
                futures.add(uploadFragment(f, target, sharer, mapKey));
            } catch (Exception e) {e.printStackTrace();}

        // wait for all fragments to upload
        Future<Iterable<Object>> futureListOfObjects = sequence(futures, system.dispatcher());
        FiniteDuration timeout = Duration.create(5*60, TimeUnit.SECONDS);
        try {
            Await.result(futureListOfObjects, timeout);
        } catch (Exception e) {e.printStackTrace();}
        return meta;
    }

    public Fragment[] downloadFragments(Metadata meta)
    {
        byte[] hashes = meta.getHashes();
        long start = System.nanoTime();
        Fragment[] res = new Fragment[hashes.length / UserPublicKey.HASH_SIZE];
        List<Future<byte[]>> futs = new ArrayList<Future<byte[]>>(res.length);
        for (int i=0; i < res.length; i++)
            futs.add(dht.get(Arrays.copyOfRange(hashes, i*UserPublicKey.HASH_SIZE, (i+1)*UserPublicKey.HASH_SIZE)));
        Countdown<byte[]> first50 = new Countdown<byte[]>(50, futs, system.dispatcher());
        first50.await();
        long end = System.nanoTime();
        System.out.printf("Succeeded downloading fragments in %d mS!\n", (end-start)/1000000);
        List<Fragment> frags = new ArrayList<Fragment>();
        for (byte[] frag: first50.results)
            frags.add(new Fragment(frag));
        return frags.toArray(new Fragment[frags.size()]);
    }

    public static class Countdown<V>
    {
        CountDownLatch left;
        List<V> results;

        public Countdown(int needed, List<Future<V>> futs, ExecutionContext context)
        {
            left = new CountDownLatch(needed);
            results = new CopyOnWriteArrayList<V>();
            for (Future<V> fut: futs)
                fut.onSuccess(new AbstractPartialFunction<V, Object>() {
                    @Override
                    public boolean isDefinedAt(V v) {
                        left.countDown();
                        results.add(v);
                        return false;
                    }
                }, context);
        }

        public void await()
        {
            while (left.getCount() > 0)
                try {
                    left.await();
                } catch (InterruptedException e) {}
        }
    }

    public static class Test
    {
        public Test() {}

        @org.junit.Test
        public void all()
        {
            try {
                ActorSystem system = null;
                String coreIP = IP.getMyPublicAddress().getHostAddress();
                String storageIP = IP.getMyPublicAddress().getHostAddress();
                int storagePort = 8000;
                try {
                    system = ActorSystem.create("UserRouter");

                    URL coreURL = new URL("http://" + coreIP + ":" + AbstractCoreNode.PORT + "/");
                    HTTPCoreNode clientCoreNode = new HTTPCoreNode(coreURL);

                    // create a new us
                    User us = User.random();
                    String ourname = "USER";

                    // create a DHT API
                    DHTUserAPI dht = new HttpsUserAPI(new InetSocketAddress(InetAddress.getByName(storageIP), storagePort), system);

                    UserContext context = new UserContext(ourname, us, dht, clientCoreNode, system);
                    assertTrue("Not already registered", !context.checkRegistered());
                    assertTrue("Register", context.register());

                    User sharer = User.random();
                    context.addSharingKey(sharer.getKey());

                    int frags = 60;
                    for (int i = 0; i < frags; i++) {
                        byte[] signature = sharer.hashAndSignMessage(ArrayOps.concat(sharer.getPublicKey(), new byte[10 + i]));
                        clientCoreNode.registerFragmentStorage(ourname, new InetSocketAddress("localhost", 666), ourname, sharer.getPublicKey(), new byte[10 + i], signature);
                    }
                    long quota = clientCoreNode.getQuota(ourname);

                    chunkTest(context, sharer);

                } finally {
                    system.shutdown();
                }
            } catch (Throwable t)
            {
                t.printStackTrace();
            }
        }

        public void chunkTest(UserContext context, User sharer)
        {
            Random r = new Random();
            byte[] initVector = new byte[EncryptedChunk.IV_SIZE];
            r.nextBytes(initVector);
            byte[] raw = new byte[Chunk.MAX_SIZE];
            byte[] contents = "Hello secure cloud! Goodbye NSA!".getBytes();
            for (int i=0; i < raw.length/32; i++)
                System.arraycopy(contents, 0, raw, 32*i, 32);

            Chunk chunk = new Chunk(raw);
            EncryptedChunk encryptedChunk = new EncryptedChunk(chunk.encrypt(initVector));
            Fragment[] fragments = encryptedChunk.generateFragments();
            Metadata meta = new Metadata(fragments, initVector);

            // upload chunk
            context.uploadChunk(meta, fragments, context.username, sharer, new byte[10]);

            // retrieve chunk
            Fragment[] retrievedfragments = context.downloadFragments(meta);

            byte[] enc = Erasure.recombine(reorder(meta, retrievedfragments), raw.length, EncryptedChunk.ERASURE_ORIGINAL, EncryptedChunk.ERASURE_ALLOWED_FAILURES);
            EncryptedChunk encrypted = new EncryptedChunk(enc);
            byte[] original = encrypted.decrypt(chunk.getKey(), initVector);
            assertTrue("Retrieved chunk identical to original", Arrays.equals(raw, original));
        }
    }

    public static byte[][] reorder(Metadata meta, Fragment[] received)
    {
        byte[] hashConcat = meta.getHashes();
        byte[][] originalHashes = new byte[hashConcat.length/UserPublicKey.HASH_SIZE][];
        for (int i=0; i < originalHashes.length; i++)
            originalHashes[i] = Arrays.copyOfRange(hashConcat, i*UserPublicKey.HASH_SIZE, (i+1)*UserPublicKey.HASH_SIZE);
        byte[][] res = new byte[originalHashes.length][];
        for (int i=0; i < res.length; i++)
        {
            for (int j=0; j < received.length; j++)
                if (Arrays.equals(originalHashes[i], received[j].getHash()))
                {
                    res[i] = received[j].getData();
                    break;
                }
            if (res[i] == null)
                res[i] = new byte[received[0].getData().length];
        }
        return res;
    }
}