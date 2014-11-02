/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.socket.*;

/**
 * A component is a piece of a media stream requiring a single transport
 * address; a media stream may require multiple components, each of which has
 * to work for the media stream as a whole to work. For media streams based on
 * RTP, there are two components per media stream - one for RTP, and one for
 * RTCP.
 * <p>
 *
 * @author Emil Ivov
 * @author Sebastien Vincent
 * @author Boris Grozev
 */
public class Component
{
    /**
     * Our class logger.
     */
    private static final Logger logger
        = Logger.getLogger(Component.class.getName());

    /**
     * The component ID to use with RTP streams.
     */
    public static final int RTP = 1;

    /**
     * The component ID to use with RTCP streams.
     */
    public static final int RTCP = 2;

    /**
     * A component id is a positive integer between 1 and 256 which identifies
     * the specific component of the media stream for which this is a candidate.
     * It MUST start at 1 and MUST increment by 1 for each component of a
     * particular candidate. For media streams based on RTP, candidates for the
     * actual RTP media MUST have a component ID of 1, and candidates for RTCP
     * MUST have a component ID of 2. Other types of media streams which
     * require multiple components MUST develop specifications which define the
     * mapping of components to component IDs. See Section 14 of RFC5245 for
     * additional discussion on extending ICE to new media streams.
     */
    private final int componentID;

    /**
     * The <tt>IceMediaStream</tt> that this <tt>Component</tt> belongs to.
     */
    private final IceMediaStream parentStream;

    /**
     * The list locally gathered candidates for this media stream.
     */
    private final List<LocalCandidate> localCandidates
        = new LinkedList<LocalCandidate>();

    /**
     * The list of candidates that the peer agent sent for this stream.
     */
    private final List<RemoteCandidate> remoteCandidates
        = new LinkedList<RemoteCandidate>();

    /**
     * The list of candidates that the peer agent sent for this stream after
     * connectivity establishment.
     */
    private final List<RemoteCandidate> remoteUpdateCandidates =
        new LinkedList<RemoteCandidate>();

    /**
     * A <tt>Comparator</tt> that we use for sorting <tt>Candidate</tt>s by
     * their priority.
     */
    private final CandidatePrioritizer candidatePrioritizer
        = new CandidatePrioritizer();

    /**
     * The default <tt>Candidate</tt> for this component or in other words, the
     * candidate that we would have used without ICE.
     */
    private LocalCandidate defaultCandidate = null;

    /**
     * The pair that has been selected for use by ICE processing
     */
    private CandidatePair selectedPair;

    /**
     * The default <tt>RemoteCandidate</tt> for this component or in other
     * words, the candidate that we would have used to communicate with the
     * remote peer if we hadn't been using ICE.
     */
    private Candidate<?> defaultRemoteCandidate = null;

    /**
     * Creates a new <tt>Component</tt> with the specified <tt>componentID</tt>
     * as a child of the specified <tt>IceMediaStream</tt>.
     *
     * @param componentID the id of this component.
     * @param mediaStream the {@link IceMediaStream} instance that would be the
     * parent of this component.
     */
    protected Component(int            componentID,
                        IceMediaStream mediaStream)
    {
        // the max value for componentID is 256
        this.componentID = componentID;
        this.parentStream = mediaStream;
    }

    /**
     * Add a local candidate to this component. The method should only be
     * accessed and local candidates added by the candidate harvesters
     * registered with the agent.
     *
     * @param candidate the candidate object to be added
     *
     * @return <tt>true</tt> if we actually added the new candidate or
     * <tt>false</tt> in case we didn't because it was redundant to an existing
     * candidate.
     */
    public boolean addLocalCandidate(LocalCandidate candidate)
    {
        Agent agent = getParentStream().getParentAgent();

        //assign foundation.
        agent.getFoundationsRegistry().assignFoundation(candidate);

        //compute priority
        candidate.computePriority();

        synchronized(localCandidates)
        {
            //check if we already have such a candidate (redundant)
            LocalCandidate redundantCandidate = findRedundant(candidate);

            if (redundantCandidate != null)
            {
                //if we get here, then it's clear we won't be adding anything
                //we will just update something at best. We purposefully don't
                //care about priorities because allowing candidate replace is
                //tricky to handle on the signalling layer with trickle
                return false;
            }

            localCandidates.add(candidate);

            //we are done adding ... now let's just order by priority.
            Collections.sort(localCandidates);

            return true;
        }
    }

