package peergos.corenode;

import peergos.crypto.*;
import peergos.util.ByteArrayWrapper;

import java.security.PublicKey;

import java.util.*;
import java.io.*;
import java.net.*;


public abstract class AbstractCoreNode
{
    public static final float FRAC_TOLERANCE = 0.001f;
    private static final long DEFAULT_FRAGMENT_LENGTH = 0x10000;

    /**
     * Maintains meta-data about fragments stored in the DHT,
     * the relationship between users and with whom user fragments
     * are shared.      
     */ 

    //
    // TODO: Usage, assume all fragments same size
    // TODO: share( String key, String sharer, String Shareee, ByteArrayWrapper b1, ByteArrayWrapper b2)
    // TODO: update key 
    //
    class UserData
    {
        private final Map<UserPublicKey, ByteArrayWrapper> sharingKeys;
        //private final Map<ByteArrayWrapper, ByteArrayWrapper> fragments;
        private final Map<UserPublicKey, Map<ByteArrayWrapper, ByteArrayWrapper> > fragments;

        UserData()
        {
            this.sharingKeys = new HashMap<UserPublicKey, ByteArrayWrapper>();
            this.fragments = new HashMap<UserPublicKey, Map<ByteArrayWrapper, ByteArrayWrapper> >();
        }
    }  

    class StorageNodeState
    {
        private long size;

        private final String owner;
        private final Map<String, Float> storageFraction;
        private final InetSocketAddress address;
        StorageNodeState(String owner, InetAddress address, int port, long size, Map<String, Float> fractions)
        {
            this.owner = owner;
            this.address = new InetSocketAddress(address, port);
            this.storageFraction = new HashMap<String,Float>(fractions);
            this.size = size;
        }

        private void setSize(long size){this.size = size;}
        public int hashCode(){return address.hashCode();}
        public boolean equals(Object that)
        {
            if (! (that instanceof StorageNodeState))
                return false;

            return ((StorageNodeState) that).address.equals(this.address);
        }
    } 

    protected final Map<UserPublicKey, UserData> userMap;
    protected final Map<String, UserPublicKey> userNameToPublicKeyMap;
    protected final Map<UserPublicKey, String> userPublicKeyToNameMap;

    protected final long fragmentLength;
    //
    // quota stuff
    //

    /*
    //
    // aims
    // 1. calculate how much storage space a user has donated to other users (and themselves)  (via 1 or more storage nodes) 
    // 2. calculate how much storage space a Storage node has available to which other users
    //
    //
    //map of username to list of storage nodes that are donating on behalf of this user  (with fraction)
    protected final Map<InetSocketAddress, (String owner, Map<(String user, float frac))> > storageState;
    //derivable from above map
    protected final Map<String, List<InetSocketAddress> > userStorageFactories;

    //set of fragments donated by this storage node 
    protected final Map<InetSocketAdress, Set<ByteArrayWrapper> > storageNodeDonations;
    */
    protected final Map<InetSocketAddress, StorageNodeState> storageStates;
    protected final Map<String, Set<StorageNodeState> > userStorageFactories;
    protected final Map<StorageNodeState, Set<ByteArrayWrapper> > storageNodeDonations;


    public AbstractCoreNode()
    {
        this(DEFAULT_FRAGMENT_LENGTH);
    } 
    public AbstractCoreNode(long fragmentLength) 
    {
        this.fragmentLength = fragmentLength;
        this.userMap = new HashMap<UserPublicKey, UserData>();
        this.userNameToPublicKeyMap = new HashMap<String, UserPublicKey>();
        this.userPublicKeyToNameMap = new HashMap<UserPublicKey, String>();

        this.storageStates = new HashMap<InetSocketAddress, StorageNodeState>();
        this.userStorageFactories =new HashMap<String, Set<StorageNodeState> > ();
        this.storageNodeDonations = new HashMap<StorageNodeState, Set<ByteArrayWrapper> >();
    } 

    public long fragmentLength(){return fragmentLength;}

    public synchronized UserPublicKey getPublicKey(String username)
    {
        return userNameToPublicKeyMap.get(username);
    }

    /*
     * @param userKey X509 encoded public key
     * @param signedHash the SHA hash of bytes in the username, signed with the user private key 
     * @param username the username that is being claimed
     */
    public boolean addUsername(String username, byte[] encodedUserKey, byte[] signedHash)
    {
        UserPublicKey key = new UserPublicKey(encodedUserKey);

        if (! key.isValidSignature(signedHash, username.getBytes()))
            return false;

        return addUsername(username, key);
    }

