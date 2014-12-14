package peergos.storage.net;

import peergos.crypto.SSL;
import peergos.directory.DirectoryServer;
import peergos.net.upnp.Upnp;
import peergos.util.Args;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.security.cert.Certificate;
import java.security.SecureRandom;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

public class IPMappings
{
    public static final int STORAGE_PORT = 8000;

    private static InetAddress getMyPublicAddressFromDirectoryServer() {
        Certificate[] dirs = SSL.getDirectoryServerCertificates();
        Certificate dir;
        try {
            if (Args.hasOption("local"))
                return InetAddress.getByName("localhost");
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

    private static final Map<Integer, InetSocketAddress> mappings = new HashMap();

    public static synchronized InetSocketAddress getMyPublicAddress(int desiredExternalPort) throws IOException
    {
        if (Args.hasOption("domain"))
            return new InetSocketAddress(InetAddress.getByName(Args.getParameter("domain")), desiredExternalPort);
        if (mappings.containsKey(desiredExternalPort))
            return mappings.get(desiredExternalPort);
        if (Args.hasOption("local"))
            return new InetSocketAddress(InetAddress.getByName("localhost"), desiredExternalPort);
        InetAddress us = getMyPublicAddressFromDirectoryServer();

        // try to find our public IP address on a NIC
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()){
            NetworkInterface current = interfaces.nextElement();
            if (!current.isUp() || current.isLoopback() || current.isVirtual()) continue;
            Enumeration<InetAddress> addresses = current.getInetAddresses();
//            InetAddress ipv6 = null;
            while (addresses.hasMoreElements()){
                InetAddress current_addr = addresses.nextElement();
                if (current_addr.isLoopbackAddress()) continue;
                if (current_addr instanceof Inet6Address)
                {
//                    ipv6 = current_addr;
                    continue;
                }
                if (current_addr.equals(us)) {
                    mappings.put(desiredExternalPort, new InetSocketAddress(us, desiredExternalPort));
                    return mappings.get(desiredExternalPort);
                }
            }
//            if (ipv6 != null)
//                return ipv6;
        }
        // try to open UPNP route to see if our router has a public IP
        InetSocketAddress externalAddress = Upnp.openUPNPConnection(us, desiredExternalPort);
        if (externalAddress != null) {
            mappings.put(externalAddress.getPort(), externalAddress);
            return externalAddress;
        }
       throw new IOException("Couldn't get an externally visible IP address. Are you connected to the internet?");
    }
}
