package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;

public class Server extends Thread
{
    private DatagramSocket socket;
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    public static final int MAX_PACKET_SIZE = MAX_UDP_PACKET_SIZE;

    public Server(int port) throws IOException
    {
        socket = new DatagramSocket(port);
    }

    public void run() {

        while (true) {
            try {
                byte[] buf = new byte[MAX_PACKET_SIZE];

                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                System.out.println(new String(packet.getData(), packet.getOffset(), packet.getLength()));

                InetAddress address = packet.getAddress();
                int port = packet.getPort();
                DatagramPacket response = new DatagramPacket(buf, buf.length, address, port);
                socket.send(response);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) throws IOException
    {
        Args.parse(args);
        int port = Args.getInt("port", 8080);
        new Server(port).start();

        DatagramSocket socket = new DatagramSocket();
        byte[] buf = "teststring".getBytes();
        InetAddress address = InetAddress.getByName("localhost");
        DatagramPacket outpacket = new DatagramPacket(buf, buf.length, address, port);
        socket.send(outpacket);

        DatagramPacket inpacket = new DatagramPacket(new byte[MAX_PACKET_SIZE], MAX_PACKET_SIZE);
        socket.receive(inpacket);

        String received = new String(inpacket.getData());
        System.out.println("Received: " + received);

        socket.close();
    }
}
