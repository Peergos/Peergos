/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.io.*;
import java.net.*;

/**
 * UDP implementation of the <tt>IceSocketWrapper</tt>.
 *
 * @author Sebastien Vincent
 */
public class IceUdpSocketWrapper
    extends IceSocketWrapper
{
    /**
     * Delegate UDP <tt>DatagramSocket</tt>.
     */
    private final DatagramSocket socket;

    /**
     * Constructor.
     *
     * @param delegate delegate <tt>DatagramSocket</tt>
     */
    public IceUdpSocketWrapper(DatagramSocket delegate)
    {
        this.socket = delegate;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(DatagramPacket p) throws IOException
    {
        socket.send(p);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(DatagramPacket p) throws IOException
    {
        socket.receive(p);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        socket.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InetAddress getLocalAddress()
    {
        return socket.getLocalAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLocalPort()
    {
        return socket.getLocalPort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return socket.getLocalSocketAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket getTCPSocket()
    {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatagramSocket getUDPSocket()
    {
        return socket;
    }
}
