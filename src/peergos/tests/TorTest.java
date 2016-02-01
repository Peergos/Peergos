package peergos.tests;

import org.junit.Test;
import com.subgraph.orchid.*;

import java.io.*;
import java.net.*;
import javax.net.*;

public class TorTest {

    @Test
    public void connect() throws IOException {
        TorClient tor = new TorClient();
        tor.start();
        while (true)
            try {
                tor.waitUntilReady();
                break;
            } catch (InterruptedException e) {}

        SocketFactory sf = tor.getSocketFactory();

        URL url = new URL("http://www.google.com/");
        String websiteAddress = url.getHost();

        String file = url.getFile();
        Socket clientSocket = sf.createSocket(websiteAddress, 80);
        BufferedReader response = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        OutputStreamWriter outWriter = new OutputStreamWriter(clientSocket.getOutputStream());
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
