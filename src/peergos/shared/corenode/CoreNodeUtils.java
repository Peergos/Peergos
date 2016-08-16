package peergos.shared.corenode;

import java.io.DataInputStream;
import java.io.IOException;

import peergos.shared.util.Serialize;

public class CoreNodeUtils {
	public static final int MAX_KEY_LENGTH = 1024*1024;
	
    public static byte[] deserializeByteArray(DataInputStream din) throws IOException
    {
        return Serialize.deserializeByteArray(din, MAX_KEY_LENGTH);
    }

    public static byte[] getByteArray(int len) throws IOException
    {
        return Serialize.getByteArray(len, MAX_KEY_LENGTH);
    }

    public static String deserializeString(DataInputStream din) throws IOException
    {
        return Serialize.deserializeString(din, 1024);
    }
}
