/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.lang.reflect.*;
import java.net.*;

import org.ice4j.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;

/**
 * Represents a <tt>Candidate</tt> obtained by sending a Google TURN Allocate
 * request from a <tt>HostCandidate</tt> to a TURN server.  The Google relayed
 * candidate is resident on the TURN server, and the TURN server relays packets
 * back towards the agent.
 *
 * @author Lubomir Marinov
 * @author Sebastien Vincent
 */
public class GoogleRelayedCandidate
    extends LocalCandidate
{
    /**
     * The <tt>RelayedCandidateDatagramSocket</tt> of this
     * <tt>GoogleRelayedCandidate</tt>.
     */
    private GoogleRelayedCandidateDatagramSocket relayedCandidateDatagramSocket;

    /**
     * The <tt>RelayedCandidateSocket</tt> of this
     * <tt>GoogleRelayedCandidate</tt>.
     */
    private GoogleRelayedCandidateSocket relayedCandidateSocket = null;

    /**
     * The application-purposed <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>.
     */
    private IceSocketWrapper socket;

    /**
     * The <tt>GoogleTurnCandidateHarvest</tt> which has harvested this
     * <tt>GoogleRelayedCandidate</tt>.
     */
    private final GoogleTurnCandidateHarvest turnCandidateHarvest;

    /**
     * Username.
     */
    private final String username;

    /**
     * Password.
     */
    private final String password;

    /**
     * Initializes a new <tt>RelayedCandidate</tt> which is to represent a
     * specific <tt>TransportAddress</tt> harvested through a specific
     * <tt>HostCandidate</tt> and a TURN server with a specific
     * <tt>TransportAddress</tt>.
     *
     * @param transportAddress the <tt>TransportAddress</tt> to be represented
     * by the new instance
     * @param turnCandidateHarvest the <tt>TurnCandidateHarvest</tt> which has
     * harvested the new instance
     * @param mappedAddress the mapped <tt>TransportAddress</tt> reported by the
     * TURN server with the delivery of the replayed <tt>transportAddress</tt>
     * to be represented by the new instance
     * @param username username (Send request to the Google relay server need
     * it)
     * @param password password (used with XMPP gingle candidates).
     * it)
     */
    public GoogleRelayedCandidate(
            TransportAddress transportAddress,
            GoogleTurnCandidateHarvest turnCandidateHarvest,
            TransportAddress mappedAddress,
            String username,
            String password)
    {
        super(
            transportAddress,
            turnCandidateHarvest.hostCandidate.getParentComponent(),
            CandidateType.RELAYED_CANDIDATE,
            CandidateExtendedType.GOOGLE_TURN_RELAYED_CANDIDATE,
            turnCandidateHarvest.hostCandidate.getParentComponent()
                .findLocalCandidate(mappedAddress));

        if(transportAddress.getTransport() == Transport.TCP)
        {
            super.setExtendedType(
                    CandidateExtendedType.GOOGLE_TCP_TURN_RELAYED_CANDIDATE);
        }

        this.turnCandidateHarvest = turnCandidateHarvest;
        this.username = username;
        this.password = password;

        // RFC 5245: The base of a relayed candidate is that candidate itself.
        setBase(this);
        setRelayServerAddress(turnCandidateHarvest.harvester.stunServer);
        setMappedAddress(mappedAddress);
    }

    /**
     * Gets the <tt>RelayedCandidateDatagramSocket</tt> of this
     * <tt>RelayedCandidate</tt>.
     * <p>
     * <b>Note</b>: The method is part of the internal API of
     * <tt>RelayedCandidate</tt> and <tt>TurnCandidateHarvest</tt> and is not
     * intended for public use.
     * </p>
     *
     * @return the <tt>RelayedCandidateDatagramSocket</tt> of this
     * <tt>RelayedCandidate</tt>
     */
    private synchronized GoogleRelayedCandidateDatagramSocket
        getRelayedCandidateDatagramSocket()
    {
        if (relayedCandidateDatagramSocket == null)
        {
            try
            {
                relayedCandidateDatagramSocket
                    = new GoogleRelayedCandidateDatagramSocket(
                            this,
                            turnCandidateHarvest,
                            username);
            }
            catch (SocketException sex)
            {
                throw new UndeclaredThrowableException(sex);
            }
        }
        return relayedCandidateDatagramSocket;
    }

    /**
     * Gets the <tt>RelayedCandidateDatagramSocket</tt> of this
     * <tt>RelayedCandidate</tt>.
     * <p>
     * <b>Note</b>: The method is part of the internal API of
     * <tt>RelayedCandidate</tt> and <tt>TurnCandidateHarvest</tt> and is not
     * intended for public use.
     * </p>
     *
     * @return the <tt>RelayedCandidateDatagramSocket</tt> of this
     * <tt>RelayedCandidate</tt>
     */
    private synchronized GoogleRelayedCandidateSocket
        getRelayedCandidateSocket()
    {
        if (relayedCandidateSocket == null)
        {
            try
            {
                relayedCandidateSocket
                    = new GoogleRelayedCandidateSocket(
                        this,
                        turnCandidateHarvest,
                        username);
            }
            catch (SocketException sex)
            {
                throw new UndeclaredThrowableException(sex);
            }
        }
        return relayedCandidateSocket;
    }

    /**
     * Gets the application-purposed <tt>DatagramSocket</tt> associated with
     * this <tt>Candidate</tt>.
     *
     * @return the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>
     * @see LocalCandidate#getIceSocketWrapper()
     */
    public synchronized IceSocketWrapper getIceSocketWrapper()
    {
        if (socket == null)
        {
            try
            {
                if(getTransport() == Transport.UDP)
                {
                    socket
                       = new IceUdpSocketWrapper(new MultiplexingDatagramSocket(
                            getRelayedCandidateDatagramSocket()));
                }
                else if(getTransport() == Transport.TCP)
                {
                    final Socket s = getRelayedCandidateSocket();
                    socket = new IceTcpSocketWrapper(new MultiplexingSocket(s));
                }
            }
            catch (Exception sex)
            {
                throw new UndeclaredThrowableException(sex);
            }
        }
        return socket;
    }

    /**
     * Returns the password for this candidate.
     * @return the password for this candidate.
     */
    public String getPassword()
    {
        return this.password;
    }
}