    /**
     * Returns a copy of the list containing all local candidates currently
     * registered in this component.
     *
     * @return Returns a copy of the list containing all local candidates
     * currently registered in this <tt>Component</tt>.
     */
    public List<LocalCandidate> getLocalCandidates()
    {
        synchronized(localCandidates)
        {
            return new ArrayList<LocalCandidate>(localCandidates);
        }
    }

    /**
     * Returns the number of local host candidates currently registered in this
     * <tt>Component</tt>.
     *
     * @return the number of local host candidates currently registered in this
     * <tt>Component</tt>.
     */
    public int countLocalHostCandidates()
    {
        synchronized(localCandidates)
        {
            int count = 0;
            for(Candidate<?> cand : localCandidates)
            {
                if ((cand.getType() == CandidateType.HOST_CANDIDATE)
                        && !cand.isVirtual())
                {
                    count++;
                }
            }

            return count;
        }
    }

    /**
     * Returns the number of all local candidates currently registered in this
     * <tt>Component</tt>.
     *
     * @return the number of all local candidates currently registered in this
     * <tt>Component</tt>.
     */
    public int getLocalCandidateCount()
    {
        synchronized(localCandidates)
        {
            return localCandidates.size();
        }
    }

    /**
     * Adds a remote <tt>Candidate</tt>s to this media-stream
     * <tt>Component</tt>.
     *
     * @param candidate the <tt>Candidate</tt> instance to add.
     */
    public void addRemoteCandidate(RemoteCandidate candidate)
    {
        logger.info("Add remote candidate for " + toShortString()
                            + ": " + candidate.toShortString());

        synchronized(remoteCandidates)
        {
            remoteCandidates.add(candidate);
        }
    }

    /**
     * Update the media-stream <tt>Component</tt> with the specified
     * <tt>Candidate</tt>s. This would happen when performing trickle ICE.
     *
     * @param candidate new <tt>Candidate</tt> to add.
     */
    public void addUpdateRemoteCandidates(RemoteCandidate candidate)
    {
        logger.info("Update remote candidate for " + toShortString() + ": " +
                candidate.getTransportAddress());

        List<RemoteCandidate> existingCandidates
                = new LinkedList<RemoteCandidate>();
        synchronized (remoteCandidates)
        {
            existingCandidates.addAll(remoteCandidates);
        }

        synchronized(remoteUpdateCandidates)
        {
            existingCandidates.addAll(remoteUpdateCandidates);

            // Make sure we add no duplicates
            TransportAddress transportAddress = candidate.getTransportAddress();
            CandidateType type = candidate.getType();
            for (RemoteCandidate existingCandidate : existingCandidates)
            {
                if (transportAddress
                        .equals(existingCandidate.getTransportAddress())
                    && type == existingCandidate.getType())
                {
                    logger.info("Not adding duplicate remote candidate: "
                                    + candidate.getTransportAddress());
                    return;
                }
            }

            remoteUpdateCandidates.add(candidate);
        }
    }

