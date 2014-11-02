/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import org.ice4j.*;
import org.ice4j.ice.*;

import java.util.*;

/**
 * Uses a list of addresses as a predefined static mask in order to generate
 * {@link TransportAddress}es. This harvester is meant for use in situations
 * where servers are deployed behind a NAT or in a DMZ with static port mapping.
 * <p>
 * Every time the {@link #harvest(Component)} method is called, the mapping
 * harvester will return a list of candidates that provide masked alternatives
 * for every host candidate in the component. Kind of like a STUN server.
 * <p>
 * Example: You run this on a server with address 192.168.0.1, that is behind
 * a NAT with public IP: 93.184.216.119. You allocate a host candidate
 * 192.168.0.1/UDP/5000. This harvester is going to then generate an address
 * 93.184.216.119/UDP/5000
 * <p>
 * This harvester is instant and does not introduce any harvesting latency.
 *
 * @author Emil Ivov
 */
public class MappingCandidateHarvester
    extends CandidateHarvester
{
    /**
     * The addresses that we will use as a mask
     */
    private TransportAddress mask;

    /**
     * The addresses that we will be masking
     */
    private TransportAddress face;

    /**
     * Creates a mapping harvester with the specified <tt>mask</tt>
     *
     * @param mask the <tt>TransportAddress</tt>es that would be used as a mask.
     * @param face the <tt>TransportAddress</tt>es that we will be masking.
     */
    public MappingCandidateHarvester(TransportAddress mask,
                                     TransportAddress face)
    {
        this.mask = mask;
        this.face = face;
    }
    /**
     * Maps all candidates to this harvester's mask and adds them to
     * <tt>component</tt>.
     *
     * @param component the {@link Component} that we'd like to map candidates
     * to.
     * @return  the <tt>LocalCandidate</tt>s gathered by this
     * <tt>CandidateHarvester</tt> or <tt>null</tt> if no mask is specified.
     */
    public Collection<LocalCandidate> harvest(Component component)
    {

        if (mask == null || face == null)
            return null;

        /*
         * Report the LocalCandidates gathered by this CandidateHarvester so
         * that the harvest is sure to be considered successful.
         */
        Collection<LocalCandidate> candidates = new HashSet<LocalCandidate>();

        for (Candidate<?> cand : component.getLocalCandidates())
        {
            if (!(cand instanceof HostCandidate)
                || !cand.getTransportAddress().getHostAddress()
                            .equals(face.getHostAddress())
                || cand.getTransport() != face.getTransport())
            {
                continue;
            }

            HostCandidate hostCandidate = (HostCandidate) cand;
            TransportAddress mappedAddress = new TransportAddress(
                mask.getHostAddress(),
                hostCandidate.getHostAddress().getPort(),
                hostCandidate.getHostAddress().getTransport());

            ServerReflexiveCandidate mappedCandidate
                = new ServerReflexiveCandidate(
                    mappedAddress,
                    hostCandidate,
                    hostCandidate.getStunServerAddress(),
                    CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE);
            if (hostCandidate.isSSL())
                mappedCandidate.setSSL(true);

            //try to add the candidate to the component and then
            //only add it to the harvest not redundant
            if( !candidates.contains(mappedCandidate)
                && component.addLocalCandidate(mappedCandidate))
            {
                candidates.add(mappedCandidate);
            }
        }

        return candidates;
    }
}
