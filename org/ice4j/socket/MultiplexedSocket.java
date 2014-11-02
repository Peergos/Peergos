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
import java.util.*;
import java.util.logging.*;

/**
 * Represents a <tt>Socket</tt> which receives <tt>DatagramPacket</tt>s
 * selected by a <tt>DatagramPacketFilter</tt> from a
 * <tt>MultiplexingSocket</tt>. The associated <tt>MultiplexingSocket</tt> is
 * the actual <tt>Socket</tt> which reads the <tt>DatagramPacket</tt>s from the
 * network. The <tt>DatagramPacket</tt>s received through the
 * <tt>MultiplexedSocket</tt> will not be received through the
 * associated <tt>MultiplexingSocket</tt>.
 *
 * @author Sebastien Vincent
 */
public class MultiplexedSocket
    extends DelegatingSocket
{
    /**
     * The <tt>Logger</tt> used by the <tt>MultiplexedSocket</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(MultiplexedSocket.class.getName());

    /**
     * The <tt>DatagramPacketFilter</tt> which determines which
     * <tt>DatagramPacket</tt>s read from the network by {@link #multiplexing}
     * are to be received through this instance.
     */
    private final DatagramPacketFilter filter;

    /**
     * The custom <tt>InputStream</tt> for this <tt>MultiplexedSocket</tt>.
     */
    private final InputStream inputStream = new InputStreamImpl();

    /**
     * The <tt>MultiplexingSocket</tt> which does the actual reading from the
     * network and which forwards <tt>DatagramPacket</tt>s accepted by
     * {@link #filter} for receipt to this instance.
     */
    private final MultiplexingSocket multiplexing;

    /**
     * The list of <tt>DatagramPacket</tt>s to be received through this
     * <tt>Socket</tt> i.e. accepted by {@link #filter}.
     */
    final List<DatagramPacket> received
        = new SocketReceiveBuffer()
        {
            private static final long serialVersionUID
                = 678744096057601141L;

            @Override
            public int getReceiveBufferSize()
                throws SocketException
            {
                return MultiplexedSocket.this.getReceiveBufferSize();
            }
        };

    /**
     * Initializes a new <tt>MultiplexedSocket</tt> which is unbound and filters
     * <tt>DatagramPacket</tt>s away from a specific <tt>MultiplexingSocket</tt>
     * using a specific <tt>DatagramPacketFilter</tt>.
     *
     * @param multiplexing the <tt>MultiplexingSocket</tt> which does the actual
     * reading from the network and which forwards <tt>DatagramPacket</tt>s
     * accepted by the specified <tt>filter</tt> to the new instance
     * @param filter the <tt>DatagramPacketFilter</tt> which determines which
     * <tt>DatagramPacket</tt>s read from the network by the specified
     * <tt>multiplexing</tt> are to be received through the new instance
     * @throws SocketException if the socket could not be opened
     */
    MultiplexedSocket(
            MultiplexingSocket multiplexing,
            DatagramPacketFilter filter)
        throws SocketException
    {
        /*
         * Even if MultiplexingSocket allows MultiplexedSocket to perform bind,
         * binding in the super will not execute correctly this early in the
         * construction because the multiplexing field is not set yet. That is
         * why MultiplexedSocket does not currently support bind at construction
         * time.
         */
        super(multiplexing);

        if (multiplexing == null)
            throw new NullPointerException("multiplexing");

        this.multiplexing = multiplexing;
        this.filter = filter;
    }

    /**
     * Closes this datagram socket.
     * <p>
     * Any thread currently blocked in {@link #receive(DatagramPacket)} upon
     * this socket will throw a {@link SocketException}.
     * </p>
     *
     * @see Socket#close()
     */
    @Override
    public void close()
    {
        multiplexing.close(this);
    }

    /**
     * Gets the <tt>DatagramPacketFilter</tt> which determines which
     * <tt>DatagramPacket</tt>s read from the network are to be received through
     * this <tt>Socket</tt>.
     *
     * @return the <tt>DatagramPacketFilter</tt> which determines which
     * <tt>DatagramPacket</tt>s read from the network are to be received through
     * this <tt>Socket</tt>
     */
    public DatagramPacketFilter getFilter()
    {
        return filter;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream getInputStream()
    {
        return inputStream;
    }

    /**
     * Receives a datagram packet from this socket. When this method returns,
     * the <tt>DatagramPacket</tt>'s buffer is filled with the data received.
     * The datagram packet also contains the sender's IP address, and the port
     * number on the sender's machine.
     * <p>
     * This method blocks until a datagram is received. The <tt>length</tt>
     * field of the datagram packet object contains the length of the received
     * message. If the message is longer than the packet's length, the message
     * is truncated.
     * </p>
     *
     * @param p the <tt>DatagramPacket</tt> into which to place the incoming
     * data
     * @throws IOException if an I/O error occurs
     * @see MultiplexingSocket#receive(DatagramPacket)
     */
    @Override
    public void receive(DatagramPacket p)
        throws IOException
    {
        multiplexing.receive(this, p);
    }

    /**
     * Implements an <tt>InputStream</tt> for this <tt>MultiplexedSocket</tt>,
     * reading data using {@link #receive(java.net.DatagramPacket)}.
     */
    private class InputStreamImpl
        extends InputStream
    {
        /**
         * A buffer to receive data into.
         */
        private final byte[] buf = new byte[1500];

        /**
         * A <tt>DatagramPacket</tt> instance to receive data into.
         */
        private final DatagramPacket packet = new DatagramPacket(buf, 1500);

        /**
         * Initializes a new <tt>TCPInputStream</tt>.
         */
        public InputStreamImpl()
        {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int available()
        {
            return 0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public boolean markSupported()
        {
            return false;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int read()
            throws IOException
        {
            // We don't support reading a single byte
            return 0;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int read(byte[] b)
            throws IOException
        {
            return read(b, 0, b.length);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int read(byte[] b, int off, int len)
            throws IOException
        {
            if (off == 0) // optimization: avoid copy to b
            {
                packet.setData(b);
                receive(packet);

                int lengthRead = packet.getLength();

                if (packet.getData() == b && lengthRead <= len)
                {
                    return lengthRead;
                }
                else
                {
                    logger.warning(
                            "Failed to read directly into the provided buffer,"
                                + " len=" + len + " lengthRead=" + lengthRead
                                + " (packet.getData() == b)="
                                + (packet.getData() == b));
                }
            }

            // either there's an offset to take into account, or receiving
            // directly in 'b' failed.

            packet.setData(buf);
            receive(packet);

            int packetLen = packet.getLength();
            int lengthRead = Math.min(len, packetLen);

            System.arraycopy(
                    packet.getData(), packet.getOffset(),
                    b, off,
                    lengthRead);

            return lengthRead;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void reset()
            throws IOException
        {
            if(!markSupported())
            {
                throw new IOException(
                        "InputStreamImpl does not support reset()");
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long skip(long n)
            throws IOException
        {
            throw new IOException("InputStreamImpl does not support skip.");
        }
    }
}
