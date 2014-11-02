/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.security.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * Implements a <tt>CandidateHarvester</tt> which gathers <tt>Candidate</tt>s
 * for a specified {@link Component} using STUN as defined in RFC 5389 "Session
 * Traversal Utilities for NAT (STUN)" only.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public class StunCandidateHarvester
    extends CandidateHarvester
{

    /**
     * The <tt>Logger</tt> used by the <tt>StunCandidateHarvester</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(StunCandidateHarvester.class.getName());

    /**
     * The list of <tt>StunCandidateHarvest</tt>s which have been successfully
     * completed i.e. have harvested <tt>Candidate</tt>s.
     */
    private final List<StunCandidateHarvest> completedHarvests
        = new LinkedList<StunCandidateHarvest>();

    /**
     * The username used by this <tt>StunCandidateHarvester</tt> for the
     * purposes of the STUN short-term credential mechanism.
     */
    private final String shortTermCredentialUsername;

    /**
     * The list of <tt>StunCandidateHarvest</tt>s which have been started to
     * harvest <tt>Candidate</tt>s for <tt>HostCandidate</tt>s and which have
     * not completed yet so {@link #harvest(Component)} has to wait for them.
     */
    private final List<StunCandidateHarvest> startedHarvests
        = new LinkedList<StunCandidateHarvest>();

    /**
     * The address of the STUN server that we will be sending our requests to.
     */
    public final TransportAddress stunServer;

    /**
     * The <tt>StunStack</tt> used by this instance for the purposes of STUN
     * communication.
     */
    private StunStack stunStack;

    /**
     * Creates a new STUN harvester that will be running against the specified
     * <tt>stunServer</tt> using a specific username for the purposes of the
     * STUN short-term credential mechanism.
     *
     * @param stunServer the address of the STUN server that we will be querying
     * for our public bindings
     */
    public StunCandidateHarvester(TransportAddress stunServer)
    {
        this(stunServer, null);
    }

    /**
     * Creates a new STUN harvester that will be running against the specified
     * <tt>stunServer</tt> using a specific username for the purposes of the
     * STUN short-term credential mechanism.
     *
     * @param stunServer the address of the STUN server that we will be querying
     * for our public bindings
     * @param shortTermCredentialUsername the username to be used by the new
     * instance for the purposes of the STUN short-term credential mechanism or
     * <tt>null</tt> if the use of the STUN short-term credential mechanism is
     * not determined at the time of the construction of the new instance
     */
    public StunCandidateHarvester(
            TransportAddress stunServer,
            String shortTermCredentialUsername)
    {
        this.stunServer = stunServer;
        this.shortTermCredentialUsername = shortTermCredentialUsername;

        //these should be configurable.
        if(System.getProperty(StackProperties.MAX_CTRAN_RETRANS_TIMER) == null)
            System.setProperty(StackProperties.MAX_CTRAN_RETRANS_TIMER, "400");
        if(System.getProperty(StackProperties.MAX_CTRAN_RETRANSMISSIONS) == null)
            System.setProperty(StackProperties.MAX_CTRAN_RETRANSMISSIONS, "3");
    }

    /**
     * Notifies this <tt>StunCandidateHarvester</tt> that a specific
     * <tt>StunCandidateHarvest</tt> has been completed. If the specified
     * harvest has harvested <tt>Candidates</tt>, it is moved from
     * {@link #startedHarvests} to {@link #completedHarvests}. Otherwise, it is
     * just removed from {@link #startedHarvests}.
     *
     * @param harvest the <tt>StunCandidateHarvest</tt> which has been completed
     */
    void completedResolvingCandidate(StunCandidateHarvest harvest)
    {
        boolean doNotify = false;

        synchronized (startedHarvests)
        {
            startedHarvests.remove(harvest);

            // If this was the last candidate, we are done with the STUN
            // resolution and need to notify the waiters.
            if (startedHarvests.isEmpty())
                doNotify = true;
        }

        synchronized (completedHarvests)
        {
            if (harvest.getCandidateCount() < 1)
                completedHarvests.remove(harvest);
            else if (!completedHarvests.contains(harvest))
                completedHarvests.add(harvest);
        }

        synchronized(startedHarvests)
        {
            if(doNotify)
                startedHarvests.notify();
        }
    }

    /**
     * Creates a new <tt>StunCandidateHarvest</tt> instance which is to perform
     * STUN harvesting of a specific <tt>HostCandidate</tt>.
     *
     * @param hostCandidate the <tt>HostCandidate</tt> for which harvesting is
     * to be performed by the new <tt>StunCandidateHarvest</tt> instance
     * @return a new <tt>StunCandidateHarvest</tt> instance which is to perform
     * STUN harvesting of the specified <tt>hostCandidate</tt>
     */
    protected StunCandidateHarvest createHarvest(HostCandidate hostCandidate)
    {
        return new StunCandidateHarvest(this, hostCandidate);
    }

    /**
     * Creates a <tt>LongTermCredential</tt> to be used by a specific
     * <tt>StunCandidateHarvest</tt> for the purposes of the long-term
     * credential mechanism in a specific <tt>realm</tt> of the STUN server
     * associated with this <tt>StunCandidateHarvester</tt>. The default
     * implementation returns <tt>null</tt> and allows extenders to override in
     * order to support the long-term credential mechanism.
     *
     * @param harvest the <tt>StunCandidateHarvest</tt> which asks for the
     * <tt>LongTermCredential</tt>
     * @param realm the realm of the STUN server associated with this
     * <tt>StunCandidateHarvester</tt> in which <tt>harvest</tt> will use the
     * returned <tt>LongTermCredential</tt>
     * @return a <tt>LongTermCredential</tt> to be used by <tt>harvest</tt> for
     * the purposes of the long-term credential mechanism in the specified
     * <tt>realm</tt> of the STUN server associated with this
     * <tt>StunCandidateHarvester</tt>
     */
    protected LongTermCredential createLongTermCredential(
            StunCandidateHarvest harvest,
            byte[] realm)
    {
        // The long-term credential mechanism is not utilized by default.
        return null;
    }

    /**
     * Gets the username to be used by this <tt>StunCandidateHarvester</tt> for
     * the purposes of the STUN short-term credential mechanism.
     *
     * @return the username to be used by this <tt>StunCandidateHarvester</tt>
     * for the purposes of the STUN short-term credential mechanism or
     * <tt>null</tt> if the STUN short-term credential mechanism is not to be
     * utilized
     */
    protected String getShortTermCredentialUsername()
    {
        return shortTermCredentialUsername;
    }

    /**
     * Gets the <tt>StunStack</tt> used by this <tt>CandidateHarvester</tt> for
     * the purposes of STUN communication. It is guaranteed to be available only
     * during the execution of {@link CandidateHarvester#harvest(Component)}.
     *
     * @return the <tt>StunStack</tt> used by this <tt>CandidateHarvester</tt>
     * for the purposes of STUN communication
     * @see CandidateHarvester#harvest(Component)
     */
    public StunStack getStunStack()
    {
        return stunStack;
    }

    /**
     * Gathers STUN candidates for all host <tt>Candidate</tt>s that are already
     * present in the specified <tt>component</tt>. This method relies on the
     * specified <tt>component</tt> to already contain all its host candidates
     * so that it would resolve them.
     *
     * @param component the {@link Component} that we'd like to gather candidate
     * STUN <tt>Candidate</tt>s for
     * @return  the <tt>LocalCandidate</tt>s gathered by this
     * <tt>CandidateHarvester</tt>
     */
    public Collection<LocalCandidate> harvest(Component component)
    {
        logger.fine("starting " + component.toShortString()
            + " harvest for: " + toString() );
        stunStack = component.getParentStream().getParentAgent().getStunStack();

        for (Candidate<?> cand : component.getLocalCandidates())
        {
            if ((cand instanceof HostCandidate)
                    && (cand.getTransport() == stunServer.getTransport()))
            {
                startResolvingCandidate((HostCandidate) cand);
            }
        }

        waitForResolutionEnd();

        /*
         * Report the LocalCandidates gathered by this CandidateHarvester so
         * that the harvest is sure to be considered successful.
         */
        Collection<LocalCandidate> candidates = new HashSet<LocalCandidate>();

        synchronized (completedHarvests)
        {
            for (StunCandidateHarvest completedHarvest : completedHarvests)
            {
                LocalCandidate[] completedHarvestCandidates
                    = completedHarvest.getCandidates();

                if ((completedHarvestCandidates != null)
                        && (completedHarvestCandidates.length != 0))
                {
                    candidates.addAll(
                            Arrays.asList(completedHarvestCandidates));
                }
            }

            completedHarvests.clear();
        }

        logger.finest(
                "Completed " + component.toShortString() + " harvest: "
                    + toString() + ". Found " + candidates.size()
                    + " candidates: " + listCandidates(candidates));

        return candidates;
    }

    private String listCandidates(Collection<? extends Candidate<?>> candidates)
    {
        StringBuilder retval = new StringBuilder();
        for(Candidate<?> candidate : candidates)
        {
            retval.append(candidate.toShortString());
        }
        return retval.toString();
    }

    /**
     * Sends a binding request to our stun server through the specified
     * <tt>hostCand</tt> candidate and adds it to the list of addresses still
     * waiting for resolution.
     *
     * @param hostCand the <tt>HostCandidate</tt> that we'd like to resolve.
     */
    private void startResolvingCandidate(HostCandidate hostCand)
    {
        //first of all, make sure that the STUN server and the Candidate
        //address are of the same type and that they can communicate.
        if (!hostCand.getTransportAddress().canReach(stunServer))
            return;

        HostCandidate cand = getHostCandidate(hostCand);

        if(cand == null)
        {
            logger.info(
                    "server/candidate address type mismatch,"
                        + " skipping candidate in this harvester");
            return;
        }

        StunCandidateHarvest harvest = createHarvest(cand);

        if (harvest == null)
        {
            logger.warning("failed to create harvest");
            return;
        }

        synchronized (startedHarvests)
        {
            startedHarvests.add(harvest);

            boolean started = false;

            try
            {
                started = harvest.startResolvingCandidate();
            }
            catch (Exception ex)
            {
                started = false;
                if (logger.isLoggable(Level.INFO))
                {
                    logger.log(
                            Level.INFO,
                            "Failed to start resolving host candidate "
                                + hostCand,
                            ex);
                }
            }
            finally
            {
                if (!started)
                {
                    try
                    {
                        startedHarvests.remove(harvest);
                        logger.warning(
                                "harvest did not start, removed: " + harvest);
                    }
                    finally
                    {
                        /*
                         * For the sake of completeness, explicitly close the
                         * harvest.
                         */
                        try
                        {
                            harvest.close();
                        }
                        catch (Exception ex)
                        {
                        }
                    }
                }
            }
        }
    }

    /**
     * Blocks the current thread until all resolutions in this harvester
     * have terminated one way or another.
     */
    private void waitForResolutionEnd()
    {
        synchronized(startedHarvests)
        {
            boolean interrupted = false;

            // Handle spurious wakeups.
            while (!startedHarvests.isEmpty())
            {
                try
                {
                    startedHarvests.wait();
                }
                catch (InterruptedException iex)
                {
                    logger.info(
                            "interrupted waiting for harvests to complete,"
                                + " no. startedHarvests = "
                                + startedHarvests.size());
                    interrupted = true;
                }
            }
            // Restore the interrupted status.
            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns a <tt>String</tt> representation of this harvester containing its
     * type and server address.
     *
     * @return a <tt>String</tt> representation of this harvester containing its
     * type and server address.
     */
    @Override
    public String toString()
    {
        String proto = (this instanceof TurnCandidateHarvester)
                                ? "TURN"
                                : "STUN";
        return proto + " harvester(srvr: " + this.stunServer + ")";
    }

    /**
     * Returns the host candidate.
     * For UDP it simply returns the candidate passed as a parameter.
     *
     * However for TCP, we cannot return the same hostCandidate because in Java
     * a  "server" socket cannot connect to a destination with the same local
     * address/port (i.e. a Java Socket cannot act as both server/client).
     *
     * @param hostCand HostCandidate
     * @return HostCandidate
     */
    protected HostCandidate getHostCandidate(HostCandidate hostCand)
    {
        HostCandidate cand = null;

        // create a new TCP HostCandidate
        if(hostCand.getTransport() == Transport.TCP)
        {
            try
            {
                Socket sock = new Socket(stunServer.getAddress(),
                    stunServer.getPort());
                cand = new HostCandidate(new IceTcpSocketWrapper(
                    new MultiplexingSocket(sock)),
                    hostCand.getParentComponent(), Transport.TCP);
                hostCand.getParentComponent().getParentStream().
                    getParentAgent().getStunStack().addSocket(
                        cand.getStunSocket(null));
            }
            catch(Exception io)
            {
                logger.info("Exception TCP client connect: " + io);
                return null;
            }
        }
        else
        {
            cand = hostCand;
        }

        return cand;
    }
}
