/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

/**
 * Enumeration which lists the different available methods used to discover new
 * candidates.
 *
 * @author Vincent Lucas
 */
public enum CandidateExtendedType
{
    /**
     * There candidate is discovered directly by the host.
     */
    HOST_CANDIDATE("host"),

    /**
     * The candidate is discovered by using UPNP method.
     */
    UPNP_CANDIDATE("upnp"),

    /**
     * The candidate is discovered through the use of a static mask (probably
     * obtained through configuration).
     */
    STATICALLY_MAPPED_CANDIDATE("statically mapped"),

    /**
     * There candidate is discovered by using STUN peer reflexive method (cf.
     * RFC5389).
     */
    STUN_PEER_REFLEXIVE_CANDIDATE("stun peer reflexive"),

    /**
     * The candidate is discovered by using STUN server reflexive method (cf.
     * RFC5389).
     */
    STUN_SERVER_REFLEXIVE_CANDIDATE("stun server reflexive"),

    /**
     * The candidate is discovered by using TURN relayed method (cf. RFC5766).
     */
    TURN_RELAYED_CANDIDATE("turn relayed"),

    /**
     * The candidate is discovered by using TURN relayed method (cf. RFC5766).
     */
    GOOGLE_TURN_RELAYED_CANDIDATE("google turn relayed"),

    /**
     * The candidate is discovered by using TURN relayed method and using TCP
     * (cf. RFC5766).
     */
    GOOGLE_TCP_TURN_RELAYED_CANDIDATE("google tcp turn relayed"),

    /**
     * The candidate is discovered by using JINGLE NODE method (cf. XEP-0278).
     */
    JINGLE_NODE_CANDIDATE("jingle node");

    /**
     * The name of this <tt>CandidateExtendedType</tt> instance.
     */
    private final String extendedTypeName;

    /**
     * Creates a <tt>CandidateExtendedType</tt> instance with the specified
     * name.
     *
     * @param extendedTypeName the name of the <tt>CandidateExtendedType</tt>
     * instance we'd like to create.
     */
    private CandidateExtendedType(String extendedTypeName)
    {
        this.extendedTypeName = extendedTypeName;
    }

    /**
     * Returns the name of this <tt>CandidateExtendedType</tt> (e.g. "host",
     * "upnp", "stun peer reflexive", "stun server reflexive", "turn relayed",
     * "google turn relayed" or "jingle node").
     *
     * @return The name of this <tt>CandidateExtendedType</tt> (e.g. "host",
     * "upnp", "stun peer reflexive", "stun server reflexive", "turn relayed",
     * "google turn relayed", "google tcp turn relayed" or "jingle node").
     */
    @Override
    public String toString()
    {
        return extendedTypeName;
    }

    /**
     * Returns a <tt>CandidateExtendedType</tt> instance corresponding to the
     * specified <tt>extendedTypeName</tt>. For example, for name "host", this
     * method would return {@link #HOST_CANDIDATE}.
     *
     * @param extendedTypeName the name that we'd like to parse.
     *
     * @return a <tt>CandidateExtendedType</tt> instance corresponding to the
     * specified <tt>extendedTypeName</tt>.
     *
     * @throws IllegalArgumentException in case <tt>extendedTypeName</tt> is
     * not a valid or currently supported candidate extended type.
     */
    public static CandidateExtendedType parse(String extendedTypeName)
        throws IllegalArgumentException
    {
        if(HOST_CANDIDATE.toString().equals(extendedTypeName))
            return HOST_CANDIDATE;
        else if(UPNP_CANDIDATE.toString().equals(extendedTypeName))
            return UPNP_CANDIDATE;
        else if(STUN_PEER_REFLEXIVE_CANDIDATE.toString().equals(
                    extendedTypeName))
            return STUN_PEER_REFLEXIVE_CANDIDATE;
        else if(STUN_SERVER_REFLEXIVE_CANDIDATE.toString().equals(
                    extendedTypeName))
            return STUN_SERVER_REFLEXIVE_CANDIDATE;
        else if(TURN_RELAYED_CANDIDATE.toString().equals(extendedTypeName))
            return TURN_RELAYED_CANDIDATE;
        else if(GOOGLE_TURN_RELAYED_CANDIDATE.toString().equals(
                    extendedTypeName))
            return GOOGLE_TURN_RELAYED_CANDIDATE;
        else if(GOOGLE_TCP_TURN_RELAYED_CANDIDATE.toString().equals(
                    extendedTypeName))
            return GOOGLE_TCP_TURN_RELAYED_CANDIDATE;
        else if(JINGLE_NODE_CANDIDATE.toString().equals(extendedTypeName))
            return JINGLE_NODE_CANDIDATE;

        throw new IllegalArgumentException(
            extendedTypeName
            + " is not a currently supported CandidateExtendedType");
    }
}
