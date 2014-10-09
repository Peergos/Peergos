package peergos.directory;

import com.sun.net.httpserver.HttpServer;
import peergos.crypto.DirectoryCertificates;
import peergos.crypto.SSL;
import peergos.storage.net.IP;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCSException;
import peergos.util.Args;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class DirectoryServer
{
    public static final int PORT = 9998;
    public static final int THREADS = 5;
    public static final int CONNECTION_BACKLOG = 100;
    private final Map<String, Certificate> storageServers = new ConcurrentHashMap();
    private final Map<String, Certificate> coreServers = new ConcurrentHashMap();
    private final Certificate ourCert;
    private final KeyPair signing;
    private final HttpServer server;
    private final String commonName;
    private byte[] cachedServerList = null;
    private byte[] cachedCoreServerList = null;

    private DirectoryServer(String keyfile, char[] passphrase, int port)
            throws IOException, NoSuchAlgorithmException, InvalidKeySpecException, OperatorCreationException, PKCSException
    {
        Certificate[] dirs = SSL.getDirectoryServerCertificates();
        signing = SSL.loadKeyPair(keyfile, passphrase);
        //start HTTP server
        InetAddress us = IP.getMyPublicAddress();
        InetSocketAddress address = new InetSocketAddress(us, port);
        commonName = Args.getParameter("domain", us.getHostAddress());
        Certificate tmp = null;
        for (Certificate dir: dirs)
            if (SSL.getCommonName(dir).equals(commonName))
                tmp = dir;
        ourCert = tmp;
        for (Certificate cert: SSL.getCoreServerCertificates())
            coreServers.put(SSL.getCommonName(cert), cert);
        System.out.println("Directory Server listening on: " + us.getHostAddress() + ":" + port);
        server = HttpServer.create(address, CONNECTION_BACKLOG);
        server.createContext("/dir", new StorageListHandler(this));
        server.createContext("/dirHuman", new ReadableStorageListHandler(this));
        server.createContext("/sign", new SignRequestHandler(this));
        server.createContext("/registerCore", new SignRequestHandler(this));
        server.createContext("/dirCore", new CoreListHandler(this));
        server.setExecutor(Executors.newFixedThreadPool(THREADS));
        server.start();
    }

    public Certificate signCertificate(PKCS10CertificationRequest csr)
    {
        System.out.println("Signing certificate for "+SSL.getCommonName(csr));
        Certificate signed =  SSL.signCertificate(csr, signing.getPrivate(), ourCert, false);
        // TODO don't overwrite existing certificates as this can easily be DOSed, rather require a cert invalidation first
        storageServers.put(SSL.getCommonName(csr), signed);
        return signed;
    }

    public byte[] getReadableStorageServers()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            DataOutputStream dout = new DataOutputStream(bout);
            Collection<Certificate> servers = storageServers.values();
            byte[] html = String.format("<html><title>Storage Server list</title><body><h1>Storage Server list (%d)</h1><ul>", servers.size()).getBytes();
            dout.write(html);
            for (Certificate c: servers)
            {
                String cn = SSL.getCommonName(c);
                dout.write("<ul>".getBytes());
                dout.write(cn.getBytes());
                dout.write("</ul>".getBytes());
            }
            dout.write("</ul></body></html>".getBytes());
        } catch (IOException e)
        {e.printStackTrace();}
        return bout.toByteArray();
    }

    public synchronized byte[] getCoreServers()
    {
        if (cachedCoreServerList == null)
            cachedCoreServerList = serialiseCoreServerList();
        return cachedCoreServerList;
    }

    public synchronized byte[] getStorageServers()
    {
        if (cachedServerList == null)
            cachedServerList = serialiseServerList();
        return cachedServerList;
    }

    private byte[] serialiseCoreServerList()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            DataOutputStream dout = new DataOutputStream(bout);
            byte[] html = "Core Server list".getBytes();
            dout.writeInt(html.length);
            dout.write(html);
            Collection<Certificate> servers = coreServers.values();
            dout.writeInt(servers.size());
            for (Certificate c: servers)
            {
                byte[] bin = c.getEncoded();
                dout.writeInt(bin.length);
                dout.write(bin);
            }
        } catch (IOException e)
        {e.printStackTrace();}
        catch (CertificateEncodingException e)
        {e.printStackTrace();}
        return bout.toByteArray();
    }

    private byte[] serialiseServerList()
    {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        try {
            DataOutputStream dout = new DataOutputStream(bout);
            byte[] html = "Storage Server list".getBytes();
            dout.writeInt(html.length);
            dout.write(html);
            Collection<Certificate> servers = storageServers.values();
            dout.writeInt(servers.size());
            for (Certificate c: servers)
            {
                byte[] bin = c.getEncoded();
                dout.writeInt(bin.length);
                dout.write(bin);
            }
        } catch (IOException e)
        {e.printStackTrace();}
        catch (CertificateEncodingException e)
        {e.printStackTrace();}
        return bout.toByteArray();
    }

    public static void createAndStart(String keyfile, char[] passphrase, int port)
    {
        try {
            new DirectoryServer(keyfile, passphrase, port);
        } catch (Exception e)
        {
            e.printStackTrace();
            System.out.println("Couldn't start directory server!");
        }
    }
}
