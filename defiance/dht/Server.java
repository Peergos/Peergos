package defiance.dht;

import defiance.util.*;

import java.io.*;
import java.net.*;
import java.util.*;

public class Server extends Thread
{
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    public static final int MAX_PACKET_SIZE = MAX_UDP_PACKET_SIZE; // TODO: decrease this once protocol is finalized

    public static enum State
    {
        JOINING, READY;
    }

    private State state = State.JOINING;
    private DatagramSocket socket;
    private NodeID us;
    private List<NodeID> leftNeighbours = new ArrayList();
    private List<NodeID> rightNeighbours = new ArrayList();
    private SortedMap<Long, NodeID> friends = new TreeMap();

    public Server(int port) throws IOException
    {
        socket = new DatagramSocket(port);
        us = new NodeID();
    }

    public void run()
    {
        // connect to network
        if (!Args.hasOption("firstNode"))
            try {
                int port = Args.getInt("contactPort", 8080);
                InetAddress entry = InetAddress.getByName(Args.getParameter("contactIP"));

                // send JOIN message to ourselves via contact point
                Message join = new Message.JOIN(us);

                // wait for JOIN message to arrive, if it doesn'tID is already taken

            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        state = State.READY;

        byte[] buf = new byte[MAX_PACKET_SIZE];
        while (true) {
            try {


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

    private void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        byte[] buf = new byte[MAX_PACKET_SIZE];
        DatagramPacket response = new DatagramPacket(buf, buf.length, addr, port);
        socket.send(response);
    }

    private void routeMessage()
    {

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
