/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.net.*;

import org.ice4j.*;
import org.ice4j.socket.*;

/**
 * <tt>HostCandidate</tt>s are obtained by binding to a specific port from an
 * IP address on the host that is running us. This includes IP addresses on
 * physical interfaces and logical ones, such as ones obtained through
 * Virtual Private Networks (VPNs), Mobile IPv6, Realm Specific IP (RSIP) etc.
 * <p>
 * At this point this class only supports UDP candidates. Implementation of
 * support for other transport protocols should mean that this class should
 * become abstract and some transport specific components like to socket for
 * example should be brought down the inheritance chain.
 * </p>
 *
 * @author Emil Ivov
 */
public class HostCandidate extends LocalCandidate
{

    /**
     * If this is a local candidate the field contains the socket that is
     * actually associated with the candidate.
     */
    private final IceSocketWrapper socket;

    /**
     * Creates a HostCandidate for the specified transport address.
     *
     * @param socket the {@link DatagramSocket} that communication associated
     * with this <tt>Candidate</tt> will be going through.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     */
    public HostCandidate(IceSocketWrapper socket,
                         Component        parentComponent)
    {
        this(socket,
             parentComponent,
             Transport.UDP);
    }

    /**
     * Creates a HostCandidate for the specified transport address.
     *
     * @param transportAddress the transport address for the new
     * <tt>HostCandidate</tt>.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     */
    HostCandidate(TransportAddress transportAddress,
                  Component parentComponent)
    {
        super(transportAddress,
              parentComponent,
              CandidateType.HOST_CANDIDATE,
              CandidateExtendedType.HOST_CANDIDATE,
              null);

        this.socket = null;
        setBase(this);
    }

    /**
     * Creates a HostCandidate for the specified transport address.
     *
     * @param socket the {@link DatagramSocket} that communication associated
     * with this <tt>Candidate</tt> will be going through.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     * @param transport transport protocol used
     */
    public HostCandidate(IceSocketWrapper socket,
                         Component        parentComponent,
                         Transport        transport)
    {
        super(new TransportAddress(socket.getLocalAddress(),
                        socket.getLocalPort(), transport),
              parentComponent,
              CandidateType.HOST_CANDIDATE,
              CandidateExtendedType.HOST_CANDIDATE,
              null);

        this.socket = socket;
        setBase(this);
    }

    /**
     * Creates a new <tt>StunDatagramPacketFilter</tt> which is to capture STUN
     * messages and make them available to the <tt>DatagramSocket</tt> returned
     * by {@link #getStunSocket(TransportAddress)}.
     *
     * @param serverAddress the address of the source we'd like to receive
     * packets from or <tt>null</tt> if we'd like to intercept all STUN packets
     * @return the <tt>StunDatagramPacketFilter</tt> which is to capture STUN
     * messages and make them available to the <tt>DatagramSocket</tt> returned
     * by {@link #getStunSocket(TransportAddress)}
     * @see LocalCandidate#createStunDatagramPacketFilter(TransportAddress)
     */
    @Override
    protected StunDatagramPacketFilter createStunDatagramPacketFilter(
            TransportAddress serverAddress)
    {
        /*
         * Since we support TURN as well, we have to be able to receive TURN
         * messages as well.
         */
        return new TurnDatagramPacketFilter(serverAddress);
    }

    /**
     * Gets the <tt>DatagramSocket</tt> associated with this <tt>Candidate</tt>.
     *
     * @return the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>
     * @see LocalCandidate#getIceSocketWrapper()
     */
    public IceSocketWrapper getIceSocketWrapper()
    {
        return socket;
    }
}
