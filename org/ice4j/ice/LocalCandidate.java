/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.io.*;
import java.net.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * <tt>LocalCandidate</tt>s are obtained by an agent for every stream component
 * and are then included in outgoing offers or answers.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public abstract class LocalCandidate
    extends Candidate<LocalCandidate>
{
    /**
     * The type of method used to discover this candidate ("host", "upnp", "stun
     * peer reflexive", "stun server reflexive", "turn relayed", "google turn
     * relayed", "google tcp turn relayed" or "jingle node").
     */
    private CandidateExtendedType extendedType = null;

    /**
     * Ufrag for the local candidate.
     */
    private String ufrag = null;

    /**
     * Whether this <tt>LocalCandidate</tt> uses SSL.
     */
    private boolean isSSL = false;

    /**
     * The <tt>Logger</tt> used by the <tt>LocalCandidate</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(HostCandidate.class.getName());

    /**
     * Creates a <tt>LocalCandidate</tt> instance for the specified transport
     * address and properties.
     *
     * @param transportAddress  the transport address that this candidate is
     * encapsulating.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     * @param type the <tt>CandidateType</tt> for this <tt>Candidate</tt>.
     * @param extendedType The type of method used to discover this candidate
     * ("host", "upnp", "stun peer reflexive", "stun server reflexive", "turn
     * relayed", "google turn relayed", "google tcp turn relayed" or "jingle
     * node").
     * @param relatedCandidate the relatedCandidate: null for a host candidate,
     * the base address (host candidate) for a reflexive candidate, the mapped
     * address (the mapped address of the TURN allocate response) for a relayed
     * candidate.
     */
    public LocalCandidate(TransportAddress transportAddress,
                          Component        parentComponent,
                          CandidateType    type,
                          CandidateExtendedType extendedType,
                          LocalCandidate  relatedCandidate)
    {
        super(transportAddress, parentComponent, type, relatedCandidate);
        this.extendedType = extendedType;
    }

    /**
     * Gets the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>.
     *
     * @return the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>
     *
     * @deprecated This should be used by the library only. Users of ice4j
     * should use {@link org.ice4j.ice.CandidatePair#getDatagramSocket()}
     * on the appropriate <tt>CandidatePair</tt> instead.
     */
    @Deprecated
    public DatagramSocket getDatagramSocket()
    {
        IceSocketWrapper wrapper = getIceSocketWrapper();
        return wrapper == null ? null : wrapper.getUDPSocket();
    }

    /**
     * Gets the <tt>Socket</tt> associated with this
     * <tt>Candidate</tt>.
     *
     * @return the <tt>Socket</tt> associated with this
     * <tt>Candidate</tt>
     *
     * @deprecated This should be used by the library only. Users of ice4j
     * should use {@link org.ice4j.ice.CandidatePair#getSocket()} on the
     * appropriate <tt>CandidatePair</tt> instead.
     */
    @Deprecated
    public Socket getSocket()
    {
        IceSocketWrapper wrapper = getIceSocketWrapper();
        return wrapper == null ? null : wrapper.getTCPSocket();
    }

    /**
     * Gets the <tt>IceSocketWrapper</tt> associated with this
     * <tt>Candidate</tt>.
     *
     * @return the <tt>IceSocketWrapper</tt> associated with this
     * <tt>Candidate</tt>
     */
    protected abstract IceSocketWrapper getIceSocketWrapper();

    /**
     * Creates if necessary and returns a <tt>DatagramSocket</tt> that would
     * capture all STUN packets arriving on this candidate's socket. If the
     * <tt>serverAddress</tt> parameter is not <tt>null</tt> this socket would
     * only intercept packets originating at this address.
     *
     * @param serverAddress the address of the source we'd like to receive
     * packets from or <tt>null</tt> if we'd like to intercept all STUN packets.
     *
     * @return the <tt>DatagramSocket</tt> that this candidate uses when sending
     * and receiving STUN packets, while harvesting STUN candidates or
     * performing connectivity checks.
     */
    public IceSocketWrapper getStunSocket(TransportAddress serverAddress)
    {
        IceSocketWrapper hostSocket = getIceSocketWrapper();

        if (hostSocket != null
              && hostSocket.getTCPSocket() != null)
        {
            Socket tcpSocket = hostSocket.getTCPSocket();
            Socket tcpStunSocket = null;

            if (tcpSocket instanceof MultiplexingSocket)
            {
                DatagramPacketFilter stunDatagramPacketFilter
                    = createStunDatagramPacketFilter(serverAddress);
                Throwable exception = null;

                try
                {
                    tcpStunSocket
                        = ((MultiplexingSocket) tcpSocket)
                            .getSocket(stunDatagramPacketFilter);
                }
                catch (SocketException sex) //don't u just luv da name? ;)
                {
                    logger.log(Level.SEVERE,
                               "Failed to acquire Socket"
                                   + " specific to STUN communication.",
                               sex);
                    exception = sex;
                }
                if (tcpStunSocket == null)
                {
                    throw
                        new IllegalStateException(
                                "Failed to acquire Socket"
                                    + " specific to STUN communication",
                                exception);
                }
            }
            else
            {
                throw
                    new IllegalStateException(
                            "The socket of "
                                + getClass().getSimpleName()
                                + " must be a MultiplexingSocket " +
                                        "instance");
            }

            IceTcpSocketWrapper stunSocket = null;
            try
            {
                stunSocket = new IceTcpSocketWrapper(tcpStunSocket);
            }
            catch(IOException e)
            {
                logger.info("Failed to create IceTcpSocketWrapper " + e);
            }

            return stunSocket;
        }
        else if (hostSocket != null
                   && hostSocket.getUDPSocket() != null)
        {
            DatagramSocket udpSocket = hostSocket.getUDPSocket();
            DatagramSocket udpStunSocket = null;

            if (udpSocket instanceof MultiplexingDatagramSocket)
            {
                DatagramPacketFilter stunDatagramPacketFilter
                    = createStunDatagramPacketFilter(serverAddress);
                Throwable exception = null;

                try
                {
                    udpStunSocket
                        = ((MultiplexingDatagramSocket) udpSocket)
                            .getSocket(stunDatagramPacketFilter);
                }
                catch (SocketException sex) //don't u just luv da name? ;)
                {
                    logger.log(Level.SEVERE,
                               "Failed to acquire DatagramSocket"
                                   + " specific to STUN communication.",
                               sex);
                    exception = sex;
                }
                if (udpStunSocket == null)
                {
                    throw
                        new IllegalStateException(
                                "Failed to acquire DatagramSocket"
                                    + " specific to STUN communication",
                                exception);
                }
            }
            else
            {
                throw
                    new IllegalStateException(
                            "The socket of "
                                + getClass().getSimpleName()
                                + " must be a MultiplexingDatagramSocket " +
                                        "instance");
            }
            return new IceUdpSocketWrapper(udpStunSocket);
        }

        return null;
    }

    /**
     * Gets the <tt>StunStack</tt> associated with this <tt>Candidate</tt>.
     *
     * @return the <tt>StunStack</tt> associated with this <tt>Candidate</tt>
     */
    public StunStack getStunStack()
    {
        return
            getParentComponent()
                .getParentStream()
                    .getParentAgent()
                        .getStunStack();
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
     */
    protected StunDatagramPacketFilter createStunDatagramPacketFilter(
            TransportAddress serverAddress)
    {
        return new StunDatagramPacketFilter(serverAddress);
    }

    /**
     * Frees resources allocated by this candidate such as its
     * <tt>DatagramSocket</tt>, for example. The <tt>socket</tt> of this
     * <tt>LocalCandidate</tt> is closed only if it is not the <tt>socket</tt>
     * of the <tt>base</tt> of this <tt>LocalCandidate</tt>.
     */
    protected void free()
    {
        // Close the socket associated with this LocalCandidate.
        IceSocketWrapper socket = getIceSocketWrapper();

        if (socket != null)
        {
            LocalCandidate base = getBase();

            if ((base == null)
                    || (base == this)
                    || (base.getIceSocketWrapper() != socket))
            {
                //remove our socket from the stack.
                getStunStack().removeSocket(getTransportAddress());

                /*
                 * Allow this LocalCandidate implementation to not create a
                 * socket if it still hasn't created one.
                 */
                socket.close();
            }
        }
    }

    /**
     * Determines whether this <tt>Candidate</tt> is the default one for its
     * parent component.
     *
     * @return <tt>true</tt> if this <tt>Candidate</tt> is the default for its
     * parent component and <tt>false</tt> if it isn't or if it has no parent
     * Component yet.
     */
    @Override
    public boolean isDefault()
    {
        Component parentCmp = getParentComponent();

        return (parentCmp != null) && equals(parentCmp.getDefaultCandidate());
    }

    /**
     * Set the local ufrag.
     *
     * @param ufrag local ufrag
     */
    public void setUfrag(String ufrag)
    {
        this.ufrag = ufrag;
    }

    /**
     * Get the local ufrag.
     *
     * @return local ufrag
     */
    @Override
    public String getUfrag()
    {
        return ufrag;
    }

    /**
     * Returns the type of method used to discover this candidate ("host",
     * "upnp", "stun peer reflexive", "stun server reflexive", "turn relayed",
     * "google turn relayed", "google tcp turn relayed" or "jingle node").
     *
     * @return The type of method used to discover this candidate ("host",
     * "upnp", "stun peer reflexive", "stun server reflexive", "turn relayed",
     * "google turn relayed", "google tcp turn relayed" or "jingle node").
     */
    public CandidateExtendedType getExtendedType()
    {
        return this.extendedType;
    }

    /**
     * Sets the type of method used to discover this candidate ("host", "upnp",
     * "stun peer reflexive", "stun server reflexive", "turn relayed", "google
     * turn relayed", "google tcp turn relayed" or "jingle node").
     *
     * @param extendedType The type of method used to discover this candidate
     * ("host", "upnp", "stun peer reflexive", "stun server reflexive", "turn
     * relayed", "google turn relayed", "google tcp turn relayed" or "jingle
     * node").
     */
    public void setExtendedType(CandidateExtendedType extendedType)
    {
        this.extendedType = extendedType;
    }

    /**
     * Find the candidate corresponding to the address given in parameter.
     *
     * @param relatedAddress The related address:
     * - null for a host candidate,
     * - the base address (host candidate) for a reflexive candidate,
     * - the mapped address (the mapped address of the TURN allocate response)
     * for a relayed candidate.
     * - null for a peer reflexive candidate : there is no way to know the
     * related address.
     *
     * @return The related candidate corresponding to the address given in
     * parameter:
     * - null for a host candidate,
     * - the base address (host candidate) for a reflexive candidate,
     * - the mapped address (the mapped address of the TURN allocate response)
     * for a relayed candidate.
     * - null for a peer reflexive candidate : there is no way to know the
     * related address.
     */
    @Override
    protected LocalCandidate findRelatedCandidate(
            TransportAddress relatedAddress)
    {
        return getParentComponent().findLocalCandidate(relatedAddress);
    }

    /**
     * Gets the value of the 'ssl' flag.
     * @return the value of the 'ssl' flag.
     */
    public boolean isSSL()
    {
        return isSSL;
    }

    /**
     * Sets the value of the 'ssl' flag.
     * @param isSSL the value to set.
     */
    public void setSSL(boolean isSSL)
    {
        this.isSSL = isSSL;
    }
}