    /**
     * Creates a local TCP candidate for use with a specific remote GTalk TCP
     * candidate.
     *
     * @param remoteCandidate the GTalk TCP candidate that we'd like to connect
     * to.
     * @param localCandidate the local candidate that we are pairing
     * remoteCandidate with and that we should use as a source for the ice
     * ufrag and password.
     *
     * @return the newly created {@link LocalCandidate}
     */
    private LocalCandidate createLocalTcpCandidate4GTalk(
                                           RemoteCandidate remoteCandidate,
                                           LocalCandidate  localCandidate)
    {
        MultiplexingSocket sock = new MultiplexingSocket();
        try
        {
            // if we use proxy (socks5, ...), the connect() timeout
            // may not be respected
            int timeout = sock.getSoTimeout();
            sock.setSoTimeout(1000);
            sock.connect(new InetSocketAddress(
                remoteCandidate.getTransportAddress().getAddress(),
                remoteCandidate.getTransportAddress().getPort()), 1000);
            sock.setSoTimeout(timeout);
        }
        catch(Exception e)
        {
            logger.info(
                    "Failed to connect to "
                        + remoteCandidate.getTransportAddress());
            try
            {
                sock.close();
            }
            catch (IOException ioex)
            {
            }
            return null;
        }

        try
        {
            if(remoteCandidate.getTransportAddress().getPort() == 443)
            {
                //SSL/TCP handshake
                OutputStream outputStream =
                    sock.getOriginalOutputStream();
                InputStream inputStream = sock.getOriginalInputStream();

                if(!GoogleTurnSSLCandidateHarvester.sslHandshake(
                    inputStream, outputStream))
                {
                    logger.info("Failed to connect to SSL/TCP relay");
                    return null;
                }
            }

            LocalCandidate newLocalCandidate =
                new HostCandidate(new IceTcpSocketWrapper(sock),
                    this, Transport.TCP);
            parentStream.getParentAgent().getStunStack().addSocket(
                newLocalCandidate.getStunSocket(null));
            newLocalCandidate.setUfrag(localCandidate.getUfrag());
            return newLocalCandidate;
        }
        catch(IOException ioe)
        {
            logger.log(Level.INFO, "Failed to connect to SSL/TCP relay", ioe);
            return null;
        }
    }

    /**
     * Update ICE processing with new <tt>Candidate</tt>s.
     */
    public void updateRemoteCandidates()
    {
        List<CandidatePair> checkList = null;
        List<RemoteCandidate> newRemoteCandidates;

        synchronized(remoteUpdateCandidates)
        {
            if(remoteUpdateCandidates.size() == 0)
                return;

            newRemoteCandidates
                    = new LinkedList<RemoteCandidate>(remoteUpdateCandidates);

            List<LocalCandidate> localCnds = getLocalCandidates();

            // remove UPnP base from local candidate
            LocalCandidate upnpBase = null;
            for(LocalCandidate lc : localCnds)
            {
                if(lc instanceof UPNPCandidate)
                {
                    upnpBase = lc.getBase();
                }
            }

            checkList = new Vector<CandidatePair>();

            for(LocalCandidate localCnd : localCnds)
            {
                if(localCnd == upnpBase)
                    continue;

                //pair each of the new remote candidates with each of our locals
                for(RemoteCandidate remoteCnd : remoteUpdateCandidates)
                {
                    if(localCnd.canReach(remoteCnd)
                            && remoteCnd.getTransportAddress().getPort() != 0)
                    {
                        // A single LocalCandidate might be/become connected
                        // to more more than one remote address, and that's ok
                        // (that is, we need to form pairs with them all).
                        /*
                        if(localCnd.getTransport() == Transport.TCP &&
                            localCnd.getIceSocketWrapper().getTCPSocket().
                                isConnected())
                        {
                            if(!localCnd.getIceSocketWrapper().getTCPSocket().
                                getRemoteSocketAddress().equals(
                                    remoteCnd.getTransportAddress()))
                            {
                                continue;
                            }
                        }
                        */

                        CandidatePair pair
                            = new CandidatePair(localCnd, remoteCnd);
                        logger.info("new Pair added: " + pair.toShortString());
                        checkList.add(pair);
                    }
                }
            }
            remoteUpdateCandidates.clear();
        }

        synchronized (remoteCandidates)
        {
            remoteCandidates.addAll(newRemoteCandidates);
        }

        //sort and prune update checklist
        Collections.sort(checkList, CandidatePair.comparator);
        parentStream.pruneCheckList(checkList);

        if(parentStream.getCheckList().getState().equals(
                CheckListState.RUNNING))
        {
            //add the updated CandidatePair list to the currently running
            //checklist
            CheckList streamCheckList = parentStream.getCheckList();
            synchronized(streamCheckList)
            {
                for(CandidatePair pair : checkList)
                {
                    streamCheckList.add(pair);
                }
            }
        }
    }

