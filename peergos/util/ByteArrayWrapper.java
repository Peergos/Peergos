package peergos.util;

import java.util.Arrays;

public class ByteArrayWrapper
{
    public byte[] data;

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
}
