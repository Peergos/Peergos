package peergos.storage.net;

import peergos.crypto.SSL;
import peergos.directory.DirectoryServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.cert.Certificate;
import java.security.SecureRandom;
import java.util.Enumeration;

public class IP
{
    public static InetAddress getMyPublicAddressFromDirectoryServer() {
        Certificate[] dirs = SSL.getDirectoryServerCertificates();
        Certificate dir;
        try {
            while (true) {
                dir = dirs[new SecureRandom().nextInt() % dirs.length];
                // synchronously contact a directory server to sign our certificate
                String address = SSL.getCommonName(dir);
                URL target = new URL("http", address, DirectoryServer.PORT, "/myIP");
                System.out.println("Looking up public IP " + target.toString());
                HttpURLConnection conn = (HttpURLConnection) target.openConnection();
                conn.setRequestMethod("GET");

                InputStream in = conn.getInputStream();
                byte[] buf = new byte[4 * 1024];
                ByteArrayOutputStream bout = new ByteArrayOutputStream();
                while (true) {
                    int r = in.read(buf);
                    if (r < 0)
                        break;
                    bout.write(buf, 0, r);
                }
                String res = new String(bout.toByteArray());
                System.out.println("myIP service returned "+res);
                return InetAddress.getByName(res);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public static InetAddress getMyPublicAddress() throws IOException
    {
        InetAddress us = getMyPublicAddressFromDirectoryServer();
        if (us != null)
            return us;
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
