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
 * Abstract socket wrapper that define a socket that could be UDP, TCP...
 *
 * @author Sebastien Vincent
 */
public abstract class IceSocketWrapper
{
    /**
     * Sends a <tt>DatagramPacket</tt> from this socket
     * It is a utility method to provide a common way to send for both
     * UDP and TCP socket. If the underlying socket is a TCP one, it is still
     * possible to get the <tt>OutputStream</tt> and do stuff with it.
     *
     *
     * @param p <tt>DatagramPacket</tt> to send
     * @throws IOException if something goes wrong
     */
    public abstract void send(DatagramPacket p)
        throws IOException;

    /**
     * Receives a <tt>DatagramPacket</tt> from this socket.
     * It is a utility method to provide a common way to receive for both
     * UDP and TCP socket. If the underlying socket is a TCP one, it is still
     * possible to get the <tt>InputStream</tt> and do stuff with it.
     *
     * @param p <tt>DatagramPacket</tt>
     * @throws IOException if something goes wrong
     */
    public abstract void receive(DatagramPacket p)
        throws IOException;

    /**
     * Closes this socket.
     */
    public abstract void close();

    /**
     * Get local address.
     *
     * @return local address
     */
    public abstract InetAddress getLocalAddress();

    /**
     * Get local port.
     *
     * @return local port
     */
    public abstract int getLocalPort();

    /**
     * Get socket address.
     *
     * @return socket address
     */
    public abstract SocketAddress getLocalSocketAddress();

    /**
     * Returns Socket object if the delegate socket is a TCP one, null
     * otherwise.
     *
     * @return Socket object if the delegate socket is a TCP one, null
     * otherwise.
     */
    public abstract Socket getTCPSocket();

    /**
     * Returns DatagramSocket object if the delegate socket is a UDP one, null
     * otherwise.
     *
     * @return DatagramSocket object if the delegate socket is a UDP one, null
     * otherwise.
     */
    public abstract DatagramSocket getUDPSocket();
}
