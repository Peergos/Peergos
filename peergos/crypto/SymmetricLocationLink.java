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
        this.loc = loc.encrypt(from, iv);
        link = ArrayOps.concat(iv, from.encrypt(to.getKey().getEncoded(), iv));
    }

    public SymmetricLocationLink(SymmetricKey from, SymmetricKey to, Location loc)
    {
        this(from, to, loc, SymmetricKey.randomIV());
    }

    public SymmetricLocationLink(byte[] raw) throws IOException
    {
        DataInputStream din = new DataInputStream(new ByteArrayInputStream(raw));
        this.link = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
        this.loc = Serialize.deserializeByteArray(din, Integer.MAX_VALUE);
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

    public byte[] initializationVector()
    {
        return Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
    }

    public SymmetricKey target(SymmetricKey from)
    {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
        byte[] encoded = from.decrypt(Arrays.copyOfRange(link, SymmetricKey.IV_SIZE, link.length), iv);
        return new SymmetricKey(encoded);
    }

    public Location targetLocation(SymmetricKey from) throws IOException {
        byte[] iv = Arrays.copyOfRange(link, 0, SymmetricKey.IV_SIZE);
        return Location.decrypt(from, iv, loc);
    }
}
