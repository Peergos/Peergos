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
 * Represents a <tt>DatagramSocket</tt> which fixes the following problem: when
 * {@link DatagramSocket#close()} is called while another thread is blocked in
 * its {@link DatagramSocket#receive(DatagramPacket)}, calling
 * {@link DatagramSocket#bind(SocketAddress)} throws an "Already bound"
 * exception until (the native counterpart of) the <tt>receive</tt> method
 * returns.
 *
 * @author Lubomir Marinov
 */
public class SafeCloseDatagramSocket
    extends DelegatingDatagramSocket
{

    /**
     * The number of {@link #receive(DatagramPacket)} calls that have to return
     * before {@link #close()} returns.
     */
    private int inReceive = 0;

    /**
     * The <tt>Object</tt> which synchronizes the access to {@link #inReceive}
     * and implements the related inter-thread communication.
     */
    private final Object inReceiveSyncRoot = new Object();

    /**
     * Initializes a new <tt>SafeCloseDatagramSocket</tt> instance and binds it
     * to any available port on the local host machine.  The socket will be
     * bound to the wildcard address, an IP address chosen by the kernel.
     *
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket()
     */
    public SafeCloseDatagramSocket()
        throws SocketException
    {
        super();
    }

    /**
     * Initializes a new <tt>SafeCloseDatagramSocket</tt> instance which to
     * implement the <tt>DatagramSocket</tt> functionality by delegating to a
     * specific <tt>DatagramSocket</tt>.
     *
     * @param delegate the <tt>DatagramSocket</tt> to which the new instance is
     * to delegate
     * @throws SocketException if anything goes wrong while initializing the new
     * <tt>SafeCloseDatagramSocket</tt> instance
     */
    public SafeCloseDatagramSocket(DatagramSocket delegate)
        throws SocketException
    {
        super(delegate);
    }

    /**
     * Initializes a new <tt>SafeCloseDatagramSocket</tt> instance  and binds it
     * to the specified port on the local host machine.  The socket will be
     * bound to the wildcard address, an IP address chosen by the kernel.
     *
     * @param port the port to bind the new socket to
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket(int)
     */
    public SafeCloseDatagramSocket(int port)
        throws SocketException
    {
        super(port);
    }

    /**
     * Initializes a new <tt>SafeCloseDatagramSocket</tt> instance bound to the
     * specified local address.  The local port must be between 0 and 65535
     * inclusive. If the IP address is 0.0.0.0, the socket will be bound to the
     * wildcard address, an IP address chosen by the kernel.
     *
     * @param port the local port to bind the new socket to
     * @param laddr the local address to bind the new socket to
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket(int, InetAddress)
     */
    public SafeCloseDatagramSocket(int port, InetAddress laddr)
        throws SocketException
    {
        super(port, laddr);
    }

    /**
     * Creates a datagram socket, bound to the specified local socket address.
     * <p>
     * If, if the address is <tt>null</tt>, creates an unbound socket.
     * </p>
     *
     * @param bindaddr local socket address to bind, or <tt>null</tt> for an
     * unbound socket
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     */
    public SafeCloseDatagramSocket(SocketAddress bindaddr)
        throws SocketException
    {
        super(bindaddr);
    }

    /**
     * Closes this datagram socket.
     * <p>
     * Any thread currently blocked in {@link #receive} upon this socket will
     * throw a {@link SocketException} and this datagram socket will wait for it
     * to return.
     * </p>
     *
     * @see DatagramSocket#close()
     */
    @Override
    public void close()
    {
        super.close();

        if(inReceiveSyncRoot == null)
            return;

        synchronized (inReceiveSyncRoot)
        {
            boolean interrupted = false;

            while (inReceive > 0)
                try
                {
                    inReceiveSyncRoot.wait();
                }
                catch (InterruptedException iex)
                {
                    interrupted = true;
                }
            if (interrupted)
                Thread.currentThread().interrupt();
        }
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
     * @throws  IOException if an I/O error occurs
     * @see DatagramSocket#receive(DatagramPacket)
     */
    @Override
    public void receive(DatagramPacket p)
        throws IOException
    {
        synchronized (inReceiveSyncRoot)
        {
            inReceive++;
        }
        try
        {
            super.receive(p);
        }
        finally
        {
            synchronized (inReceiveSyncRoot)
            {
                inReceive--;
                inReceiveSyncRoot.notifyAll();
            }
        }
    }
}
