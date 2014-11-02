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

/**
 * A candidate represents a transport address that is a potential point of
 * contact for receipt of media. Candidates also have properties - their
 * type (server reflexive, relayed or host), priority, foundation,
 * and base.
 * <p>
 * At this point this class only supports UDP candidates. Implementation of
 * support for other transport protocols should mean that this class should
 * become abstract and some transport specific components like to socket for
 * example should be brought down the inheritance chain.
 * </p>
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public abstract class Candidate<T extends Candidate<?>>
    implements Comparable<T>
{
    /**
     * The maximum value for a candidate's type preference.
     */
    public static final int MAX_TYPE_PREFERENCE = 126;

    /**
     * The minimum value for a candidate's type preference.
     */
    public static final int MIN_TYPE_PREFERENCE = 0;

    /**
     * The maximum value for a candidate's local preference.
     */
    public static final int MAX_LOCAL_PREFERENCE = 65535;

    /**
     * The minimum value for a candidate's local preference.
     */
    public static final int MIN_LOCAL_PREFERENCE = 0;

    /**
     * The transport address represented by this candidate.
     */
    private TransportAddress transportAddress = null;

    /**
     * The type of this candidate. At this point the ICE specification (and
     * hence this implementation) only defines for candidate types: host,
     * server reflexive, peer reflexive and relayed candidates. Others may be
     * added in the future.
     */
    private CandidateType candidateType = null;

    /**
     * An arbitrary string that is the same for two candidates
     * that have the same type, base IP address, protocol (UDP, TCP,
     * etc.) and STUN or TURN server. If any of these are different then
     * the foundation will be different. Two candidate pairs with the
     * same foundation pairs are likely to have similar network
     * characteristics. Foundations are used in the frozen algorithm.
     */
    private String foundation = null;

    /**
     * The base of a server reflexive candidate is the host candidate
     * from which it was derived. A host candidate is also said to have
     * a base, equal to that candidate itself. Similarly, the base of a
     * relayed candidate is that candidate itself.
     */
    private T base = null;

    /**
     * A unique priority number that MUST be a positive integer between 1 and
     * (2**32 - 1). This priority will be set and used by ICE algorithms to
     * determine the order of the connectivity checks and the relative
     * preference for candidates.
     */
    protected long priority = 0;

    /**
     * Specifies whether the address associated with this candidate belongs to
     * a VPN interface. In many cases (e.g. when running on a 1.5 JVM) we won't
     * be able to determine whether an interface is virtual or not. If we are
     * however (that is when running a more recent JVM) we will reflect it in
     * this property.
     */
    private boolean virtual = false;

    /**
     * The component that this candidate was created for. Every candidate is
     * always associated with a specific component for which it is a candidate.
     */
    private final Component parentComponent;

    /**
     * The address of the STUN server that was used to obtain this
     * <tt>Candidate</tt>. Will be <tt>null</tt> if this is not a server
     * reflexive candidate.
     */
    private TransportAddress stunServerAddress = null;

    /**
     * The address of the relay server (i.e. TURN, Jingle Nodes, ...) that was
     * used to obtain this <tt>Candidate</tt>. Will be <tt>null</tt> if this is
     * not a relayed candidate.
     */
    private TransportAddress relayServerAddress = null;

    /**
     * The address that our TURN/STUN server returned as mapped if this is a
     * relayed or a reflexive <tt>Candidate</tt>. Will remain <tt>null</tt> if
     * this is a host candidate.
     */
    private TransportAddress mappedAddress = null;

    /**
     * The related candidate:
     * - null for a host candidate,
     * - the base address (host candidate) for a reflexive candidate,
     * - the mapped address (the mapped address of the TURN allocate response)
     * for a relayed candidate.
     * - null for a peer reflexive candidate : there is no way to know the
     * related address.
     */
    private T relatedCandidate = null;

    /**
     * The <tt>CandidateTcpType</tt> for this <tt>Candidate</tt>.
     */
    private CandidateTcpType tcpType = null;

    /**
     * Creates a candidate for the specified transport address and properties.
     *
     * @param transportAddress  the transport address that this candidate is
     * encapsulating.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     * @param type the <tt>CandidateType</tt> for this <tt>Candidate</tt>.
     * @param relatedCandidate The related candidate:
     * - null for a host candidate,
     * - the base address (host candidate) for a reflexive candidate,
     * - the mapped address (the mapped address of the TURN allocate response)
     * for a relayed candidate.
     * - null for a peer reflexive candidate : there is no way to know the
     * related address.
     */
    public Candidate(TransportAddress transportAddress,
                     Component        parentComponent,
                     CandidateType    type,
                     T relatedCandidate)
    {
        this.transportAddress = transportAddress;
        this.parentComponent = parentComponent;
        this.candidateType = type;
        this.relatedCandidate = relatedCandidate;
    }

    /**
     * Returns the type of this candidate which should be an instance of the
     * {@link CandidateType} enumeration.
     *
     * @return a <tt>CandidateType</tt> indicating the type of this
     * <tt>Candidate</tt>.
     */
    public CandidateType getType()
    {
        return candidateType;
    }

    /**
     * Sets the type of this candidate which should be an instance of the
     * {@link CandidateType} enumeration.
     *
     * @param candidateType a <tt>CandidateType</tt> instance indicating the
     * type of this <tt>Candidate</tt>.
     */
    public void setCandidateType(CandidateType candidateType)
    {
        this.candidateType = candidateType;
    }

    /**
     * Returns a <tt>String</tt> containing the foundation of this
     * <tt>Candidate</tt>. A foundation is an arbitrary <tt>String</tt> that is
     * the same for candidates that have the same type, base IP address,
     * transport protocol (UDP, TCP, etc.) and STUN or TURN server. If any of
     * these are different then the foundation will be different. Two candidate
     * pairs with the same foundation pairs are likely to have similar network
     * characteristics. Typically, candidates for RTP and RTCP streams will
     * share the same foundation. Foundations are used in the frozen algorithm.
     *
     * @return the foundation of this <tt>Candidate</tt>.
     */
    public String getFoundation()
    {
        return foundation;
    }

    /**
     * Sets this <tt>Candidate</tt>'s foundation. A foundation is an arbitrary
     * string that is always the same for candidates that have the same type,
     * base IP address, protocol (UDP, TCP, etc.) and STUN or TURN server. If
     * any of these are different then the foundation will be different. Two
     * candidate pairs with the same foundation pairs are likely to have similar
     * network characteristics. Foundations are used in the frozen algorithm.
     *
     * @param foundation the foundation of this <tt>Candidate</tt>.
     */
    public void setFoundation(String foundation)
    {
        this.foundation = foundation;
    }

    /**
     * Returns this <tt>Candidate</tt>'s base. The base of a server
     * reflexive candidate is the host candidate from which it was derived.
     * A host candidate is also said to have a base, equal to that candidate
     * itself. Similarly, the base of a relayed candidate is that candidate
     * itself.
     *
     * @return the base <tt>Candidate</tt> for this <tt>Candidate</tt>.
     */
    public T getBase()
    {
        return base;
    }

    /**
     * Sets this <tt>Candidate</tt>'s base. The base of a server
     * reflexive candidate is the host candidate from which it was derived.
     * A host candidate is also said to have a base, equal to that candidate
     * itself. Similarly, the base of a relayed candidate is that candidate
     * itself.
     *
     * @param base the base <tt>Candidate</tt> of this <tt>Candidate</tt>.
     */
    public void setBase(T base)
    {
        this.base = base;
    }

    /**
     * Returns the priority of this candidate. Priority is a unique priority
     * number that MUST be a positive integer between 1 and (2**32 - 1). This
     * priority will be set and used by ICE algorithms to  determine the order
     * of the connectivity checks and the relative preference for candidates.
     *
     * @return a number between 1 and (2**32 - 1) indicating the priority of
     * this candidate.
     */
    public long getPriority()
    {
        return priority;
    }

    /**
     * Returns the transport address that this candidate is representing.
     *
     * @return the TransportAddress encapsulated by this Candidate.
     */
    public TransportAddress getTransportAddress()
    {
        return transportAddress;
    }

    /**
     * Indicates whether some other Candidate is "equal to" this one. We
     * consider candidates equal when they are redundant, i.e.
     * <p>
     * @param obj the reference object with which to compare.
     * <p>
     * @return <code>true</code> if this <tt>Candidate</tt> is equal to the
     * obj argument; <code>false</code> otherwise.
     *
     * @throws java.lang.NullPointerException if <tt>obj</tt> is null;
     */
    @Override
    public boolean equals(Object obj)
        throws NullPointerException
    {
        if(obj == this)
            return true;

        if( ! (obj instanceof Candidate))
            return false;

        Candidate<?> targetCandidate = (Candidate<?>) obj;

        //compare candidate addresses
        if( ! targetCandidate.getTransportAddress()
                .equals(getTransportAddress()))
            return false;

        //compare bases
        if( getBase() == null )
        {
            if (targetCandidate.getBase() != null)
                return false;
        }

        //compare other properties
        if(((getBase() == this && targetCandidate.getBase() == targetCandidate)
            || getBase().equals(targetCandidate.getBase()))
            && getPriority() == targetCandidate.getPriority()
            && getType() == targetCandidate.getType()
            && getFoundation().equals(targetCandidate.getFoundation()))
        {
            return true;
        }
        return false;
    }

    /**
     * Returns a reference to the <tt>Component</tt> that this candidate belongs
     * to.
     *
     * @return a reference to the <tt>Component</tt> that this candidate belongs
     * to.
     */
    public Component getParentComponent()
    {
        return parentComponent;
    }

    /**
     * Computes the priority for this <tt>Candidate</tt> based on the procedures
     * defined in the ICE specification..
     *
     * @return the priority for this <tt>Candidate</tt> as per the procedures
     * defined in the ICE specification..
     */
    public long computePriority()
    {
        this.priority = computePriorityForType(getType());

        return this.priority;
    }

    /**
     * Computes the priority for this <tt>Candidate</tt> based on the procedures
     * defined in the Google Talk specification.
     *
     * @return the priority for this <tt>Candidate</tt> as per the procedures
     * defined in the ICE specification..
     */
    public long computeGTalkPriority()
    {
        this.priority = computeGTalkPriorityForType(getType());

        return this.priority;
    }

    /**
     * Computes the priority this <tt>Candidate</tt> would have if it were of
     * the specified <tt>candidateType</tt> and based on the procedures
     * defined in the ICE specification. The reason we need this method in
     * addition to the {@link #computePriority()} one is the need to be able
     * to compute the priority of a peer reflexive <tt>candidate</tt> that we
     * might learn during connectivity checks through this <tt>Candidate</tt>.
     *
     * @param candidateType the hypothetical type that we'd like to use when
     * computing the priority for this <tt>Candidate</tt>.
     *
     * @return the priority this <tt>Candidate</tt> would have had if it were
     * of the specified <tt>candidateType</tt>.
     */
    public long computePriorityForType(CandidateType candidateType)
    {
        //According to the ICE spec we compute priority this way:
        //priority = (2^24)*(type preference) +
        //           (2^8)*(local preference) +
        //           (2^0)*(256 - component ID)

        return (long) (getTypePreference(candidateType)  << 24) +
               (long) (getLocalPreference()              << 8 ) +
               (long) (256 - getParentComponent().getComponentID());
    }

    /**
     * Computes the priority this <tt>Candidate</tt> would have if it were of
     * the specified <tt>candidateType</tt> and based on the procedures
     * defined in the Google Talk specification.
     *
     * @param candidateType the hypothetical type that we'd like to use when
     * computing the priority for this <tt>Candidate</tt>.
     * @return the priority this <tt>Candidate</tt> would have had if it were
     * of the specified <tt>candidateType</tt>.
     */
    public long computeGTalkPriorityForType(CandidateType candidateType)
    {
        long priority = 0;

        /* Google Talk priority is in range 0 - 1, we multiply this by 1000
         * to have a long rather than float
         */
        if(candidateType == CandidateType.HOST_CANDIDATE)
        {
           priority += 0.95 * 1000 -
               (this.getBase().getTransport() == Transport.TCP ? 200 : 0);
        }
        else if(candidateType == CandidateType.PEER_REFLEXIVE_CANDIDATE)
        {
            priority += (long)(0.9 * 1000) -
                (this.getBase().getTransport() == Transport.TCP ? 200 : 0);
        }
        else if(candidateType == CandidateType.SERVER_REFLEXIVE_CANDIDATE)
        {
            priority += (long)(0.9 * 1000);
        }
        else //relayed candidates
        {
            priority += (long)(0.5 * 1000);
        }

        priority -= getParentComponent().getComponentID() - 1;

        InetAddress addr = getTransportAddress().getAddress();

        // IPv6 has better priority than IPv4
        if(addr instanceof Inet6Address)
        {
            if(addr.isLinkLocalAddress())
            {
                priority += 40;
            }
            else
                priority += 50;
        }

        return priority;
    }

    /**
     * Returns the type preference that should be used when computing priority
     * for <tt>Candidate</tt>s of the specified <tt>candidateType</tt>.
     * The type preference MUST be an integer from <tt>0</tt> to <tt>126</tt>
     * inclusive, and represents the preference for the type of the candidate
     * (where the types are local, server reflexive, peer reflexive and
     * relayed). A <tt>126</tt> is the highest preference, and a <tt>0</tt> is
     * the lowest. Setting the value to a <tt>0</tt> means that candidates of
     * this type will only be used as a last resort.  The type preference MUST
     * be identical for all candidates of the same type and MUST be different
     * for candidates of different types.  The type preference for peer
     * reflexive candidates MUST be higher than that of server reflexive
     * candidates.
     *
     * @param candidateType the <tt>CandidateType</tt> that we'd like to obtain
     * a preference for.
     *
     * @return the type preference for this <tt>Candidate</tt> as per the
     * procedures in the ICE specification.
     */
    private static int getTypePreference(CandidateType candidateType)
    {
        int typePreference;

        if(candidateType == CandidateType.HOST_CANDIDATE)
        {
            typePreference = MAX_TYPE_PREFERENCE; // 126
        }
        else if(candidateType == CandidateType.PEER_REFLEXIVE_CANDIDATE)
        {
            typePreference = 110;
        }
        else if(candidateType == CandidateType.SERVER_REFLEXIVE_CANDIDATE)
        {
            typePreference = 100;
        }
        else //relayed candidates
        {
            typePreference =  MIN_TYPE_PREFERENCE; // 0
        }

        return typePreference;
    }

    /**
     * Calculates and returns the local preference for this <tt>Candidate</tt>
     * <p>
     * The local preference MUST be an integer from <tt>0</tt> to <tt>65535</tt>
     * inclusive. It represents a preference for the particular IP address from
     * which the candidate was obtained, in cases where an agent is multihomed.
     * <tt>65535</tt> represents the highest preference, and a zero, the lowest.
     * When there is only a single IP address, this value SHOULD be set to
     * <tt>65535</tt>. More generally, if there are multiple candidates for a
     * particular component for a particular media stream which have the same
     * type, the local preference MUST be unique for each one. In this
     * specification, this only happens for multihomed hosts.  If a host is
     * multihomed because it is dual stacked, the local preference SHOULD be
     * set equal to the precedence value for IP addresses described in RFC 3484.
     * </p>
     * @return the local preference for this <tt>Candidate</tt>.
     */
    private int getLocalPreference()
    {
        //The ICE spec says: When there is only a single IP address, this value
        //SHOULD be set to.
        if(getParentComponent().countLocalHostCandidates() < 2)
            return MAX_LOCAL_PREFERENCE;

        //The ICE spec also says: Furthermore, if an agent is multi-homed and
        //has multiple IP addresses, the local preference for host candidates
        //from a VPN interface SHOULD have a priority of 0.
        if(isVirtual())
            return MIN_LOCAL_PREFERENCE;

        InetAddress addr = getTransportAddress().getAddress();

        //the following tries to reuse precedence from RFC 3484 but that's a
        //bit tricky since it is not meant to be used exactly the way that
        //Johnnie seems to think.

        //prefer IPv6 to IPv4
        if(addr instanceof Inet6Address)
        {
            //prefer link local addresses to global ones
            if(addr.isLinkLocalAddress())
                return 30;
            else
                return 40;
        }
        else
        {
            //IPv4
            return 10;
        }
    }

    /**
     * Determines whether the address associated with this candidate belongs to
     * a VPN interface. In many cases (e.g. when running on a 1.5 JVM) we won't
     * be able to determine whether an interface is virtual or not. If we are
     * however (that is when running a more recent JVM) we will reflect it in
     * this property. Note that the <tt>isVirtual</tt> property is not really
     * an ICE concept. The ICE specs only mention it and give basic guidelines
     * as to how it should be handled so other implementations maybe dealing
     * with it differently.
     *
     * @return <tt>true</tt> if we were able to determine that the address
     * associated with this <tt>Candidate</tt> comes from a virtual interface
     * and <tt>false</tt> if otherwise.
     */
    public boolean isVirtual()
    {
        return virtual;
    }

    /**
     * Specifies whether the address associated with this candidate belongs to
     * a VPN interface. In many cases (e.g. when running on a 1.5 JVM) we won't
     * be able to determine whether an interface is virtual or not. If we are
     * however (that is when running a more recent JVM) we will reflect it in
     * this property. Note that the <tt>isVirtual</tt> property is not really
     * an ICE concept. The ICE specs only mention it and give basic guidelines
     * as to how it should be handled so other implementations maybe dealing
     * with it differently.
     *
     * @param virtual <tt>true</tt> if we were able to determine that the
     * address associated with this <tt>Candidate</tt> comes from a virtual
     * interface and <tt>false</tt> if otherwise.
     */
    public void setVirtual(boolean virtual)
    {
        this.virtual = virtual;
    }

    /**
     * Returns the address of the STUN server that was used to obtain this
     * <tt>Candidate</tt> or <tt>null</tt> if this is not a server reflexive
     * candidate.
     *
     * @return the address of the STUN server that was used to obtain this
     * <tt>Candidate</tt> or <tt>null</tt> if this is not a server reflexive
     * candidate.
     */
    public TransportAddress getStunServerAddress()
    {
        return stunServerAddress;
    }

    /**
     * Sets the address of the STUN server that was used to obtain this
     * <tt>Candidate</tt>. Only makes sense if this is a relayed candidate.
     *
     * @param address the address of the STUN server that was used to obtain
     * this <tt>Candidate</tt> or <tt>null</tt> if this is not a server
     * reflexive candidate.
     */
    protected void setStunServerAddress(TransportAddress address)
    {
        this.stunServerAddress = address;
    }

    /**
     * Returns the address of the relay server (i.e. TURN, Jingle Nodes, ...)
     * that was used to obtain this <tt>Candidate</tt> or <tt>null</tt> if this
     * is not a relayed candidate.
     *
     * @return the address of the relay server that was used to obtain this
     * <tt>Candidate</tt> or <tt>null</tt> if this is not a relayed candidate.
     */
    public TransportAddress getRelayServerAddress()
    {
        return relayServerAddress;
    }

    /**
     * Sets the address of the relay server (i.e. TURN, Jingle Nodes, ...) that
     * was used to obtain this <tt>Candidate</tt>. Only makes sense if this is a
     *  relayed candidate.
     *
     * @param address the address of the relay server that was used to obtain
     * this <tt>Candidate</tt> or <tt>null</tt> if this is not a relayed
     * candidate.
     */
    protected void setRelayServerAddress(TransportAddress address)
    {
        this.relayServerAddress = address;
    }

    /**
     * Returns the address that was returned to us a "mapped address" from a
     * TURN or a STUN server in case this <tt>Candidate</tt> is relayed or
     * reflexive and <tt>null</tt> otherwise. Note that the address returned by
     * this method would be equal to the transport address for reflexive
     * <tt>Candidate</tt>s but not for relayed ones.
     *
     * @return the address that our TURN/STUN server returned as mapped if this
     * is a relayed or a reflexive <tt>Candidate</tt> or <tt>null</tt> if this
     * is a host candidate.
     */
    public TransportAddress getMappedAddress()
    {
        return mappedAddress;
    }

    /**
     * Sets the address that was returned to us a "mapped address" from a
     * TURN or a STUN server in case this <tt>Candidate</tt> is relayed.
     *
     * @param address the address that our TURN/STUN server returned as mapped
     * if this is a relayed or a reflexive <tt>Candidate</tt>.
     */
    protected void setMappedAddress(TransportAddress address)
    {
        this.mappedAddress = address;
    }

    /**
     * Returns the <tt>Transport</tt> for this <tt>Candidate</tt>. This is a
     * convenience method only and it is equivalent to retrieving the transport
     * of this <tt>Candidate</tt>'s transport address.
     *
     * @return the <tt>Transport</tt> that this <tt>Candidate</tt> was obtained
     * for/with.
     */
    public Transport getTransport()
    {
        return getTransportAddress().getTransport();
    }

    /**
     * Returns a <tt>TransportAddress</tt> related to this <tt>Candidate</tt>.
     * Related addresses are present for server reflexive, peer reflexive and
     * relayed candidates. If a candidate is server or peer reflexive,
     * the related address is equal to the base of this <tt>Candidate</tt>.
     * If the candidate is relayed, the returned address is equal to the mapped
     * address. If the candidate is a host candidate then the method returns
     * <tt>null</tt>.
     *
     * @return the <tt>TransportAddress</tt> of the base if this is a reflexive
     * candidate, the mapped address in the case of a relayed candidate, and
     * <tt>null</tt> if this is a host or a remote candidate.
     */
    public TransportAddress getRelatedAddress()
    {
        if(getRelatedCandidate() != null)
        {
            return getRelatedCandidate().getTransportAddress();
        }
        return null;
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
    protected abstract T findRelatedCandidate(TransportAddress relatedAddress);

    /**
     * Returns a <tt>String</tt> representation of this <tt>Candidate</tt>
     * containing its <tt>TransportAddress</tt>, base, foundation, priority and
     * whatever other properties may be relevant.
     *
     * @return a <tt>String</tt> representation of this <tt>Candidate</tt>.
     */
    @Override
    public String toString()
    {
        StringBuilder buff = new StringBuilder("candidate:");

        buff.append(getFoundation());
        buff.append(" ").append(getParentComponent().getComponentID());
        buff.append(" ").append(getTransport());
        buff.append(" ").append(getPriority());
        buff.append(" ").append(getTransportAddress().getHostAddress());
        buff.append(" ").append(getTransportAddress().getPort());
        buff.append(" typ ").append(getType());

        TransportAddress relAddr = getRelatedAddress();

        if(relAddr != null)
        {
            buff.append(" raddr ").append(relAddr.getHostAddress());
            buff.append(" rport ").append(relAddr.getPort());
        }

        return buff.toString();
    }

    /**
     * Returns short <tt>String</tt> representation of this <tt>Candidate</tt>.
     * @return short <tt>String</tt> representation of this <tt>Candidate</tt>.
     */
    public String toShortString()
    {
        return getTransportAddress() + "/" + getType();
    }

    /**
     * Returns an integer indicating the preference that this <tt>Candidate</tt>
     * should be considered with for becoming a default candidate.
     *
     * @return an integer indicating the preference that this <tt>Candidate</tt>
     * should be considered with for becoming a default candidate.
     */
    protected int getDefaultPreference()
    {
        if( getType() == CandidateType.RELAYED_CANDIDATE)
            return 30;

        if( getType() == CandidateType.SERVER_REFLEXIVE_CANDIDATE)
            return 10;

        if( getType() == CandidateType.HOST_CANDIDATE)
        {
            //prefer IPv4 as default since many servers would still freak out
            //when seeing IPv6 address.
            if(getTransportAddress().isIPv6())
                return 20;
            else
                return 25;
        }

        //WTF?
        return 5;
    }

    /**
     * Determines whether this <tt>Candidate</tt>'s<tt>TransportAddress</tt> is
     * theoretically usable for communication with that of <tt>dst</tt>. Same
     * as calling:
     * <p>
     *  <tt>getTransportAddress().canReach(dst.getTransportAddress())</tt>
     * </p>
     *
     * @param dst the <tt>Candidate</tt> that we'd like to check for
     * reachability from this one.
     *
     * @return <tt>true</tt> if this {@link Candidate} shares the same
     * <tt>Transport</tt> and family as <tt>dst</tt> or <tt>false</tt>
     * otherwise.
     *
     */
    public boolean canReach(Candidate<?> dst)
    {
        return getTransportAddress().canReach(dst.getTransportAddress());
    }

    /**
     * Determines whether this <tt>Candidate</tt> is the default one for its
     * parent component.
     *
     * @return <tt>true</tt> if this <tt>Candidate</tt> is the default for its
     * parent component and <tt>false</tt> if it isn't or if it has no parent
     * Component yet.
     */
    public abstract boolean isDefault();

    /**
     * Get the local ufrag.
     *
     * @return local ufrag
     */
    public abstract String getUfrag();

    /**
     * Returns this candidate host address.
     *
     * @return This candidate host address.
     */
    public TransportAddress getHostAddress()
    {
        switch (getType())
        {
            case SERVER_REFLEXIVE_CANDIDATE:
                if(getBase() != null)
                {
                    return getBase().getHostAddress();
                }
                break;
            case PEER_REFLEXIVE_CANDIDATE:
                if(getBase() != null)
                {
                    return getBase().getHostAddress();
                }
                break;
            case RELAYED_CANDIDATE:
                if(getRelatedCandidate() != null)
                {
                    return getRelatedCandidate().getHostAddress();
                }
                break;
            default: //host candidate
                return getTransportAddress();
        }
        return null;
    }

    /**
     * Returns this candidate reflexive address.
     *
     * @return This candidate reflexive address. Null if this candidate
     * does not use a peer/server reflexive address.
     */
    public TransportAddress getReflexiveAddress()
    {
        switch (getType())
        {
            case SERVER_REFLEXIVE_CANDIDATE:
                return getTransportAddress();
            case PEER_REFLEXIVE_CANDIDATE:
                return getTransportAddress();
            case RELAYED_CANDIDATE:
                // Corresponding to getMappedAddress();
                if(getRelatedCandidate() != null)
                {
                    return getRelatedCandidate().getReflexiveAddress();
                }
                break;
            default: //host candidate
                return null;
        }
        return null;
    }

    /**
     * Returns this candidate relayed address.
     *
     * @return This candidate relayed address. Null if this candidate
     * does not use a relay.
     */
    public TransportAddress getRelayedAddress()
    {
        switch (getType())
        {
            case SERVER_REFLEXIVE_CANDIDATE:
                return null;
            case PEER_REFLEXIVE_CANDIDATE:
                return null;
            case RELAYED_CANDIDATE:
                return getTransportAddress();
            default: //host candidate
                return null;
        }
    }

    /**
     * Returns the related candidate corresponding to the address given in
     * parameter:
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
    public T getRelatedCandidate()
    {
        if(this.relatedCandidate == null)
        {
            TransportAddress relatedAddress = null;
            switch (getType())
            {
                case SERVER_REFLEXIVE_CANDIDATE:
                    if(getBase() != null)
                    {
                        relatedAddress = getBase().getTransportAddress();
                    }
                    break;
                case PEER_REFLEXIVE_CANDIDATE:
                    if(getBase() != null)
                    {
                        relatedAddress = getBase().getTransportAddress();
                    }
                    break;
                case RELAYED_CANDIDATE:
                    relatedAddress = getMappedAddress();
                    break;
                default:
                    //host candidate
                    return null;
            }
            // Update the related candidate conforming to the related address.
            this.relatedCandidate = findRelatedCandidate(relatedAddress);
        }

        return this.relatedCandidate;
    }

    /**
     * Compares this <tt>Candidate</tt> with the specified one based on their
     * priority and returns a negative integer, zero, or a positive integer if
     * this <tt>Candidate</tt> has a lower, equal, or greater priority than the
     * second.
     *
     * @param candidate the second <tt>Candidate</tt> to compare.
     *
     * @return a negative integer, zero, or a positive integer as the first
     * <tt>Candidate</tt> has a lower, equal, or greater priority than the
     * second.
     */
    public int compareTo(T candidate)
    {
        return CandidatePrioritizer.compareCandidates(this, candidate);
    }

    /**
     * Gets the <tt>CandidateTcpType</tt> for this <tt>Candidate</tt>.
     * @return the <tt>CandidateTcpType</tt> for this <tt>Candidate</tt>.
     */
    public CandidateTcpType getTcpType()
    {
        return tcpType;
    }

    /**
     * Sets the <tt>CandidateTcpType</tt> for this <tt>Candidate</tt>.
     * @param tcpType the <tt>CandidateTcpType</tt> to set.
     */
    public void setTcpType(CandidateTcpType tcpType)
    {
        this.tcpType = tcpType;
    }
}