    /**
     * Returns a copy of the list containing all remote candidates currently
     * registered in this component.
     *
     * @return Returns a copy of the list containing all remote candidates
     * currently registered in this <tt>Component</tt>.
     */
    public List<RemoteCandidate> getRemoteCandidates()
    {
        synchronized(remoteCandidates)
        {
            return new ArrayList<RemoteCandidate>(remoteCandidates);
        }
    }

    /**
     * Adds a List of remote <tt>Candidate</tt>s as reported by a remote agent.
     *
     * @param candidates the <tt>List</tt> of <tt>Candidate</tt>s reported by
     * the remote agent for this component.
     */
    public void addRemoteCandidates(List<RemoteCandidate> candidates)
    {
        synchronized(remoteCandidates)
        {
            remoteCandidates.addAll(candidates);
        }
    }

    /**
     * Returns the number of all remote candidates currently registered in this
     * <tt>Component</tt>.
     *
     * @return the number of all remote candidates currently registered in this
     * <tt>Component</tt>.
     */
    public int getRemoteCandidateCount()
    {
        synchronized(remoteCandidates)
        {
            return remoteCandidates.size();
        }
    }

    /**
     * Returns a reference to the <tt>IceMediaStream</tt> that this
     * <tt>Component</tt> belongs to.
     *
     * @return  a reference to the <tt>IceMediaStream</tt> that this
     * <tt>Component</tt> belongs to.
     */
    public IceMediaStream getParentStream()
    {
        return parentStream;
    }

    /**
     * Returns the ID of this <tt>Component</tt>. For RTP/RTCP flows this would
     * be <tt>1</tt> for RTP and 2 for <tt>RTCP</tt>.
     *
     * @return the ID of this <tt>Component</tt>.
     */
    public int getComponentID()
    {
        return componentID;
    }

    /**
     * Returns a <tt>String</tt> representation of this <tt>Component</tt>
     * containing its ID, parent stream name and any existing candidates.
     *
     * @return  a <tt>String</tt> representation of this <tt>Component</tt>
     * containing its ID, parent stream name and any existing candidates.
     */
    public String toString()
    {
        StringBuffer buff
            = new StringBuffer("Component id=").append(getComponentID());

        buff.append(" parent stream=" + getParentStream().getName());

        //local candidates
        int localCandidatesCount = getLocalCandidateCount();

        if(localCandidatesCount > 0)
        {
            buff.append("\n" + localCandidatesCount + " Local candidates:");
            buff.append("\ndefault candidate: " + getDefaultCandidate());

            synchronized(localCandidates)
            {
                for (Candidate<?> cand : localCandidates)
                {
                    buff.append('\n').append(cand.toString());
                }
            }
        }
        else
        {
            buff.append("\nno local candidates.");
        }

        //remote candidates
        int remoteCandidatesCount = getRemoteCandidateCount();

        if(remoteCandidatesCount > 0)
        {
            buff.append("\n" + remoteCandidatesCount + " Remote candidates:");
            buff.append("\ndefault remote candidate: "
                                + getDefaultRemoteCandidate());
            synchronized(remoteCandidates)
            {
                for (RemoteCandidate cand : remoteCandidates)
                {
                    buff.append("\n" + cand.toString());
                }
            }
        }
        else
        {
            buff.append("\nno remote candidates.");
        }

        return buff.toString();
    }

