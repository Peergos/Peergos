/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.io.*;
import java.net.*;

/**
 * TCP input stream for TCP socket. It is used to multiplex sockets and keep the
 * <tt>InputStream</tt> interface to users.
 *
 * @author Sebastien Vincent
 * @author Lyubomir Marinov
 */
public class TCPInputStream
    extends InputStream
{
    /**
     * The default size of the receive buffer of <tt>TCPInputStream</tt> if the
     * associated <tt>MultiplexingSocket</tt> does not specify a value.
     */
    private static final int DEFAULT_RECEIVE_BUFFER_SIZE = 65536;

    /**
     * The <tt>byte</tt> array with one element which is used by the
     * implementation of {@link #read()} in order to delegate to the
     * implementation of {@link #read(byte[], int, int)} for the purposes of
     * simplicity.
     */
    private final byte[] b = new byte[1];

    /**
     * The indicator which determines whether this <tt>TCPInputStream</tt> is
     * executing one of its <tt>read</tt> method implementations.
     */
    private boolean inRead;

    /**
     * Current packet being processed if any.
     */
    private DatagramPacket packet;

    /**
     * The <tt>data</tt> of {@link #packet}.
     */
    private byte[] packetData;

    /**
     * Current packet length.
     */
    private int packetLength;

    /**
     * Current offset.
     */
    private int packetOffset;

    /**
     * The <tt>Object</tt> which synchronizes the access to the read-related
     * state of this instance.
     */
    private final Object readSyncRoot = new Object();

    /**
     * The <tt>MultiplexingSocket</tt> which has initialized this instance and
     * is using it as its <tt>inputStream</tt>.
     */
    private final MultiplexingSocket socket;

    /**
     * Initializes a new <tt>TCPInputStream</tt>.
     *
     * @param socket
     */
    public TCPInputStream(MultiplexingSocket socket)
    {
        if (socket == null)
            throw new NullPointerException("socket");

        this.socket = socket;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
        throws IOException
    {
        // TODO Auto-generated method stub
        super.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read()
        throws IOException
    {
        synchronized (readSyncRoot)
        {
            waitWhileInRead();
            inRead = true;
        }
        try
        {
            do
            {
                int read = read0(b, 0, 1);

                if (read == -1)
                    return read;
                if (read == 1)
                    return b[0];
            }
            while (true);
        }
        finally
        {
            synchronized (readSyncRoot)
            {
                inRead = false;
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int read(byte[] b, int off, int len)
        throws IOException
    {
        int read;

        // The javadoc on InputStream.read(byte[], int, int) says that, if len
        // is zero, no bytes are read and zero is returned.
        if (len == 0)
        {
            read = 0;
        }
        else
        {
            synchronized (readSyncRoot)
            {
                waitWhileInRead();
                inRead = true;
            }
            try
            {
                read = read0(b, off, len);
            }
            finally
            {
                synchronized (readSyncRoot)
                {
                    inRead = false;
                }
            }
        }
        return read;
    }

    protected int read0(byte[] b, int off, int len)
        throws IOException
    {
        int read;

        do
        {
            if (packetLength > 0)
            {
                // Data has already been received from the network.
                read = Math.min(packetLength, len);
                System.arraycopy(packetData, packetOffset, b, off, read);
                packetLength -= read;
                packetOffset += read;
                break;
            }

            // Receive from the network.

            // Make sure that the receive buffer of this InputStream satisfies
            // the requirements with respect to size of the socket.
            int receiveBufferSize = socket.getReceiveBufferSize();

            if (receiveBufferSize < 1)
                receiveBufferSize = DEFAULT_RECEIVE_BUFFER_SIZE;
            if ((packetData == null) || (packetData.length < receiveBufferSize))
                packetData = new byte[receiveBufferSize];
            if (packet == null)
                packet = new DatagramPacket(packetData, 0, packetData.length);
            else
                packet.setData(packetData, 0, packetData.length);
            packetLength = 0;
            packetOffset = 0;

            socket.receive(packet);

            packetData = packet.getData();
            packetLength = packet.getLength();
            packetOffset = packet.getOffset();
        }
        while (true);

        return read;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long skip(long n)
        throws IOException
    {
        // Optimizing the implementation of InputStream.skip(long) sounds like a
        // nice idea in general. However, we do not expect the method to be used
        // a lot. Consequently, we would rather go for simplicity.
        return super.skip(n);
    }

    /**
     * Waits on {@link #readSyncRoot} while {@link #inRead} equals
     * <tt>true</tt>.
     */
    private void waitWhileInRead()
    {
        boolean interrupted = false;

        synchronized (readSyncRoot)
        {
            while (inRead)
            {
                try
                {
                    readSyncRoot.wait();
                }
                catch (InterruptedException ex)
                {
                    interrupted = true;
                }
            }
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
