package peergos.user;

import peergos.corenode.AbstractCoreNode;
import peergos.corenode.HTTPCoreNode;
import peergos.crypto.User;
import peergos.storage.dht.DHTAPI;
import peergos.storage.net.IP;
import peergos.user.fs.Chunk;
import peergos.user.fs.EncryptedChunk;
import peergos.user.fs.Fragment;
import peergos.user.fs.MetadataBlob;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.IOException;
import java.net.URL;
import java.security.PublicKey;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class UserContext
{
    String username;
    User user;
    DHTAPI dht;
    AbstractCoreNode core;

    public UserContext(String username, User user, DHTAPI dht, AbstractCoreNode core)
    {
        this.username = username;
        this.user = user;
        this.dht = dht;
        this.core = core;
    }

    public boolean register()
    {
        byte[] signedHash = user.hashAndSignMessage(username.getBytes());
        return core.addUsername(username, user.getPublicKey(), signedHash);
    }

    public boolean checkRegistered()
    {
        String name = core.getUsername(user.getPublicKey());
        return name.equals(username);
    }

    public boolean addSharingKey(PublicKey pub)
    {
        byte[] signedHash = user.hashAndSignMessage(pub.getEncoded());
        return core.allowSharingKey(username, pub.getEncoded(), signedHash);
    }

    public MetadataBlob uploadChunk(byte[] raw, byte[] initVector)
    {
        Chunk chunk = new Chunk(raw);
        EncryptedChunk encryptedChunk = new EncryptedChunk(chunk.encrypt(initVector));
        Fragment[] fragments = encryptedChunk.generateFragments();

        FiniteDuration timeout = Duration.create(30, TimeUnit.SECONDS);
        for (Fragment f: fragments)
            try {
                Await.result(dht.uploadFragment(f), timeout);
            } catch (Exception e) {e.printStackTrace();}
        return new MetadataBlob(fragments, initVector);
    }

    public static class Test
    {
        public Test() {}

        @org.junit.Test
        public void all() throws IOException
        {
            // create a CoreNode API
            URL coreURL = new URL("http://"+IP.getMyPublicAddress()+":"+ AbstractCoreNode.PORT+"/");
            HTTPCoreNode clientCoreNode = new HTTPCoreNode(coreURL);

            // create a new user
            User us = User.random();
            String ourname = "USER";

            // create a DHT API
            DHTAPI dht = null;

            UserContext context = new UserContext(ourname, us, dht, clientCoreNode);
//            uploadChunkTest(context);
        }

        public void uploadChunkTest(UserContext context)
        {
            Random r = new Random();
            byte[] initVector = new byte[EncryptedChunk.IV_SIZE];
            r.nextBytes(initVector);
            byte[] raw = new byte[Chunk.MAX_SIZE];
            byte[] contents = "Hello secure cloud! Goodbye NSA!".getBytes();
            for (int i=0; i < raw.length/32; i++)
                System.arraycopy(contents, 0, raw, 32*i, 32);

            MetadataBlob meta = context.uploadChunk(raw, initVector);
            // upload metadata to core node
            byte[] metablob = meta.serialize();

        }
    }
}