    /**
     * Returns a short <tt>String</tt> representation of this
     * <tt>Component</tt>.
     *
     * @return a short <tt>String</tt> representation of this
     * <tt>Component</tt>.
     */
    public String toShortString()
    {
        return parentStream.getName() + "." + getName();
    }

    /**
     * Computes the priorities of all <tt>Candidate</tt>s and then sorts them
     * accordingly.
     *
     * @deprecated candidates are now being prioritized upon addition and
     * calling this method is no longer necessary.
     */
    @Deprecated
    protected void prioritizeCandidates()
    {
        synchronized(localCandidates)
        {
            LocalCandidate[] candidates
                = new LocalCandidate[localCandidates.size()];

            localCandidates.toArray(candidates);

            //first compute the actual priorities
            for (Candidate<?> cand : candidates)
            {
                cand.computePriority();
            }

            //sort
            Arrays.sort(candidates, candidatePrioritizer);

            //now re-add the candidates in the order they've been sorted in.
            localCandidates.clear();
            for (LocalCandidate cand : candidates)
                localCandidates.add(cand);
        }
    }

    /**
     * Eliminates redundant candidates, removing them from the specified
     * <tt>component</tt>.  A candidate is redundant if its transport address
     * equals another candidate, and its base equals the base of that other
     * candidate.  Note that two candidates can have the same transport address
     * yet have different bases, and these would not be considered redundant.
     * Frequently, a server reflexive candidate and a host candidate will be
     * redundant when the agent is not behind a NAT.  The agent SHOULD eliminate
     * the redundant candidate with the lower priority which is why we have to
     * run this method only after prioritizing candidates.
     *
     * @deprecated redundancies are now being detected upon addition of
     * candidates and calling this method is no longer necessary.
     */
    @Deprecated
    protected void eliminateRedundantCandidates()
    {
        /*
         * Find and remove all candidates that have the same address and base as
         * cand and a lower priority. The algorithm implemented bellow does rely
         * on localCandidates being ordered in decreasing order (as said in its
         * javadoc that the eliminateRedundantCandidates method is called only
         * after prioritizeCandidates.
         */
        synchronized (localCandidates)
        {
            for (int i = 0; i < localCandidates.size(); i++)
            {
                LocalCandidate cand = localCandidates.get(i);

                for (int j = i + 1; j < localCandidates.size();)
                {
                    LocalCandidate cand2 = localCandidates.get(j);

                    if ((cand != cand2)
                            && cand.getTransportAddress().equals(
                                    cand2.getTransportAddress())
                            && cand.getBase().equals(cand2.getBase())
                            && (cand.getPriority() >= cand2.getPriority()))
                    {
                        localCandidates.remove(j);
                        if(logger.isLoggable(Level.FINEST))
                        {
                            logger.finest(
                                    "eliminating redundant cand: "+ cand2);
                        }
                    }
                    else
                        j++;
                }
            }
        }
    }

    /**
     * Finds and returns the first candidate that is redundant to <tt>cand</tt>.
     * A candidate is redundant if its transport address equals another
     * candidate, and its base equals the base of that other candidate. Note
     * that two candidates can have the same transport address yet have
     * different bases, and these would not be considered redundant. Frequently,
     * a server reflexive candidate and a host candidate will be redundant when
     * the agent is not behind a NAT. The agent SHOULD eliminate the redundant
     * candidate with the lower priority which is why we have to run this method
     * only after prioritizing candidates.
     *
     * @param cand the {@link LocalCandidate} that we'd like to check for
     * redundancies.
     *
     * @return the first candidate that is redundant to <tt>cand</tt> or
     * <tt>null</tt> if there is no such candidate.
     */
    private LocalCandidate findRedundant(LocalCandidate cand)
    {
        synchronized (localCandidates)
        {
            for (LocalCandidate redundantCand : localCandidates)
            {
                if ((cand != redundantCand)
                        && cand.getTransportAddress().equals(
                                redundantCand.getTransportAddress())
                        && cand.getBase().equals(redundantCand.getBase()))
                {
                    return redundantCand;
                }
            }
        }

        return null;
    }

