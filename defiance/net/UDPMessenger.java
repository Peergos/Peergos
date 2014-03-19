package defiance.net;

import defiance.dht.Message;
import defiance.dht.Messenger;

import java.io.*;
import java.net.*;
import java.util.logging.*;

public class UDPMessenger extends Messenger
{
    public static final int MAX_UDP_PACKET_SIZE = 65507;
    public static final int MAX_PACKET_SIZE = MAX_UDP_PACKET_SIZE; // TODO: decrease this once protocol is finalized

    private final Logger LOGGER;
    private DatagramSocket socket;

    public UDPMessenger(int port, Logger LOGGER) throws IOException
    {
        socket = new DatagramSocket(port);
        this.LOGGER = LOGGER;
    }

    @Override
    public void join(InetAddress addr, int port) {

    }

    @Override
    public void sendMessage(Message m, InetAddress addr, int port) throws IOException
    {
        if (Message.LOG)
            LOGGER.log(Level.ALL, String.format("Sent %s with target %d to %s:%d\n", m.name(), m.getTarget(), addr, port));
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        m.write(new DataOutputStream(bout));
        DatagramPacket response = new DatagramPacket(bout.toByteArray(), bout.size(), addr, port);
        socket.send(response);
    }

    @Override
    public Message awaitMessage(int duration) throws IOException
    {
        socket.setSoTimeout(duration);
        byte[] buf = new byte[MAX_PACKET_SIZE];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        Message m = Message.read(new DataInputStream(new ByteArrayInputStream(packet.getData())));
        socket.setSoTimeout(0);
        return m;
    }
}
