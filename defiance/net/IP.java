package defiance.net;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class IP
{
    public static InetAddress getMyPublicAddress() throws IOException
    {
        // try to find our public IP address
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()){
            NetworkInterface current = interfaces.nextElement();
            if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
            Enumeration<InetAddress> addresses = current.getInetAddresses();
            InetAddress ipv6 = null;
            while (addresses.hasMoreElements()){
                InetAddress current_addr = addresses.nextElement();
                if (current_addr.isLoopbackAddress()) continue;
                if (current_addr instanceof Inet6Address)
                {
                    ipv6 = current_addr;
                    continue;
                }
//                System.out.println("my public address: "+current_addr.getHostAddress());
                return current_addr;
            }
            if (ipv6 != null)
                return ipv6;
        }
       throw new IOException("Is server connected to the internet?");
    }
}
