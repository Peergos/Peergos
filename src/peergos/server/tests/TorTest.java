package peergos.server.tests;

import org.junit.Test;
import com.subgraph.orchid.*;

import java.io.*;
import java.net.*;
import javax.net.*;
import javax.net.ssl.*;

public class TorTest {

//    @Test
    public void connect() throws IOException {
        TorClient tor = new TorClient();
        tor.start();
        while (true)
            try {
                tor.waitUntilReady();
                break;
            } catch (InterruptedException e) {}

        SocketFactory sf = tor.getSocketFactory();

        String websiteAddress = "www.google.com";
        SSLSocketFactory ssl = (SSLSocketFactory)SSLSocketFactory.getDefault();

        String file = "/";
        Socket unsafeSocket = sf.createSocket(websiteAddress, 443);
        Socket sslSocket = ssl.createSocket(unsafeSocket, websiteAddress, 443, false);
        BufferedReader response = new BufferedReader(new InputStreamReader(sslSocket.getInputStream()));
        OutputStreamWriter outWriter = new OutputStreamWriter(sslSocket.getOutputStream());
        outWriter.write("GET " + file + " HTTP/1.0\r\n\n");
        outWriter.flush();

        StringBuilder b = new StringBuilder();
        String line;
        while ((line = response.readLine()) != null)
            b.append(line + "\n");

        String resp = b.toString();
        System.out.println(resp);
    }
}
