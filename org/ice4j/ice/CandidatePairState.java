/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice;

/**
 * Each candidate pair in the a list has a <tt>CandidatePairState</tt>. It is
 * assigned once the check list for each media stream has been computed. There
 * are five potential values that the state can have and they are all
 * represented by this enumeration.
 *
 * @author Emil Ivov
 */
public enum CandidatePairState
{
    /**
     * Indicates that the candidate pair is in a Waiting state which means that
     * a check has not been performed for this pair, and can be performed as
     * soon as it is the highest priority Waiting pair on the check list.
     */
    WAITING("Waiting"),

    /**
     * Indicates that the candidate pair is in a "In-Progress" state which
     * means that a check has been sent for this pair, but the transaction is
     * in progress.
     */
    IN_PROGRESS("In-Progress"),

    /**
     * Indicates that the candidate pair is in a "Succeeded" state which means
     * that a check for this pair was already done and produced a successful
     * result.
     */
    SUCCEEDED("Succeeded"),

    /**
     * Indicates that the candidate pair is in a "Failed" state which means that
     * a check for this pair was already done and failed, either never producing
     * any response or producing an unrecoverable failure response.
     */
    FAILED("Failed"),

    /**
     * Indicates that the candidate pair is in a "Frozen" state which means that
     * a check for this pair hasn't been performed, and it can't yet be
     * performed until some other check succeeds, allowing this pair to unfreeze
     * and move into the Waiting state.
     */
    FROZEN("Frozen");

    /**
     * The name of this <tt>CandidatePairState</tt> instance.
     */
    private final String stateName;

    /**
     * Creates a <tt>CandidatePairState</tt> instance with the specified name.
     *
     * @param stateName the name of the <tt>CandidatePairState</tt> instance
     * we'd like to create.
     */
    private CandidatePairState(String stateName)
    {
        this.stateName = stateName;
    }

    /**
     * Returns the name of this <tt>CandidatePairStae</tt> (e.g. "In-Progress",
     * "Waiting", "Succeeded", or "Failed").
     *
     * @return the name of this <tt>Transport</tt> (e.g. "Waiting" or
     * "Frozen").
     */
    @Override
    public String toString()
    {
        return stateName;
    }

}
