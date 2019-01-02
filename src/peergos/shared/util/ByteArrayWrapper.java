package peergos.shared.util;

import java.util.Arrays;

/** A convenience wrapper for using byte arrays in collections and sorted collections.
 *
 */
public class ByteArrayWrapper implements Comparable<ByteArrayWrapper>
{
    public final byte[] data;

    public ByteArrayWrapper(byte[] data)
    {
        this.data = data;
    }

    @Override
    public int hashCode()
    {
        return java.util.Arrays.hashCode(data);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        ByteArrayWrapper other = (ByteArrayWrapper) obj;
        if (!Arrays.equals(data, other.data))
            return false;
        return true;
    }

    @Override
    public int compareTo(ByteArrayWrapper o) {
        if (data.length < o.data.length)
            return -1;
        if (data.length > o.data.length)
            return 1;
        for (int i=0; i < data.length; i++)
            if (data[i] != o.data[i])
                return (0xff & data[i]) - (0xff & o.data[i]);
        return 0;
    }

    @Override
    public String toString() {
        return ArrayOps.bytesToHex(data);
    }
}
