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
 * Represents a <tt>Socket</tt> which allows filtering <tt>DatagramPacket</tt>s
 * it reads from the network using <tt>DatagramPacketFilter</tt>s so that the
 * <tt>DatagramPacket</tt>s do not get received through it but through
 * associated <tt>MultiplexedSocket</tt>s.
 *
 * @author Sebastien Vincent
 * @author Lyubomir Marinov
 */
public class MultiplexingSocket
    extends DelegatingSocket
{
    /**
     * The <tt>Logger</tt> used by the <tt>MultiplexingSocket</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(MultiplexingSocket.class.getName());

    /**
     * The constant which represents an empty array with
     * <tt>MultiplexedSocket</tt> element type. Explicitly defined in order to
     * reduce allocations.
     */
    private static final MultiplexedSocket[] NO_SOCKETS
        = new MultiplexedSocket[0];

    /**
     * Custom <tt>InputStream</tt> for this <tt>Socket</tt>.
     */
    private final InputStream inputStream = new TCPInputStream(this);

    /**
     * The indicator which determines whether this <tt>Socket</tt> is currently
     * reading from the network  using {@link #receive(DatagramPacket)}. When
     * <tt>true</tt>, subsequent requests to read from the network will be
     * blocked until the current read is finished.
     */
    private boolean inReceive = false;

    /**
     * Custom <tt>OutputStream</tt> for this <tt>Socket</tt>.
     */
    private TCPOutputStream outputStream = null;

    /**
     * The list of <tt>DatagramPacket</tt>s to be received through this
     * <tt>Socket</tt> i.e. not accepted by the <tt>DatagramFilter</tt>s of
     * {@link #sockets} at the time of the reading from the network.
     */
    private final List<DatagramPacket> received
        = new SocketReceiveBuffer()
        {
            private static final long serialVersionUID
                = 4097024214973676873L;

            @Override
            public int getReceiveBufferSize()
                throws SocketException
            {
                return MultiplexingSocket.this.getReceiveBufferSize();
            }
        };

    /**
     * The <tt>Object</tt> which synchronizes the access to {@link #inReceive}.
     */
    private final Object receiveSyncRoot = new Object();

    /**
     * The <tt>MultiplexedSocket</tt>s filtering <tt>DatagramPacket</tt>s away
     * from this <tt>Socket</tt>.
     */
    private MultiplexedSocket[] sockets = NO_SOCKETS;

    /**
     * The <tt>Object</tt> which synchronizes the access to the {@link #sockets}
     * field of this instance.
     */
    private final Object socketsSyncRoot = new Object();

    /**
     * Buffer variable for storing the SO_TIMEOUT value set by the last
     * <tt>setSoTimeout()</tt> call. Although not strictly needed, getting the
     * locally stored value as opposed to retrieving it from a parent
     * <tt>getSoTimeout()</tt> call seems to significantly improve efficiency,
     * at least on some platforms.
     */
    private int soTimeout = 0;

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket()
     */
    public MultiplexingSocket()
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(InetAddress, int)
     */
    public MultiplexingSocket(InetAddress address, int port)
        throws IOException
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(InetAddress, int, InetAddress, int)
     */
    public MultiplexingSocket(
            InetAddress address, int port,
            InetAddress localAddr, int localPort)
        throws IOException
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(Proxy)
     */
    public MultiplexingSocket(Proxy proxy)
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @param socket delegate socket
     */
    public MultiplexingSocket(Socket socket)
    {
        super(socket);

        try
        {
            setTcpNoDelay(true);
        }
        catch (SocketException ex)
        {
            logger.info("Cannot SO_TCPNODELAY");
        }
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(SocketImpl)
     */
    protected MultiplexingSocket(SocketImpl impl)
        throws SocketException
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(String, int)
     */
    public MultiplexingSocket(String host, int port)
        throws UnknownHostException, IOException
    {
        this((Socket) null);
    }

    /**
     * Initializes a new <tt>MultiplexingSocket</tt> instance.
     *
     * @see Socket#Socket(String, int, InetAddress, int)
     */
    public MultiplexingSocket(
            String host, int port,
            InetAddress localAddr, int localPort)
    {
        this((Socket) null);
    }

    /**
     * Closes a specific <tt>MultiplexedSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>Socket</tt>.
     *
     * @param multiplexed the <tt>MultiplexedSocket</tt> to close
     */
    void close(MultiplexedSocket multiplexed)
    {
        synchronized (socketsSyncRoot)
        {
            int socketCount = sockets.length;

            for (int i = 0; i < socketCount; i++)
            {
                if (sockets[i].equals(multiplexed))
                {
                    if (socketCount == 1)
                    {
                        sockets = NO_SOCKETS;
                    }
                    else
                    {
                        MultiplexedSocket[] newSockets
                            = new MultiplexedSocket[socketCount - 1];

                        System.arraycopy(sockets, 0, newSockets, 0, i);
                        System.arraycopy(
                                sockets, i + 1,
                                newSockets, i,
                                newSockets.length - i);
                        sockets = newSockets;
                    }
                    break;
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InputStream getInputStream()
        throws IOException
    {
        return inputStream;
    }

    /**
     * Get original <tt>InputStream</tt>.
     *
     * @return original <tt>InputStream</tt>
     * @throws IOException if something goes wrong
     */
    public InputStream getOriginalInputStream()
        throws IOException
    {
        return super.getInputStream();
    }

    /**
     * Get original <tt>OutputStream</tt>.
     *
     * @return original <tt>OutputStream</tt>
     * @throws IOException if something goes wrong
     */
    public OutputStream getOriginalOutputStream()
        throws IOException
    {
        return super.getOutputStream();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public OutputStream getOutputStream()
        throws IOException
    {
        if (outputStream == null)
            outputStream = new TCPOutputStream(super.getOutputStream());
        return outputStream;
    }

    /**
     * Gets a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using a
     * specific <tt>DatagramPacketFilter</tt>. If such a
     * <tt>MultiplexedDatagramSocket</tt> does not exist in this instance, it is
     * created.
     *
     * @param filter the <tt>DatagramPacketFilter</tt> to get a
     * <tt>MultiplexedDatagramSocket</tt> for
     * @return a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using the
     * specified <tt>filter</tt>
     * @throws SocketException if creating the
     * <tt>MultiplexedDatagramSocket</tt> for the specified <tt>filter</tt>
     * fails
     */
    public MultiplexedSocket getSocket(DatagramPacketFilter filter)
        throws SocketException
    {
        if (filter == null)
            throw new NullPointerException("filter");

        synchronized (socketsSyncRoot)
        {
            // If a socket for the specified filter exists already, do not
            // create a new one and return the existing.
            for (MultiplexedSocket socket : sockets)
            {
                if (filter.equals(socket.getFilter()))
                    return socket;
            }

            // Create a new socket for the specified filter.
            MultiplexedSocket socket = new MultiplexedSocket(this, filter);

            // Remember the new socket.
            sockets = MultiplexingDatagramSocket.add(sockets, socket);

            return socket;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getSoTimeout()
    {
    	return soTimeout;
    }

    /**
     * Receives a datagram packet from this socket. The <tt>DatagramPacket</tt>s
     * returned by this method do not match any of the
     * <tt>DatagramPacketFilter</tt>s of the <tt>MultiplexedDatagramSocket</tt>s
     * associated with this instance at the time of their receipt. When this
     * method returns, the <tt>DatagramPacket</tt>'s buffer is filled with the
     * data received. The datagram packet also contains the sender's IP address,
     * and the port number on the sender's machine.
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
     * @see DelegatingSocket#receive(DatagramPacket)
     */
    @Override
    public void receive(DatagramPacket p)
        throws IOException
    {
        try
        {
            setOriginalInputStream(super.getInputStream());
        }
        catch(Exception e)
        {
        }
        receive(received, p, soTimeout);
    }

    /**
     * Receives a <tt>DatagramPacket</tt> from a specific list of
     * <tt>DatagramPacket</tt>s if it is not empty or from the network if the
     * specified list is empty. When this method returns, the
     * <tt>DatagramPacket</tt>'s buffer is filled with the data received. The
     * datagram packet also contains the sender's IP address, and the port
     * number on the sender's machine.
     *
     * @param received the list of previously received <tt>DatagramPacket</tt>
     * from which the first is to be removed and returned if available
     * @param p the <tt>DatagramPacket</tt> into which to place the incoming
     * data
     * @param timeout the maximum time in milliseconds to wait for a
     * packet.  A timeout of zero is interpreted as an infinite
     * timeout
     * @throws SocketTimeoutException if the timeout has expired
     * @throws IOException if an I/O error occurs
     */
    private void receive(List<DatagramPacket> received,
                         DatagramPacket p,
                         int timeout)
        throws IOException
    {
        long startTime = System.currentTimeMillis();
        DatagramPacket r = null;

        do
        {
            long now = System.currentTimeMillis();

            // If there is a packet which has been received from the network and
            // is to merely be received from the list of received
            // DatagramPackets, then let it be received and do not throw a
            // SocketTimeoutException.
            synchronized (received)
            {
                if (!received.isEmpty())
                {
                    r = received.remove(0);
                    if (r != null)
                        break;
                }
            }

            // Throw a SocketTimeoutException if the timeout is over/up.
            long remainingTimeout;

            if (timeout > 0)
            {
                remainingTimeout = timeout - (now - startTime);
                if (remainingTimeout <= 0L)
                {
                    throw new SocketTimeoutException(
                            Long.toString(remainingTimeout));
                }
            }
            else
            {
                remainingTimeout = 1000L;
            }

            // Determine whether the caller will receive from the network or
            // will wait for a previous caller to receive from the network.
            boolean wait;

            synchronized (receiveSyncRoot)
            {
                if (inReceive)
                {
                    wait = true;
                }
                else
                {
                    wait = false;
                    inReceive = true;
                }
            }
            try
            {
                if (wait)
                {
                    // The caller will wait for a previous caller to receive
                    // from the network.
                    synchronized (received)
                    {
                        if (received.isEmpty())
                        {
                            try
                            {
                                received.wait(remainingTimeout);
                            }
                            catch (InterruptedException ie)
                            {
                            }
                        }
                        else
                        {
                            received.notifyAll();
                        }
                    }
                    continue;
                }

                // The caller will receive from the network.
                DatagramPacket c
                    = MultiplexingDatagramSocket.clone(
                            p,
                            /* arraycopy */ false);

                super.receive(c);

                // The caller received from the network. Copy/add the packet to
                // the receive list of the sockets which accept it.
                synchronized (socketsSyncRoot)
                {
                    boolean accepted = false;

                    for (MultiplexedSocket socket : sockets)
                    {
                        if (socket.getFilter().accept(c))
                        {
                            synchronized (socket.received)
                            {
                                socket.received.add(
                                        accepted
                                            ? MultiplexingDatagramSocket.clone(
                                                    c,
                                                    /* arraycopy */ true)
                                            : c);
                                socket.received.notifyAll();
                            }
                            accepted = true;

                            // Emil: Don't break because we want all filtering
                            // sockets to get the received packet.
                        }
                    }
                    if (!accepted)
                    {
                        synchronized (this.received)
                        {
                            this.received.add(c);
                            this.received.notifyAll();
                        }
                    }
                }
            }
            finally
            {
                synchronized (receiveSyncRoot)
                {
                    if (!wait)
                        inReceive = false;
                }
            }
        }
        while (true);

        MultiplexingDatagramSocket.copy(r, p);
    }

    /**
     * Receives a <tt>DatagramPacket</tt> from this <tt>Socket</tt> upon
     * request from a specific <tt>MultiplexedSocket</tt>.
     *
     * @param multiplexed the <tt>MultiplexedSocket</tt> which requests
     * the receipt of a <tt>DatagramPacket</tt> from the network
     * @param p the <tt>DatagramPacket</tt> to receive the data from the network
     * @throws IOException if an I/O error occurs
     */
    void receive(MultiplexedSocket multiplexed, DatagramPacket p)
        throws IOException
    {
        try
        {
            setOriginalInputStream(super.getInputStream());
        }
        catch(Exception e)
        {
        }
        receive(multiplexed.received, p, multiplexed.getSoTimeout());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setSoTimeout(int timeout)
    	throws SocketException
    {
    	super.setSoTimeout(timeout);

    	soTimeout = timeout;
    }
}
