/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice;

import org.ice4j.*;
import org.ice4j.socket.*;

/**
 * Peer Reflexive Candidates are candidates whose IP address and port are a
 * binding explicitly allocated by a NAT for an agent when it sent a STUN
 * Binding request through the NAT to its peer.
 * <p>
 * Peer Reflexive Candidates are generally allocated by NATs with endpoint
 * dependent mapping also known as Symmetric NATs. PeerReflexiveCandidates
 * are generally preferred to relayed ones. RFC 5245 explains this with
 * better security ... although simply avoiding a relay would probably be
 * enough of a reason for many.
 *
 * @author Emil Ivov
 */
public class PeerReflexiveCandidate
    extends LocalCandidate
{
    /**
     * Creates a <tt>PeerReflexiveCandidate</tt> instance for the specified
     * transport address and properties.
     *
     * @param transportAddress  the transport address that this candidate is
     * encapsulating.
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     * @param base the base of a peer reflexive candidate base is the local
     * candidate of the candidate pair from which the STUN check was sent.
     * @param priority the priority of the candidate.
     */
    public PeerReflexiveCandidate(TransportAddress transportAddress,
                                  Component        parentComponent,
                                  LocalCandidate   base,
                                  long             priority)
    {
        super(
                transportAddress,
                parentComponent,
                CandidateType.PEER_REFLEXIVE_CANDIDATE,
                CandidateExtendedType.STUN_PEER_REFLEXIVE_CANDIDATE,
                base);
        super.setBase(base);
        super.priority = priority;
    }

    /**
     * Returns the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>.
     *
     * @return the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>
     * @see LocalCandidate#getIceSocketWrapper()
     */
    public IceSocketWrapper getIceSocketWrapper()
    {
        return getBase().getIceSocketWrapper();
    }
}
