/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.security.*;

/**
 * Implements a <tt>CandidateHarvester</tt> which gathers TURN
 * <tt>Candidate</tt>s for a specified {@link Component}.
 *
 * @author Emil Ivov
 * @author Lubomir Marinov
 */
public class TurnCandidateHarvester
    extends StunCandidateHarvester
{

    /**
     * The <tt>LongTermCredential</tt> to be used with the TURN server with
     * which this instance works.
     */
    private final LongTermCredential longTermCredential;

    /**
     * Initializes a new <tt>TurnCandidateHarvester</tt> instance which is to
     * work with a specific TURN server.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server the
     * new instance is to work with
     */
    public TurnCandidateHarvester(TransportAddress turnServer)
    {
        this(turnServer, (LongTermCredential) null);
    }

    /**
     * Initializes a new <tt>TurnCandidateHarvester</tt> instance which is to
     * work with a specific TURN server using a specific
     * <tt>LongTermCredential</tt>.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server the
     * new instance is to work with
     * @param longTermCredential the <tt>LongTermCredential</tt> to use with the
     * specified <tt>turnServer</tt> or <tt>null</tt> if the use of the
     * long-term credential mechanism is not determined at the time of the
     * initialization of the new <tt>TurnCandidateHarvester</tt> instance
     */
    public TurnCandidateHarvester(
            TransportAddress turnServer,
            LongTermCredential longTermCredential)
    {
        super(turnServer);

        this.longTermCredential = longTermCredential;
    }

    /**
     * Initializes a new <tt>TurnCandidateHarvester</tt> instance which is to
     * work with a specific TURN server using a specific username for the
     * purposes of the STUN short-term credential mechanism.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server the
     * new instance is to work with
     * @param shortTermCredentialUsername the username to be used by the new
     * instance for the purposes of the STUN short-term credential mechanism or
     * <tt>null</tt> if the use of the STUN short-term credential mechanism is
     * not determined at the time of the construction of the new instance
     */
    public TurnCandidateHarvester(
            TransportAddress turnServer,
            String shortTermCredentialUsername)
    {
        super(turnServer, shortTermCredentialUsername);

        this.longTermCredential = null;
    }

    /**
     * Creates a new <tt>TurnCandidateHarvest</tt> instance which is to perform
     * TURN harvesting of a specific <tt>HostCandidate</tt>.
     *
     * @param hostCandidate the <tt>HostCandidate</tt> for which harvesting is
     * to be performed by the new <tt>TurnCandidateHarvest</tt> instance
     * @return a new <tt>TurnCandidateHarvest</tt> instance which is to perform
     * TURN harvesting of the specified <tt>hostCandidate</tt>
     * @see StunCandidateHarvester#createHarvest(HostCandidate)
     */
    @Override
    protected TurnCandidateHarvest createHarvest(HostCandidate hostCandidate)
    {
        return new TurnCandidateHarvest(this, hostCandidate);
    }

    /**
     * Creates a <tt>LongTermCredential</tt> to be used by a specific
     * <tt>StunCandidateHarvest</tt> for the purposes of the long-term
     * credential mechanism in a specific <tt>realm</tt> of the TURN server
     * associated with this <tt>TurnCandidateHarvester</tt>. The default
     * implementation returns <tt>null</tt> and allows extenders to override in
     * order to support the long-term credential mechanism.
     *
     * @param harvest the <tt>StunCandidateHarvest</tt> which asks for the
     * <tt>LongTermCredential</tt>
     * @param realm the realm of the TURN server associated with this
     * <tt>TurnCandidateHarvester</tt> in which <tt>harvest</tt> will use the
     * returned <tt>LongTermCredential</tt>
     * @return a <tt>LongTermCredential</tt> to be used by <tt>harvest</tt> for
     * the purposes of the long-term credential mechanism in the specified
     * <tt>realm</tt> of the TURN server associated with this
     * <tt>TurnsCandidateHarvester</tt>
     * @see StunCandidateHarvester#createLongTermCredential(
     * StunCandidateHarvest,byte[])
     */
    @Override
    protected LongTermCredential createLongTermCredential(
            StunCandidateHarvest harvest,
            byte[] realm)
    {
        return longTermCredential;
    }
 }
