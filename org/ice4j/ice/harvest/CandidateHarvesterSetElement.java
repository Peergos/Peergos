/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import org.ice4j.ice.*;

import java.util.*;
import java.util.logging.*;

/**
 * Represents a <tt>CandidateHarvester</tt> as an element in a
 * <tt>CandidateHarvesterSet</tt>.
 *
 * @author Lyubomir Marinov
 * @author Emil Ivov
 */
class CandidateHarvesterSetElement
{
    /**
     * The <tt>Logger</tt> used by the <tt>CandidateHarvesterSetElement</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(CandidateHarvesterSetElement.class.getName());

    /**
     * The indicator which determines whether
     * {@link CandidateHarvester#harvest(org.ice4j.ice.Component)} is to be
     * called on {@link #harvester}.
     */
    private boolean enabled = true;

    /**
     * The <tt>CandidateHarvester</tt> which is an element in a
     * <tt>CandidateHarvesterSet</tt>.
     */
    private final CandidateHarvester harvester;

    /**
     * Initializes a new <tt>CandidateHarvesterSetElement</tt> instance
     * which is to represent a specific <tt>CandidateHarvester</tt> as an
     * element in a <tt>CandidateHarvesterSet</tt>.
     *
     * @param harvester the <tt>CandidateHarvester</tt> which is to be
     * represented as an element in a <tt>CandidateHarvesterSet</tt> by the
     * new instance
     */
    public CandidateHarvesterSetElement(CandidateHarvester harvester)
    {
        this.harvester = harvester;
        harvester.getHarvestStatistics().harvesterName = harvester.toString();
    }

    /**
     * Calls {@link CandidateHarvester#harvest(org.ice4j.ice.Component)} on the
     * associated <tt>CandidateHarvester</tt> if <tt>enabled</tt>.
     *
     * @param component the <tt>Component</tt> to gather candidates for
     * @param trickleCallback the {@link TrickleCallback} that we will be
     * feeding candidates to, or <tt>null</tt> in case the application doesn't
     * want us trickling any candidates
     */
    public void harvest(Component       component,
                        TrickleCallback trickleCallback)
    {
        if (!isEnabled())
            return;

        startHarvestTiming();

        Collection<LocalCandidate> candidates = harvester.harvest(component);

        stopHarvestTiming(candidates);

        /*
         * If the CandidateHarvester has not gathered any candidates, it
         * is considered failed and will not be used again in order to
         * not risk it slowing down the overall harvesting.
         */
        if ((candidates == null) || candidates.isEmpty())
        {
            setEnabled(false);
        }
        else if(trickleCallback != null)
        {
            trickleCallback.onIceCandidates(candidates);
        }

    }

    /**
     * Determines whether the associated <tt>CandidateHarvester</tt> is
     * considered to be the same as a specific <tt>CandidateHarvester</tt>.
     *
     * @param harvester the <tt>CandidateHarvester</tt> to be compared to
     * the associated <tt>CandidateHarvester</tt>
     * @return <tt>true</tt> if the associated <tt>CandidateHarvester</tt>
     * is considered to be the same as the specified <tt>harvester</tt>;
     * otherwise, <tt>false</tt>
     */
    public boolean harvesterEquals(CandidateHarvester harvester)
    {
        return this.harvester.equals(harvester);
    }

    /**
     * Gets the indicator which determines whether
     * {@link CandidateHarvester#harvest(Component)} is to be called on the
     * associated <tt>CandidateHarvester</tt>.
     *
     * @return <tt>true</tt> if
     * <tt>CandidateHarvester#harvest(Component)</tt> is to be called on the
     * associated <tt>CandidateHarvester</tt>; otherwise, <tt>false</tt>
     */
    public boolean isEnabled()
    {
        return enabled;
    }

    /**
     * Sets the indicator which determines whether
     * {@link CandidateHarvester#harvest(Component)} is to be called on the
     * associated <tt>CandidateHarvester</tt>.
     *
     * @param enabled <tt>true</tt> if
     * <tt>CandidateHarvester#harvest(Component)</tt> is to be called on the
     * associated <tt>CandidateHarvester</tt>; otherwise, <tt>false</tt>
     */
    public void setEnabled(boolean enabled)
    {
        logger.info((enabled ? "Enabling: " : "Disabling: ") + harvester);
        this.enabled = enabled;
    }

    /**
     * Returns the <tt>CandidateHarvester</tt> encapsulated by this element.
     *
     * @return the <tt>CandidateHarvester</tt> encapsulated by this element.
     */
    public CandidateHarvester getHarvester()
    {
        return harvester;
    }

    /**
     * Starts the harvesting timer. Called when the harvest begins.
     */
    private void startHarvestTiming()
    {
        harvester.getHarvestStatistics().startHarvestTiming();
    }

    /**
     * Stops the harvesting timer. Called when the harvest ends.
     *
     * @param harvest the harvest that we just concluded.
     */
    private void stopHarvestTiming(Collection<LocalCandidate> harvest)
    {
        harvester.getHarvestStatistics().stopHarvestTiming(harvest);
    }
}
