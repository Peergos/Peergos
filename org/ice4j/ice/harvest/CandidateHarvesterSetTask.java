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
 * Represents a task to be executed by the specified executorService and
 * to call {@link CandidateHarvester#harvest(Component)} on the specified
 * harvesters.
 *
 * @author Lyubomir Marinov
 * @author  Emil Ivov
 */
class CandidateHarvesterSetTask
    implements Runnable
{
    /**
     * The <tt>Logger</tt> used by the <tt>CandidateHarvesterSetTask</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(CandidateHarvesterSetTask.class.getName());

    /**
     * The <tt>CandidateHarvester</tt> on which
     * {@link CandidateHarvester#harvest(org.ice4j.ice.Component)} is to be or
     * is being called.
     */
    private CandidateHarvesterSetElement harvester;

    /**
     * The {@link Component}s whose addresses we will be harvesting in this
     * task.
     */
    private Collection<Component> components;

    /**
     * The callback that we will be notifying every time a harvester completes.
     */
    private final TrickleCallback trickleCallback;

    /**
     * Initializes a new <tt>CandidateHarvesterSetTask</tt> which is to
     * call {@link CandidateHarvester#harvest(org.ice4j.ice.Component)} on a
     * specific harvester and then as many harvesters as possible.
     *
     * @param harvester the <tt>CandidateHarvester</tt> on which the
     * new instance is to call
     * @param components the <tt>Component</tt> whose candidates we are currently
     * gathering.
     * <tt>CandidateHarvester#harvest(Component)</tt> first
     */
    public CandidateHarvesterSetTask(
            CandidateHarvesterSetElement harvester,
            Collection<Component>        components,
            TrickleCallback              trickleCallback)
    {
        this.harvester = harvester;
        this.components = components;
        this.trickleCallback = trickleCallback;
    }

    /**
     * Gets the <tt>CandidateHarvester</tt> on which
     * {@link CandidateHarvester#harvest(org.ice4j.ice.Component)} is being
     * called.
     *
     * @return the <tt>CandidateHarvester</tt> on which
     * <tt>CandidateHarvester#harvest(Component)</tt> is being called
     */
    public CandidateHarvesterSetElement getHarvester()
    {
        return harvester;
    }

    /**
     * Runs the actual harvesting for this component
     */
    public void run()
    {
        if (harvester == null || !harvester.isEnabled())
            return;

        for (Component component : components)
        {
            try
            {
                harvester.harvest(component, trickleCallback);
            }
            catch (Throwable t)
            {
                logger.info(
                    "disabling harvester due to exception: " +
                        t.getLocalizedMessage());
                harvester.setEnabled(false);

                if (t instanceof ThreadDeath)
                    throw (ThreadDeath) t;
            }
        }

        /*
         * CandidateHarvester#harvest(Component) has been called on
         * the harvester and its success or failure has been noted.
         * Now forget the harvester because any failure to continue
         * execution is surely not its fault.
         */
        harvester = null;
    }
}
