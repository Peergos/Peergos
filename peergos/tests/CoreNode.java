
package peergos.tests;

import static org.junit.Assert.*;

import peergos.crypto.*;
import peergos.corenode.*;
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

    public void coreNodeTests(AbstractCoreNode coreNode) throws Exception
    {
        //generate key-pair & signed cert
        //
        User user = User.random();
        //        KeyPair keyPair = SSL.generateKeyPair();
        //        UserPublicKey userPublicKey = new UserPublicKey(keyPair.getPublic());
        String username = "USER";

        //        UserPublicKey userPrivateKey = new UserPublicKey(keyPair.getPrivate());
        byte[] signedHash = user.hashAndSignMessage(username.getBytes());//userPrivateKey.encryptMessageFor(userPrivateKey.hash(username.getBytes()));
        //
        //add to coreNode
        //
        boolean userAdded = coreNode.addUsername(username, user.getPublicKey(), signedHash);

        assertTrue("added user", userAdded);


        //
        //add sharing key to this user
        //
        KeyPair sharingKeyPair = SSL.generateKeyPair();

        UserPublicKey sharingPublicKey = new UserPublicKey(sharingKeyPair.getPublic());
        UserPublicKey sharingPrivateKey = new UserPublicKey(sharingKeyPair.getPrivate());
        signedHash = user.hashAndSignMessage(sharingPublicKey.getPublicKey());
        //byte[] signedSharingKeyPairBlob = userPublicKey.encryptMessageFor(sharingKeyPairBlob);
        //boolean followingUser = coreNode.allowSharingKey(username, sharingPublicKey.getPublicKey(), signedSharingKeyPairBlob, signedHash);
        boolean followingUser = coreNode.allowSharingKey(username, sharingPublicKey.getPublicKey(), signedHash);

        assertTrue("following user", followingUser);

        //
        //retrieve sharing key
        //
        boolean retrievedSharingKey = false;
        Iterator<UserPublicKey> sharingKeys = coreNode.getSharingKeys(username);
        while (sharingKeys.hasNext()) {
            UserPublicKey next = sharingKeys.next();
            retrievedSharingKey = next.equals(sharingPublicKey);
        }

        assertTrue("retrieved sharing key ", retrievedSharingKey);

        //generate some test data
        //
        byte[] fragmentData = new byte[500];
        random.nextBytes(fragmentData);
        byte[] cipherText = sharingPublicKey.encryptMessageFor(fragmentData);

        //
        //add fragment
        //
        signedHash = sharingPrivateKey.encryptMessageFor(sharingPrivateKey.hash(cipherText));
        byte[] mapKey = new byte[10];
        boolean addedFragment = coreNode.addFragment(username, sharingPublicKey.getPublicKey(), mapKey, cipherText, signedHash);
        assertTrue("added fragment", !addedFragment);

        // add storage allowance

        int frags = 10;
        for (int i=0; i < frags; i++) {
            coreNode.registerFragment(username, new InetSocketAddress("localhost", 666), new byte[10+i]);
        }
        long quota = coreNode.getQuota(username);
        assertTrue("quota after registering fragment", quota == coreNode.fragmentLength()*frags);

        // try again adding fragment
        addedFragment = coreNode.addFragment(username, sharingPublicKey.getPublicKey(), mapKey, cipherText, signedHash);
        assertTrue("added fragment", addedFragment);

        // get fragment and verify contents are the same
        ByteArrayWrapper blob = coreNode.getFragment(username, sharingPublicKey.getPublicKey(), mapKey);
        assertTrue("retrieved blob equality", new ByteArrayWrapper(cipherText).equals(blob));

        //
        //create a friend
        //
        KeyPair friendKeyPair = SSL.generateKeyPair();
        UserPublicKey friendPublicKey = new UserPublicKey(friendKeyPair.getPublic());
        UserPublicKey friendPrivateKey = new UserPublicKey(friendKeyPair.getPrivate());
        String friendname = "FRIEND";

        signedHash = friendPrivateKey.encryptMessageFor(friendPrivateKey.hash(friendname.getBytes()));
        //
        //add to coreNode
        //
        boolean friendAdded = coreNode.addUsername(friendname, friendPublicKey.getPublicKey(), signedHash);

        assertTrue("added friend", friendAdded);

    }

    /*
       @Test public void abstractTest() throws Exception 
       {

       MockCoreNode coreNode = new MockCoreNode();
       coreNodeTests(coreNode);
       }
       */

    @Test public void httpTest() throws Exception
    {
        try
        {
            HTTPCoreNodeServer server = null;
            try
            {
                MockCoreNode mockCoreNode = new MockCoreNode();
                InetAddress address = InetAddress.getByName("localhost");
                int port = 6666;

                server = new HTTPCoreNodeServer(mockCoreNode,address, port);
                server.start();

                URL url = new URL("http://localhost:6666/"); 
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
