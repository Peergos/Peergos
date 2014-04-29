
package peergos.tests;

import static org.junit.Assert.*;

import akka.actor.ActorSystem;
import peergos.crypto.*;
import peergos.corenode.*;
import peergos.user.UserContext;
import peergos.util.ByteArrayWrapper;

import org.junit.Test;

import java.util.*;
import java.io.*;
import java.net.*;

import java.security.*;

public class CoreNode
{
    private static Random random = new Random(666);

    class MockCoreNode extends AbstractCoreNode
    {
        public void close(){};
    }

    public void coreNodeTests(AbstractCoreNode coreNode) throws Exception {
        ActorSystem system = null;
        try {
            system = ActorSystem.create("UserRouter");
            User user = User.random();
            String username = "USER";
            UserContext context = new UserContext(username, user, null, coreNode, system);
            assertTrue("Checking for username", !context.checkRegistered());
            assertTrue("added user", context.register());

            //
            //add sharing key to this user
            //
            User follower = User.random();
            assertTrue("follow user", context.addSharingKey(follower.getKey()));

            //
            //retrieve sharing key
            //
            boolean retrievedSharingKey = false;
            Iterator<UserPublicKey> sharingKeys = coreNode.getSharingKeys(username);
            while (sharingKeys.hasNext()) {
                UserPublicKey next = sharingKeys.next();
                if (Arrays.equals(next.getKey().getEncoded(), follower.getKey().getEncoded()))
                    retrievedSharingKey = true;
            }
            assertTrue("retrieved sharing key ", retrievedSharingKey);

            //generate some test data
            //
            byte[] fragmentData = new byte[500];
            random.nextBytes(fragmentData);
            byte[] cipherText = follower.encryptMessageFor(fragmentData);

            //
            //add fragment
            //
            byte[] mapKey = new byte[10];
            boolean addedFragment = coreNode.addFragment(username, follower.getPublicKey(), mapKey, cipherText, follower.hashAndSignMessage(cipherText));
            assertTrue("added fragment", !addedFragment);

            // add storage allowance

            int frags = 10;
            for (int i = 0; i < frags; i++) {
                coreNode.registerFragment(username, new InetSocketAddress("localhost", 666), new byte[10 + i]);
            }
            long quota = coreNode.getQuota(username);
            assertTrue("quota after registering fragment", quota == coreNode.fragmentLength() * frags);

            // try again adding fragment
            addedFragment = coreNode.addFragment(username, follower.getPublicKey(), mapKey, cipherText, follower.hashAndSignMessage(cipherText));
            assertTrue("added fragment", addedFragment);

            // get fragment and verify contents are the same
            ByteArrayWrapper blob = coreNode.getFragment(username, follower.getPublicKey(), mapKey);
            assertTrue("retrieved blob equality", new ByteArrayWrapper(cipherText).equals(blob));

            //
            //create a friend
            //
            User friend = User.random();
            String friendname = "FRIEND";
            UserContext friendContext = new UserContext(friendname, friend, null, coreNode, system);

            //
            //add to coreNode
            //
            assertTrue("added friend", friendContext.register());
        } finally {
            system.shutdown();
        }
    }

    @Test public void abstractTest() throws Exception 
    {

        MockCoreNode coreNode = new MockCoreNode();
        coreNodeTests(coreNode);
    }

    @Test public void httpTest() throws Exception
    {
        try
        {
            HTTPCoreNodeServer server = null;
            try
            {
                MockCoreNode mockCoreNode = new MockCoreNode();
                InetAddress address = InetAddress.getByName("localhost");

                server = new HTTPCoreNodeServer(mockCoreNode,address, AbstractCoreNode.PORT);
                server.start();

                URL url = new URL("http://localhost:"+AbstractCoreNode.PORT+"/");
                HTTPCoreNode clientCoreNode = new HTTPCoreNode(url);

                coreNodeTests(clientCoreNode);
            } finally {
                if (server != null)
                    server.close();    
            }
        } catch (Throwable t) {
            t.printStackTrace();
            throw t;
        }
    }

    /*
       @Test public void sqlTest()
       {
       Random random = new Random(666);

       SQLiteCoreNode coreNode = null;

       try
       {
       coreNode = new SQLiteCoreNode("corenode_test.db");
       } catch (SQLException e){
       e.printStackTrace();
       fail(); 
       }

       }
       */



}