    /**
     * Returns the <tt>Candidate</tt> that has been selected as the default
     * for this <tt>Component</tt> or <tt>null</tt> if no such
     * <tt>Candidate</tt> has been selected yet. A candidate is said to be
     * default if it would be the target of media from a non-ICE peer;
     *
     * @return the <tt>Candidate</tt> that has been selected as the default for
     * this <tt>Component</tt> or <tt>null</tt> if no such <tt>Candidate</tt>
     * has been selected yet
     */
    public LocalCandidate getDefaultCandidate()
    {
        return defaultCandidate;
    }

    /**
     * Returns the <tt>Candidate</tt> that the remote party has reported as
     * default for this <tt>Component</tt> or <tt>null</tt> if no such
     * <tt>Candidate</tt> has been reported yet. A candidate is said to be
     * default if it would be the target of media from a non-ICE peer;
     *
     * @return the <tt>Candidate</tt> that the remote party has reported as
     * default for this <tt>Component</tt> or <tt>null</tt> if no such
     * <tt>Candidate</tt> has reported yet.
     */
    public Candidate<?> getDefaultRemoteCandidate()
    {
        return defaultRemoteCandidate;
    }

    /**
     * Sets the <tt>Candidate</tt> that the remote party has reported as
     * default for this <tt>Component</tt>. A candidate is said to be
     * default if it would be the target of media from a non-ICE peer;
     *
     * @param candidate the <tt>Candidate</tt> that the remote party has
     * reported as default for this <tt>Component</tt>.
     */
    public void setDefaultRemoteCandidate(Candidate<?> candidate)
    {
        this.defaultRemoteCandidate = candidate;
    }

    /**
     * Selects a <tt>Candidate</tt> that should be considered as the default
     * for this <tt>Component</tt>. A candidate is said to be default if it
     * would be the target of media from a non-ICE peer;
     * <p>
     * The ICE specification RECOMMENDEDs that default candidates be chosen
     * based on the likelihood of those candidates to work with the peer that is
     * being contacted. It is RECOMMENDED that the default candidates are the
     * relayed candidates (if relayed candidates are available), server
     * reflexive candidates (if server reflexive candidates are available), and
     * finally host candidates.
     * </p>
     */
    protected void selectDefaultCandidate()
    {
        synchronized(localCandidates)
        {
            Iterator<LocalCandidate> localCandsIter
                                                = localCandidates.iterator();

            while (localCandsIter.hasNext())
            {
                LocalCandidate cand = localCandsIter.next();

                if(this.defaultCandidate == null)
                {
                    this.defaultCandidate = cand;
                    continue;
                }

                if( defaultCandidate.getDefaultPreference()
                                < cand.getDefaultPreference())
                {
                    defaultCandidate = cand;
                }
            }
        }
    }

    /**
     * Releases all resources allocated by this <tt>Component</tt> and its
     * <tt>Candidate</tt>s like sockets for example.
     */
    protected void free()
    {
        synchronized (localCandidates)
        {
            /*
             * Since the sockets of the non-HostCandidate LocalCandidates may
             * depend on the socket of the HostCandidate for which they have
             * been harvested, order the freeing.
             */
            CandidateType[] candidateTypes
                = new CandidateType[]
                        {
                            CandidateType.RELAYED_CANDIDATE,
                            CandidateType.PEER_REFLEXIVE_CANDIDATE,
                            CandidateType.SERVER_REFLEXIVE_CANDIDATE
                        };

            for (CandidateType candidateType : candidateTypes)
            {
                Iterator<LocalCandidate> localCandidateIter
                    = localCandidates.iterator();

                while (localCandidateIter.hasNext())
                {
                    LocalCandidate localCandidate = localCandidateIter.next();

                    if (candidateType.equals(localCandidate.getType()))
                    {
                        free(localCandidate);
                        localCandidateIter.remove();
                    }
                }
            }

            // Free whatever's left.
            Iterator<LocalCandidate> localCandidateIter
                = localCandidates.iterator();

            while (localCandidateIter.hasNext())
            {
                LocalCandidate localCandidate = localCandidateIter.next();

                free(localCandidate);
                localCandidateIter.remove();
            }
        }
    }

