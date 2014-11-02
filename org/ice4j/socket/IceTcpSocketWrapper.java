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
 * TCP implementation of the <tt>IceSocketWrapper</tt>.
 *
 * @author Sebastien Vincent
 */
public class IceTcpSocketWrapper
    extends IceSocketWrapper
{
    /**
     * Delegate TCP <tt>Socket</tt>.
     */
    private final Socket socket;

    /**
     * InputStream for this socket.
     */
    private InputStream inputStream = null;

    /**
     * OutputStream for this socket.
     */
    private OutputStream outputStream = null;

    /**
     * Constructor.
     *
     * @param delegate delegate <tt>Socket</tt>
     *
     * @throws IOException if something goes wrong during initialization
     */
    public IceTcpSocketWrapper(Socket delegate)
        throws IOException
    {
        this.socket = delegate;
        if(!(delegate instanceof DelegatingSocket))
        {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(DatagramPacket p)
        throws IOException
    {
        if(socket instanceof DelegatingSocket)
        {
            ((DelegatingSocket)socket).send(p);
            return;
        }

        int len = p.getLength();
        int off = p.getOffset();
        byte data[] = new byte[len + 2];
        data[0] = (byte)((len >> 8) & 0xff);
        data[1] = (byte)(len & 0xff);
        System.arraycopy(p.getData(), off, data, 2, len);
        outputStream.write(data, 0, len + 2);
        data = null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(DatagramPacket p) throws IOException
    {
        if(socket instanceof DelegatingSocket)
        {
            ((DelegatingSocket)socket).receive(p);
            return;
        }

        DelegatingSocket.receiveFromNetwork(
                p,
                inputStream,
                this.getLocalAddress(),
                this.getLocalPort());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        try
        {
            socket.close();
        }
        catch(IOException e)
        {
        }
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
        return socket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatagramSocket getUDPSocket()
    {
        return null;
    }
}
