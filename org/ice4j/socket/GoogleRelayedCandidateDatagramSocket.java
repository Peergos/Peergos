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
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.message.*;

/**
 * Represents an application-purposed (as opposed to an ICE-specific)
 * <tt>DatagramSocket</tt> for a <tt>RelayedCandidate</tt> harvested by a
 * <tt>TurnCandidateHarvest</tt> (and its associated
 * <tt>TurnCandidateHarvester</tt>, of course).
 * <tt>GoogleRelayedCandidateDatagramSocket</tt> is associated with a successful
 * Allocation on a TURN server and implements sends and receives through it
 * using TURN messages to and from that TURN server.
 *
 * @author Lyubomir Marinov
 * @author Sebastien Vincent
 */
public class GoogleRelayedCandidateDatagramSocket
    extends DatagramSocket
{
    /**
     * The <tt>Logger</tt> used by the
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> class and its instances for
     * logging output.
     */
    private static final Logger logger
        = Logger.getLogger(
                GoogleRelayedCandidateDatagramSocket.class.getName());

    /**
     * The indicator which determines whether this instance has started
     * executing or has executed its {@link #close()} method.
     */
    private boolean closed = false;

    /**
     * The <tt>GoogleRelayedCandidate</tt> which uses this instance as the value
     * of its <tt>socket</tt> property.
     */
    private final GoogleRelayedCandidate relayedCandidate;

    /**
     * The <tt>GoogleTurnCandidateHarvest</tt> which has harvested
     * {@link #relayedCandidate}.
     */
    private final GoogleTurnCandidateHarvest turnCandidateHarvest;

    /**
     * The <tt>GoogleTurnCandidateDelegage</tt> which will handle send/receive
     * operations.
     */
    private final GoogleRelayedCandidateDelegate socketDelegate;

    /**
     * Initializes a new <tt>GoogleRelayedCandidateDatagramSocket</tt> instance
     * which is to be the <tt>socket</tt> of a specific
     * <tt>RelayedCandidate</tt> harvested by a specific
     * <tt>TurnCandidateHarvest</tt>.
     *
     * @param relayedCandidate the <tt>RelayedCandidate</tt> which is to use the
     * new instance as the value of its <tt>socket</tt> property
     * @param turnCandidateHarvest the <tt>TurnCandidateHarvest</tt> which has
     * harvested <tt>relayedCandidate</tt>
     * @param username username
     * @throws SocketException if anything goes wrong while initializing the new
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> instance
     */
    public GoogleRelayedCandidateDatagramSocket(
            GoogleRelayedCandidate relayedCandidate,
            GoogleTurnCandidateHarvest turnCandidateHarvest,
            String username)
        throws SocketException
    {
        super(/* bindaddr */ (SocketAddress) null);

        socketDelegate = new GoogleRelayedCandidateDelegate(
            turnCandidateHarvest, username);
        this.relayedCandidate = relayedCandidate;
        this.turnCandidateHarvest = turnCandidateHarvest;

        logger.finest("Create new GoogleRelayedCandidateDatagramSocket");
    }

    /**
     * Closes this datagram socket.
     *
     * @see DatagramSocket#close()
     */
    @Override
    public void close()
    {
        synchronized (this)
        {
            if (this.closed)
                return;
            else
                this.closed = true;
        }

        socketDelegate.close();
        turnCandidateHarvest.close(this);
    }

    /**
     * Gets the local address to which the socket is bound.
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> returns the
     * <tt>address</tt> of its <tt>localSocketAddress</tt>.
     * <p>
     * If there is a security manager, its <tt>checkConnect</tt> method is first
     * called with the host address and <tt>-1</tt> as its arguments to see if
     * the operation is allowed.
     * </p>
     *
     * @return the local address to which the socket is bound, or an
     * <tt>InetAddress</tt> representing any local address if either the socket
     * is not bound, or the security manager <tt>checkConnect</tt> method does
     * not allow the operation
     * @see #getLocalSocketAddress()
     * @see DatagramSocket#getLocalAddress()
     */
    @Override
    public InetAddress getLocalAddress()
    {
        return getLocalSocketAddress().getAddress();
    }

    /**
     * Returns the port number on the local host to which this socket is bound.
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> returns the <tt>port</tt>
     * of its <tt>localSocketAddress</tt>.
     *
     * @return the port number on the local host to which this socket is bound
     * @see #getLocalSocketAddress()
     * @see DatagramSocket#getLocalPort()
     */
    @Override
    public int getLocalPort()
    {
        return getLocalSocketAddress().getPort();
    }

    /**
     * Returns the address of the endpoint this socket is bound to, or
     * <tt>null</tt> if it is not bound yet. Since
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> represents an
     * application-purposed <tt>DatagramSocket</tt> relaying data to and from a
     * TURN server, the <tt>localSocketAddress</tt> is the
     * <tt>transportAddress</tt> of the respective <tt>RelayedCandidate</tt>.
     *
     * @return a <tt>SocketAddress</tt> representing the local endpoint of this
     * socket, or <tt>null</tt> if it is not bound yet
     * @see DatagramSocket#getLocalSocketAddress()
     */
    @Override
    public InetSocketAddress getLocalSocketAddress()
    {
        return getRelayedCandidate().getTransportAddress();
    }

    /**
     * Gets the <tt>RelayedCandidate</tt> which uses this instance as the value
     * of its <tt>socket</tt> property.
     *
     * @return the <tt>RelayedCandidate</tt> which uses this instance as the
     * value of its <tt>socket</tt> property
     */
    public final GoogleRelayedCandidate getRelayedCandidate()
    {
        return relayedCandidate;
    }

    /**
     * Notifies this <tt>GoogleRelayedCandidateDatagramSocket</tt> that a
     * specific <tt>Request</tt> it has sent has received a STUN success
     * <tt>Response</tt>.
     *
     * @param response the <tt>Response</tt> which responds to <tt>request</tt>
     * @param request the <tt>Request</tt> sent by this instance to which
     * <tt>response</tt> responds
     */
    public void processSuccess(Response response, Request request)
    {
        socketDelegate.processSuccess(response, request);
    }

    /**
     * Dispatch the specified response.
     *
     * @param response the response to dispatch.
     */
    public void processResponse(StunResponseEvent response)
    {
        socketDelegate.processResponse(response);
    }

    /**
     * Receives a datagram packet from this socket. When this method returns,
     * the <tt>DatagramPacket</tt>'s buffer is filled with the data received.
     * The datagram packet also contains the sender's IP address, and the port
     * number on the sender's machine.
     *
     * @param p the <tt>DatagramPacket</tt> into which to place the incoming
     * data
     * @throws IOException if an I/O error occurs
     * @see DatagramSocket#receive(DatagramPacket)
     */
    @Override
    public void receive(DatagramPacket p)
        throws IOException
    {
        socketDelegate.receive(p);
    }

    /**
     * Sends a datagram packet from this socket. The <tt>DatagramPacket</tt>
     * includes information indicating the data to be sent, its length, the IP
     * address of the remote host, and the port number on the remote host.
     *
     * @param p the <tt>DatagramPacket</tt> to be sent
     * @throws IOException if an I/O error occurs
     * @see DatagramSocket#send(DatagramPacket)
     */
    @Override
    public void send(DatagramPacket p)
        throws IOException
    {
        socketDelegate.send(p);
    }
}