    protected synchronized boolean addUsername(String username, UserPublicKey key)
    {
        if (userNameToPublicKeyMap.containsKey(username))
            return false;
        if (userPublicKeyToNameMap.containsKey(key))
            return false;

        userNameToPublicKeyMap.put(username, key); 
        userPublicKeyToNameMap.put(key, username); 
        userMap.put(key, new UserData());
        return true;
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a friend
     * @param signedHash the SHA hash of userBencodedKey, signed with the user private key 
     * @param encodedFriendName the bytes of the friendname sined with userKey 
     */
    public boolean addSharingKey(String username, byte[] encodedSharingPublicKey, byte[] sharingKeyPairBlob, byte[] signedHash)
    {
        UserPublicKey key = null; 
        synchronized(this)
        {
            key = userNameToPublicKeyMap.get(username);
        }
        
        if (key == null || ! key.isValidSignature(signedHash, sharingKeyPairBlob))
            return false;

        ByteArrayWrapper b = new ByteArrayWrapper(sharingKeyPairBlob);

        return addSharingKey(key, new UserPublicKey(encodedSharingPublicKey), b);
    }

    protected synchronized boolean addSharingKey(UserPublicKey userKey, UserPublicKey sharingPublicKey, ByteArrayWrapper sharingKeyPairBlob)
    {
        UserData userData = userMap.get(userKey);

        if (userData == null)
            return false;
        if (userData.sharingKeys.containsKey(sharingPublicKey))
            return false;

        userData.sharingKeys.put(sharingPublicKey, sharingKeyPairBlob);
        userData.fragments.put(sharingPublicKey, new HashMap<ByteArrayWrapper, ByteArrayWrapper>());
        return true; 
    }
    
    protected synchronized boolean removeSharingKey(UserPublicKey userKey, UserPublicKey sharingPublicKey)
    {
        UserData userData = userMap.get(userKey);

        if (userData == null)
            return false;
        
        return userData.sharingKeys.remove(sharingPublicKey) != null;
    }
    
    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param signedHash the SHA hash of encodedFragmentData, signed with the user private key 
     * @param encodedFragmentData fragment meta-data encoded with userKey
     */ 
    
    //public boolean addFragment(byte[] userKey, byte[] signedHash, byte[] encodedFragmentData)
    public boolean addFragment(String username, byte[] encodedSharingPublicKey, byte[] signedFragmentData, byte[] sharingKeySignedHash)
    {
        UserPublicKey userKey = null;
        synchronized(this)
        {
            userKey = userNameToPublicKeyMap.get(username);
        }
        if (userKey == null)
            return false;
            
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingPublicKey);
        synchronized(this)
        {
            if (! userMap.get(userKey).sharingKeys.containsKey(sharingKey))
                return false;
        }
          
        if (! sharingKey.isValidSignature(sharingKeySignedHash, signedFragmentData))
            return false;

        return addFragment(userKey, sharingKey, signedFragmentData);
    }

    protected synchronized boolean addFragment(UserPublicKey userKey, UserPublicKey sharingKey, byte[] signedFragmentData)
    {
         
        UserData userData = userMap.get(userKey);

        if (userData == null)
            return false;

        String username = userPublicKeyToNameMap.get(userKey);
        if (remainingStorage(username) < fragmentLength())
            return false;

        Map<ByteArrayWrapper, ByteArrayWrapper> fragments = userData.fragments.get(sharingKey);
        if (fragments == null)
            return false;

        byte[] hash = sharingKey.hash(signedFragmentData);
        ByteArrayWrapper hashW = new ByteArrayWrapper(hash);
        if (fragments.containsKey(hashW))
            return false;
        
        fragments.put(hashW, new ByteArrayWrapper(signedFragmentData));
        return true;
    }

    /*
     * @param userKey X509 encoded key of user that wishes to add a fragment
     * @param hash the hash of the fragment to be removed 
     * @param signedHash the SHA hash of hash, signed with the user private key 
     */ 
    //public boolean removeFragment(byte[] userKey, byte[] signedHash, byte[] hash)
    public boolean removeFragment(String username, byte[] encodedSharingKey, byte[] fragmentHash, byte[] userKeySignedHash, byte[] sharingKeySignedHash)
    {
        UserPublicKey userKey = null;
        UserPublicKey sharingKey = new UserPublicKey(encodedSharingKey);

        synchronized(this)
        {
            userKey = userNameToPublicKeyMap.get(username);
            if (userKey == null)
                return false;
            if (! userMap.get(userKey).sharingKeys.containsKey(sharingKey))
                return false;
        }
        if (! userKey.isValidSignature(fragmentHash, userKeySignedHash))
           return false; 

        if (! sharingKey.isValidSignature(fragmentHash, sharingKeySignedHash))
            return false;

        return removeFragment(userKey, sharingKey, fragmentHash);
    }