    /**
     * Frees a specific <tt>LocalCandidate</tt> and swallows any
     * <tt>Throwable</tt> it throws while freeing itself in order to prevent its
     * failure to affect the rest of the execution.
     *
     * @param localCandidate the <tt>LocalCandidate</tt> to be freed
     */
    private void free(LocalCandidate localCandidate)
    {
        try
        {
            localCandidate.free();
        }
        catch (Throwable t)
        {
            /*
             * Don't let the failing of a single LocalCandidate to free itself
             * to fail the freeing of the other LocalCandidates.
             */
            if (t instanceof ThreadDeath)
                throw (ThreadDeath) t;
            if (logger.isLoggable(Level.INFO))
            {
                logger.log(
                        Level.INFO,
                        "Failed to free LocalCandidate: " + localCandidate);
            }
        }
    }

    /**
     * Returns the local <tt>LocalCandidate</tt> with the specified
     * <tt>localAddress</tt> if it belongs to this component or <tt>null</tt>
     * if it doesn't.
     *
     * @param localAddress the {@link TransportAddress} we are looking for.
     *
     * @return  the local <tt>LocalCandidate</tt> with the specified
     * <tt>localAddress</tt> if it belongs to this component or <tt>null</tt>
     * if it doesn't.
     */
    public LocalCandidate findLocalCandidate(TransportAddress localAddress)
    {
        for (LocalCandidate localCnd : localCandidates)
        {
            if (localCnd.getTransportAddress().equals(localAddress))
                return localCnd;
        }

        return null;
    }

    /**
     * Returns the remote <tt>Candidate</tt> with the specified
     * <tt>remoteAddress</tt> if it belongs to this {@link Component} or
     * <tt>null</tt> if it doesn't.
     *
     * @param remoteAddress the {@link TransportAddress} we are looking for.
     *
     * @return the remote <tt>RemoteCandidate</tt> with the specified
     * <tt>remoteAddress</tt> if it belongs to this component or <tt>null</tt>
     * if it doesn't.
     */
    public RemoteCandidate findRemoteCandidate(TransportAddress remoteAddress)
    {
        for(RemoteCandidate remoteCnd : remoteCandidates)
        {
            if(remoteCnd.getTransportAddress().equals(remoteAddress))
                return remoteCnd;
        }

        return null;
    }

    /**
     * Sets the {@link CandidatePair} selected for use by ICE processing and
     * that the application would use.
     *
     * @param pair the {@link CandidatePair} selected for use by ICE processing.
     */
    protected void setSelectedPair(CandidatePair pair)
    {
        this.selectedPair = pair;
    }

    /**
     * Returns the {@link CandidatePair} selected for use by ICE processing or
     * <tt>null</tt> if no pair has been selected so far or if ICE processing
     * has failed.
     *
     * @return the {@link CandidatePair} selected for use by ICE processing or
     * <tt>null</tt> if no pair has been selected so far or if ICE processing
     * has failed.
     */
    public CandidatePair getSelectedPair()
    {
        return selectedPair;
    }

    /**
     * Returns a human readable name that can be used in debug logs associated
     * with this component.
     *
     * @return "RTP" if the component ID is 1, "RTCP" if the component id is 2
     * and the component id itself otherwise.
     */
    public String getName()
    {
        if (componentID == RTP)
            return "RTP";
        else if(componentID == RTCP)
            return "RTCP";
        else
            return Integer.toString(componentID);
    }
}
