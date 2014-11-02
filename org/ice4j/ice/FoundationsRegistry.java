/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import org.ice4j.*;

import java.util.*;

/**
 * We <tt>FoundationsRegistry</tt>s to keep track of and generate new
 * foundations within the lifetime of a single <tt>Agent</tt>.
 *
 * @author Emil Ivov
 */
public class FoundationsRegistry
{
    /**
     * The foundation number that was last assigned to a <tt>Candidate</tt>
     */
    private int lastAssignedFoundation = 0;

    /**
     * The foundation number that was last assigned to a PEER-REFLEXIVE
     * <tt>RemoteCandidate</tt>
     */
    private int lastAssignedRemoteFoundation = 10000;

    /**
     * Contains mappings between a type+baseIP+server+transport
     * <tt>String</tt>s and the foundation that has been assigned to them.
     */
    private Map<String, String> foundations = new Hashtable<String, String>();

    /**
     * Assigns to <tt>candidate</tt> the foundation that corresponds to its
     * base, type and transport properties or a new one if no foundation has
     * been generated yet for the specific combination.
     *
     * @param candidate the <tt>Candidate</tt> that we'd like to assign a
     * foundation to.
     */
    public void assignFoundation(Candidate<?> candidate)
    {
        //create the foundation key String
        CandidateType candidateType = candidate.getType();
        String type = candidateType.toString();
        String base
                  = candidate.getBase().getTransportAddress().getHostAddress();
        String server;

        switch (candidateType)
        {
        case SERVER_REFLEXIVE_CANDIDATE:
            TransportAddress serverAddress = candidate.getStunServerAddress();
            server = (serverAddress == null)
                        ? ""
                        : serverAddress.getHostAddress();
            break;
        case RELAYED_CANDIDATE:
            server = candidate.getRelayServerAddress().getHostAddress();
            break;
        default:
            server = null;
            break;
        }

        String transport = candidate.getTransport().toString();

        StringBuffer foundationStringBuff = new StringBuffer(type);
        foundationStringBuff.append(base);
        if(server != null)
            foundationStringBuff.append(server);

        foundationStringBuff.append(transport);

        String foundationString = foundationStringBuff.toString();

        String foundationValue = null;
        synchronized(foundations)
        {
            foundationValue = foundations.get(foundationString);

            //obtain a new foundation number if we don't have one for this kind
            //of candidates.
            if(foundationValue == null)
            {
                foundationValue = Integer.toString(++lastAssignedFoundation);
                foundations.put(foundationString, foundationValue);
            }
        }

        candidate.setFoundation(foundationValue);
    }

    /**
     * Returns an (as far as you care) random foundation that could be assigned
     * to a learned PEER-REFLEXIVE candidate.
     *
     * @return  a foundation <tt>String</tt> that could be assigned to a
     * learned PEER-REFLEXIVE candidate.
     */
    public String obtainFoundationForPeerReflexiveCandidate()
    {
        return Integer.toString(lastAssignedRemoteFoundation++);
    }

    /**
     * Returns the number of foundation <tt>String</tt>s that are currently
     * tracked by the registry.
     *
     * @return the number of foundation <tt>String</tt>s that are currently
     * tracked by this registry.
     */
    public int size()
    {
        return foundations.size();
    }
}
