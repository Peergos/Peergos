package peergos.tests;

import static org.junit.Assert.*;

import peergos.util.ByteArrayWrapper;
import org.junit.Test;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class Components
{
    @Test
    public void byteArrayWrapper()
    {
        byte[] a = new byte[32];
        ByteArrayWrapper aw = new ByteArrayWrapper(a);
        byte[] b = new byte[32];
        ByteArrayWrapper bw = new ByteArrayWrapper(b);
        assertEquals(aw, bw);

        Map<ByteArrayWrapper, String> map = new ConcurrentHashMap();
        map.put(aw, "Hi");
        assertEquals("Map contains: ", true, map.containsKey(bw));
    }
}