    protected synchronized boolean removeFragment(UserPublicKey userKey, UserPublicKey sharingKey, byte[] fragmentHash)
    {
        UserData userData = userMap.get(userKey);

        if (userData == null)
            return false;
        Map<ByteArrayWrapper, ByteArrayWrapper> fragments = userData.fragments.get(sharingKey);
        if (fragments == null)
            return false;

        return fragments.remove(new ByteArrayWrapper(fragmentHash)) != null;
    }

    /*
     * @param userKey X509 encoded key of user to be removed 
     * @param username to be removed 
     * @param signedHash the SHA hash of the bytes that make up username, signed with the user private key 
     *
     */

    public boolean removeUsername(byte[] userKey, byte[] signedHash, String username)
    {
        UserPublicKey key = new UserPublicKey(userKey);

        if (! Arrays.equals(key.hash(username),key.unsignMessage(signedHash)))
            return false;

        return removeUsername(key, username);
    }

    protected synchronized boolean removeUsername(UserPublicKey key, String username)
    {
        userPublicKeyToNameMap.remove(key);
        userMap.remove(key);
        return userNameToPublicKeyMap.remove(username) != null;
    }

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public synchronized Iterator<ByteArrayWrapper> getSharingKeys(String username, byte[] signedHash)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        
        if (userKey == null || ! userKey.isValidSignature(signedHash, username.getBytes()))
            return null;
            
        UserData userData = userMap.get(userKey);
        return Collections.unmodifiableCollection(userData.sharingKeys.values()).iterator();
        
    } 

    /*
     * @param userKey X509 encoded key of user that wishes to share a fragment 
     * @param signedHash the SHA hash of userKey, signed with the user private key 
     */
    public synchronized Iterator<ByteArrayWrapper> getFragments(String username, byte[] encodedSharingKey)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(username);
        if (userKey == null)
            return null;
        
        UserData userData = userMap.get(userKey);
        
        Map<ByteArrayWrapper, ByteArrayWrapper> sharedFragments = userData.fragments.get(new UserPublicKey(encodedSharingKey));
        
        if (sharedFragments == null)
            return null;
            
        return Collections.unmodifiableCollection(sharedFragments.values()).iterator();
    }

    public boolean addStorageNodeState(String owner, InetAddress address, int port, long size)
    {
        Map<String, Float> fracs = new HashMap<String, Float>();
        fracs.put(owner, 1.f);
        return addStorageNodeState(owner, address, port, size, fracs);
    }
    public boolean addStorageNodeState(String owner, InetAddress address, int port, long size, Map<String, Float> fracs)
    {
        if (size < 0)
            return false;
        //
        // validate map entries
        //
        float totalFraction = 0.f;
        for (Float frac: fracs.values())
            if (frac < 0)
                return false;
            else
                totalFraction += frac;

        if (totalFraction -1 > FRAC_TOLERANCE)
            return false;

        StorageNodeState state = new StorageNodeState(owner, address, port, size, fracs);
        return addStorageNodeState(state);
    }

    private synchronized boolean addStorageNodeState(StorageNodeState state)
    {

        if (! userNameToPublicKeyMap.containsKey(state.owner))
            return false;
        if (! storageStates.containsKey(state.address))
            return false;

        for (String user: state.storageFraction.keySet())
            if (! userNameToPublicKeyMap.containsKey(user))
                return false;

        storageStates.put(state.address, state);

        for (String user: state.storageFraction.keySet())
        {
            if (userStorageFactories.get(user) == null)
                userStorageFactories.put(user, new HashSet<StorageNodeState>());

            userStorageFactories.get(user).add(state);
        }
        return true;
    }

    private synchronized long getQuota(String user)
    {
        if (! userNameToPublicKeyMap.containsKey(user))
            return -1l;

        Set<StorageNodeState> storageStates = userStorageFactories.get(user);
        if (storageStates == null)
            return -1l;
        long quota = 0l;
        
        for (StorageNodeState state: storageStates)
            quota += state.size* state.storageFraction.get(user);

        return quota;    
    }

    private synchronized long getUsage(String user)
    {
        UserPublicKey userKey = userNameToPublicKeyMap.get(user);
        if (userKey == null)
            return -1l;
        
       long usage = 0l;
       for (Map<ByteArrayWrapper, ByteArrayWrapper> fragmentsMap: userMap.get(userKey).fragments.values())
           usage += fragmentsMap.size() * fragmentLength();

        return usage;
    }

    private synchronized long remainingStorage(String user)
    {
        long quota = getQuota(user);
        long usage = getUsage(user);

        return Math.max(0, quota - usage);
    }
}
