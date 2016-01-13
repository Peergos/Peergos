package peergos.crypto;

import peergos.user.fs.Location;
import peergos.util.ArrayOps;
import peergos.util.Serialize;

import java.io.*;
import java.util.Arrays;

public class SymmetricLocationLink
{
    final byte[] link;
    final byte[] loc;

    public SymmetricLocationLink(SymmetricKey from, SymmetricKey to, Location loc, byte[] iv)
    {
        // TODO only do one encryption, and thus don't use nonce twice!
        this.loc = loc.encrypt(from, iv);
        link = ArrayOps.concat(iv, from.encrypt(to.getKey(), iv));
    }

    public SymmetricLocationLink(SymmetricKey from, SymmetricKey to, Location loc)
    {
        this(from, to, loc, from.createNonce());
    }

    public SymmetricLocationLink(byte[] raw) throws IOException
    {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        this.link = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
        this.loc = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
    }

    private SymmetricLocationLink(byte[] link, byte[] loc){
    	this.link = link;
    	this.loc = loc;
    }
    
    public byte[] serialize()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(bout);
        try {
            Serialize.serialize(link, dout);
            Serialize.serialize(loc, dout);
        } catch (IOException e) {e.printStackTrace();}
        return bout.toByteArray();
    }
    
    public static SymmetricLocationLink deserialize(DataInput din) throws IOException
    {
        byte[] link = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
        byte[] loc = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
        return new SymmetricLocationLink(link, loc);
    }

    public byte[] getNonce()
    {
        return Arrays.copyOfRange(link, 0, SymmetricKey.NONCE_BYTES);
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] iv = getNonce();
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.NONCE_BYTES, link.length), iv);
        return new SymmetricKey(encoded);
    }

    public Location targetLocation(SymmetricKey from) throws IOException {
        byte[] iv = getNonce();
        return Location.decrypt(from, iv, loc);
    }
}
