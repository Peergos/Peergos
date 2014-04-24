
package peergos.tests;

import static org.junit.Assert.*;

import peergos.crypto.*;
import peergos.corenode.*;
import peergos.util.ByteArrayWrapper;

import org.junit.Test;

import java.util.*;
import java.io.*;

import java.security.*;
import java.security.cert.*;

import java.sql.*;

public class CoreNode
{
    class MockCoreNode extends AbstractCoreNode{}

    @Test public void abstractTest() throws Exception 
    {
        Random random = new Random(666);

        MockCoreNode coreNode = new MockCoreNode();

        //
        //generate key-pair & signed cert
        //
        KeyPair keyPair = SSL.generateKeyPair();
        UserPublicKey userPublicKey = new UserPublicKey(keyPair.getPublic());
        User user = new User(keyPair);
        String username = "USER";

        UserPublicKey userPrivateKey = new UserPublicKey(keyPair.getPrivate());
        byte[] signedHash = userPrivateKey.encryptMessageFor(userPrivateKey.hash(username.getBytes())); 
        //
        //add to coreNode
        //
        boolean userAdded = coreNode.addUsername(username, userPublicKey.getPublicKey(), signedHash);

        assertTrue("successfully added user", userAdded);


        //
        //add sharing key to this user
        //
        KeyPair sharingKeyPair = SSL.generateKeyPair();

        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(bout);
        oout.writeObject(sharingKeyPair);
        oout.close();
        byte[] sharingKeyPairBlob = bout.toByteArray();
        
        UserPublicKey sharingPublicKey  = new UserPublicKey(sharingKeyPair.getPublic());
        UserPublicKey sharingPrivateKey  = new UserPublicKey(sharingKeyPair.getPrivate());
        signedHash = userPrivateKey.encryptMessageFor(userPrivateKey.hash(sharingKeyPairBlob)); 
        //byte[] signedSharingKeyPairBlob = userPublicKey.encryptMessageFor(sharingKeyPairBlob);
        //boolean followingUser = coreNode.addSharingKey(username, sharingPublicKey.getPublicKey(), signedSharingKeyPairBlob, signedHash);
        boolean followingUser = coreNode.addSharingKey(username, sharingPublicKey.getPublicKey(), sharingKeyPairBlob, signedHash);

        assertTrue("following user", followingUser);

        //
        //retrieve sharing key
        //
        boolean retrievedSharingKey = false;
        signedHash = userPrivateKey.encryptMessageFor(userPrivateKey.hash(username.getBytes())); 
        Iterator<ByteArrayWrapper> sharingKeyPairBlobs = coreNode.getSharingKeys(username, signedHash);
        while (sharingKeyPairBlobs.hasNext())
        {
            byte[] signedBlob =sharingKeyPairBlobs.next().data;
            //byte[] unsignedSharingKeyBlob = userPrivateKey.unsignMessage(signedBlob);
            //ObjectInputStream oin = new ObjectInputStream(new ByteArrayInputStream(unsignedSharingKeyBlob));
            ObjectInputStream oin = new ObjectInputStream(new ByteArrayInputStream(signedBlob));
            try
            {
              KeyPair retrievedKeyPair = (KeyPair) oin.readObject();
              if (retrievedKeyPair.getPublic().equals(sharingKeyPair.getPublic()) && retrievedKeyPair.getPrivate().equals(sharingKeyPair.getPrivate()))
                retrievedSharingKey = true;
            } finally {
                oin.close();
            }
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
        boolean addedFragment = coreNode.addFragment(username, sharingPublicKey.getPublicKey(), cipherText, signedHash); 
        assertTrue("added fragment", addedFragment);

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
        if (true)
            return;

        Random random = new Random(666);

        MockCoreNode coreNode = new MockCoreNode();

        //
        //generate key-pair & signed cert
        //
        KeyPair keyPair = SSL.generateKeyPair();
        UserPublicKey userPublicKey = new UserPublicKey(keyPair.getPublic());
        User user = new User(keyPair);
        String username = "USER";

        UserPublicKey userPrivateKey = new UserPublicKey(keyPair.getPrivate());
        byte[] hash = userPrivateKey.hash(userPublicKey.getPublicKey());
        byte[] signedHash = userPrivateKey.encryptMessageFor(userPrivateKey.hash(username.getBytes())); 
        //
        //add to coreNode
        //
        boolean userAdded = coreNode.addUsername(userPublicKey.getPublicKey(), signedHash,username);

        assertTrue("successfully added user", userAdded);

        //
        //generate some test data
        //
        byte[] fragmentData = new byte[500];
        random.nextBytes(fragmentData);
        byte[] cipherText = userPublicKey.encryptMessageFor(fragmentData);

        //
        //add to user 
        //
        boolean fragmentAdded = coreNode.addFragment(userPublicKey.getPublicKey(), userPrivateKey.encryptMessageFor(userPublicKey.hash(cipherText)), cipherText);
        assertTrue("successfully added fragment", fragmentAdded);

        byte[] encoded = userPublicKey.getPublicKey();
        Iterator<ByteArrayWrapper> userFragments = coreNode.getFragments(encoded, userPrivateKey.encryptMessageFor(userPublicKey.hash(encoded)));

        assertTrue("found fragments", userFragments != null);
        //
        //get back message
        //
        boolean foundFragment = false;
        if (userFragments != null)
            while(userFragments.hasNext())
            {
                byte[] plainText = user.decryptMessage(userFragments.next().data);
                if (Arrays.equals(plainText, fragmentData))
                    foundFragment = true;
            }
        assertTrue("successfully found fragment", foundFragment);

        //
        //create a friend
        //
        KeyPair friendKeyPair = SSL.generateKeyPair();
        UserPublicKey friendPublicKey = new UserPublicKey(friendKeyPair.getPublic());
        UserPublicKey friendPrivateKey = new UserPublicKey(friendKeyPair.getPrivate());
        User friend = new User(friendKeyPair);
        String friendname = "FRIEND";

        //
        //add friend to corenode
        //
        boolean friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), new byte[0],friendname);

        assertFalse("successfully failed validation test", friendAdded);

        friendAdded = coreNode.addUsername(friendPublicKey.getPublicKey(), friendPrivateKey.encryptMessageFor(friendPublicKey.hash(friendname)),friendname);
        assertTrue("successfully added friend", friendAdded);

        //
        //user adds friend to his friend list
        //
        byte[] signedEncodedFriend = userPublicKey.encryptMessageFor(friendname.getBytes());

        boolean userAddedFriend = coreNode.addSharingKey(username,  userPrivateKey.encryptMessageFor(userPublicKey.hash(signedEncodedFriend)), signedEncodedFriend);
        assertTrue("userA successfully added friend to friend-list", userAddedFriend);

        Iterator<ByteArrayWrapper> it = coreNode.getSharingKeys(encoded, userPrivateKey.encryptMessageFor(userPublicKey.hash(encoded)));
        assertTrue("got sharing keys ", it != null);

        boolean foundFriend = false;
        while (it.hasNext())
        {
            ByteArrayWrapper b = it.next();
            byte[] unsigned = userPrivateKey.unsignMessage(b.data);
            if (java.util.Arrays.equals(unsigned, friendname.getBytes()))
                foundFriend = true;
        }
        assertTrue("found friend", foundFriend);
        System.out.println("MADE IT");

    }
    */

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
