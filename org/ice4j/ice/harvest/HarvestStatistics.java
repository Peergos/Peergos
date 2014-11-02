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

/**
 * Manages statistics about harvesting time.
 *
 * @author Vincent Lucas
 * @author Emil Ivov
 */
public class HarvestStatistics
{
    /**
     * The number of harvesting for this harvester.
     */
    private int harvestCount = 0;

    /**
     * The last harvest start time for this harvester. -1 if this harvester is
     * not currently harvesting.
     */
    private long lastStartHarvestingTime = -1;

    /**
     * The last ended harvesting time for this harvester. -1 if this harvester
     * has never harvested yet.
     */
    private long lastHarvestingTime = 0;

    /**
     * The number of non-redundant candidates that this harvester has discovered
     * during all its harvests.
     */
    private int totalCandidateCount = 0;

    /**
     * The name of the harvester associated with these stats.
     */
    protected String harvesterName;

    /**
     * Starts the harvesting timer. Called when the harvest begins.
     */
    protected void startHarvestTiming()
    {
        harvestCount++;
        // Remember the start date of this harvester.
        this.lastStartHarvestingTime = System.currentTimeMillis();
    }

    /**
     * Stops the harvesting timer. Called when the harvest ends.
     *
     * @param harvest the harvest that we just concluded.
     */
    protected void stopHarvestTiming(Collection<LocalCandidate> harvest)
    {
        //count total candidates
        if(harvest != null)
            stopHarvestTiming(harvest.size());
        else
            stopHarvestTiming(0);

    }

    /**
     * Stops the harvesting timer. Called when the harvest ends.
     *
     * @param candidateCount the number of candidates we harvested.
     */
    protected void stopHarvestTiming(int candidateCount)
    {
        // Remember the last harvesting time.
        this.lastHarvestingTime = this.getHarvestDuration();
        // Stops the current timer (must be done after setting the
        // lastHarvestingTime).
        this.lastStartHarvestingTime = -1;

        //count total candidates
        this.totalCandidateCount += candidateCount;
    }

    /**
     * Returns the current harvesting time in ms. If this harvester is not
     * currently harvesting, then returns the value of the last harvesting time.
     * 0 if this harvester has never harvested.
     *
     * @return The current harvesting time in ms.
     */
    public long getHarvestDuration()
    {
        if(this.lastStartHarvestingTime != -1)
        {
            long currentHarvestingTime
                = System.currentTimeMillis() - lastStartHarvestingTime;
            // Retest here, while the harvesting may be end while computing the
            // harvesting time.
            if(this.lastStartHarvestingTime != -1)
            {
                return this.lastHarvestingTime + currentHarvestingTime;
            }
        }
        // If we are ont currently harvesting, then returns the value of the
        // last harvesting time.
        return this.lastHarvestingTime;
    }

    /**
     * Returns the number of candidates gathered  by the associated harvester.
     *
     * @return the total number of candidates gatherer by this harvester.
     */
    public int getTotalCandidateCount()
    {
        return totalCandidateCount;
    }

    /**
     * Returns the number of harvests that the harvester associated with these
     * statistics has completed so far.
     *
     * @return the number of harvests that the associated harvester has engaged
     * in.
     */
    public int getHarvestCount()
    {
        return this.harvestCount;
    }

    /**
     * Specifies the name of the associated harvester.
     *
     * @param harvesterName the name of the associated harvester.
     */
    protected void setName(String harvesterName)
    {
        this.harvesterName = harvesterName;
    }

    /**
     * Returns the name of the associated harvester.
     *
     * @return the name of the associated harvester.
     */
    public String getName()
    {
        return harvesterName;
    }

    /**
     * Returns a string representation of these statistics in a concise format.
     *
     * @return a string representation of these stats.
     */
    @Override
    public String toString()
    {
        return harvesterName
            + ": time="+getHarvestDuration()
            + "ms harvests="+getHarvestCount()
            +" candidates=" + getTotalCandidateCount() ;
    }
}
