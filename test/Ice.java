/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package test;

import java.beans.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.security.*;

/**
 * Simple ice4j testing scenario. The sample application would create and start
 * both agents and make one of them run checks against the other.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
public class Ice
{
    /**
     * The <tt>Logger</tt> used by the <tt>Ice</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(Ice.class.getName());

    /**
     * The indicator which determines whether the <tt>Ice</tt> application (i.e.
     * the run-sample Ant target) is to start connectivity establishment of the
     * remote peer (in addition to the connectivity establishment of the local
     * agent which is always started, of course).
     */
    private static final boolean START_CONNECTIVITY_ESTABLISHMENT_OF_REMOTE_PEER
        = false;

    /**
     * Start time for debugging purposes.
     */
    static long startTime;

    /**
     * Runs the test
     * @param args command line arguments
     *
     * @throws Throwable if bad stuff happens.
     */
    public static void main(String[] args) throws Throwable
    {
        startTime = System.currentTimeMillis();

        Agent localAgent = createAgent(9090, false);
        localAgent.setNominationStrategy(
                        NominationStrategy.NOMINATE_HIGHEST_PRIO);
        Agent remotePeer = createAgent(6060, false);

        localAgent.addStateChangeListener(new IceProcessingListener());

        //let them fight ... fights forge character.
        localAgent.setControlling(true);
        remotePeer.setControlling(false);

        long endTime = System.currentTimeMillis();

        transferRemoteCandidates(localAgent, remotePeer);
        for(IceMediaStream stream : localAgent.getStreams())
        {
            stream.setRemoteUfrag(remotePeer.getLocalUfrag());
            stream.setRemotePassword(remotePeer.getLocalPassword());
        }

        if (START_CONNECTIVITY_ESTABLISHMENT_OF_REMOTE_PEER)
            transferRemoteCandidates(remotePeer, localAgent);

        for(IceMediaStream stream : remotePeer.getStreams())
        {
            stream.setRemoteUfrag(localAgent.getLocalUfrag());
            stream.setRemotePassword(localAgent.getLocalPassword());
        }

        logger.info("Total candidate gathering time: "
                        + (endTime - startTime) + "ms");
        logger.info("LocalAgent:\n" + localAgent);

        localAgent.startConnectivityEstablishment();

        if (START_CONNECTIVITY_ESTABLISHMENT_OF_REMOTE_PEER)
            remotePeer.startConnectivityEstablishment();

        logger.info("Local audio clist:\n"
                        + localAgent.getStream("audio").getCheckList());

        IceMediaStream videoStream = localAgent.getStream("video");

        if(videoStream != null)
            logger.info("Local video clist:\n"
                            + videoStream.getCheckList());

        //Give processing enough time to finish. We'll System.exit() anyway
        //as soon as localAgent enters a final state.
        Thread.sleep(60000);
    }

    /**
     * The listener that would end example execution once we enter the
     * completed state.
     */
    public static final class IceProcessingListener
        implements PropertyChangeListener
    {
        /**
         * System.exit()s as soon as ICE processing enters a final state.
         *
         * @param evt the {@link PropertyChangeEvent} containing the old and new
         * states of ICE processing.
         */
        public void propertyChange(PropertyChangeEvent evt)
        {
            long processingEndTime = System.currentTimeMillis();

            Object iceProcessingState = evt.getNewValue();

            logger.info(
                    "Agent entered the " + iceProcessingState + " state.");
            if(iceProcessingState == IceProcessingState.COMPLETED)
            {
                logger.info(
                        "Total ICE processing time: "
                            + (processingEndTime - startTime) + "ms");
                Agent agent = (Agent)evt.getSource();
                List<IceMediaStream> streams = agent.getStreams();

                for(IceMediaStream stream : streams)
                {
                    String streamName = stream.getName();
                    logger.info(
                            "Pairs selected for stream: " + streamName);
                    List<Component> components = stream.getComponents();

                    for(Component cmp : components)
                    {
                        String cmpName = cmp.getName();
                        logger.info(cmpName + ": "
                                        + cmp.getSelectedPair());
                    }
                }

                logger.info("Printing the completed check lists:");
                for(IceMediaStream stream : streams)
                {
                    String streamName = stream.getName();
                    logger.info("Check list for  stream: " + streamName);
                    //uncomment for a more verbose output
                    logger.info(stream.getCheckList().toString());
                }

                logger.info("Total ICE processing time to completion: "
                    + (System.currentTimeMillis() - startTime));
            }
            else if(iceProcessingState == IceProcessingState.TERMINATED
                    || iceProcessingState == IceProcessingState.FAILED)
            {
                /*
                 * Though the process will be instructed to die, demonstrate
                 * that Agent instances are to be explicitly prepared for
                 * garbage collection.
                 */
                ((Agent) evt.getSource()).free();

                logger.info("Total ICE processing time: "
                    + (System.currentTimeMillis() - startTime));
                System.exit(0);
            }
        }
    }

    /**
     * Installs remote candidates in <tt>localAgent</tt>..
     *
     * @param localAgent a reference to the agent that we will pretend to be the
     * local
     * @param remotePeer a reference to what we'll pretend to be a remote agent.
     */
    static void transferRemoteCandidates(Agent localAgent,
                                                 Agent remotePeer)
    {
        List<IceMediaStream> streams = localAgent.getStreams();

        for(IceMediaStream localStream : streams)
        {
            String streamName = localStream.getName();

            //get a reference to the local stream
            IceMediaStream remoteStream = remotePeer.getStream(streamName);

            if(remoteStream != null)
                transferRemoteCandidates(localStream, remoteStream);
            else
                localAgent.removeStream(localStream);
        }
    }

    /**
     * Installs remote candidates in <tt>localStream</tt>..
     *
     * @param localStream the stream where we will be adding remote candidates
     * to.
     * @param remoteStream the stream that we should extract remote candidates
     * from.
     */
    private static void transferRemoteCandidates(IceMediaStream localStream,
                                                 IceMediaStream remoteStream)
    {
        List<Component> localComponents = localStream.getComponents();

        for(Component localComponent : localComponents)
        {
            int id = localComponent.getComponentID();

            Component remoteComponent = remoteStream.getComponent(id);

            if(remoteComponent != null)
                transferRemoteCandidates(localComponent, remoteComponent);
            else
                localStream.removeComponent(localComponent);
        }
    }

    /**
     * Adds to <tt>localComponent</tt> a list of remote candidates that are
     * actually the local candidates from <tt>remoteComponent</tt>.
     *
     * @param localComponent the <tt>Component</tt> where that we should be
     * adding <tt>remoteCandidate</tt>s to.
     * @param remoteComponent the source of remote candidates.
     */
    private static void transferRemoteCandidates(Component localComponent,
                                                 Component remoteComponent)
    {
        List<LocalCandidate> remoteCandidates
            = remoteComponent.getLocalCandidates();

        localComponent.setDefaultRemoteCandidate(
                remoteComponent.getDefaultCandidate());

        for(Candidate<?> rCand : remoteCandidates)
        {
            localComponent.addRemoteCandidate(
                    new RemoteCandidate(
                            rCand.getTransportAddress(),
                            localComponent,
                            rCand.getType(),
                            rCand.getFoundation(),
                            rCand.getPriority(),
                            null));
        }
    }

    /**
     * Creates a vanilla ICE <tt>Agent</tt> and adds to it an audio and a video
     * stream with RTP and RTCP components.
     *
     * @param rtpPort the port that we should try to bind the RTP component on
     * (the RTCP one would automatically go to rtpPort + 1)
     * @return an ICE <tt>Agent</tt> with an audio stream with RTP and RTCP
     * components.
     *
     * @throws Throwable if anything goes wrong.
     */
    protected static Agent createAgent(int rtpPort)
        throws Throwable
    {
        return createAgent(rtpPort, false);
    }

    /**
     * Creates an ICE <tt>Agent</tt> (vanilla or trickle, depending on the
     * value of <tt>isTrickling</tt>) and adds to it an audio and a video stream
     * with RTP and RTCP components.
     *
     * @param rtpPort the port that we should try to bind the RTP component on
     * (the RTCP one would automatically go to rtpPort + 1)
     * @return an ICE <tt>Agent</tt> with an audio stream with RTP and RTCP
     * components.
     * @param isTrickling indicates whether the newly created agent should be
     * performing trickle ICE.
     *
     * @throws Throwable if anything goes wrong.
     */
    protected static Agent createAgent(int rtpPort, boolean isTrickling)
        throws Throwable
    {
        return createAgent(rtpPort, isTrickling, null);
    }

    /**
     * Creates an ICE <tt>Agent</tt> (vanilla or trickle, depending on the
     * value of <tt>isTrickling</tt>) and adds to it an audio and a video stream
     * with RTP and RTCP components.
     *
     * @param rtpPort the port that we should try to bind the RTP component on
     * (the RTCP one would automatically go to rtpPort + 1)
     * @return an ICE <tt>Agent</tt> with an audio stream with RTP and RTCP
     * components.
     * @param isTrickling indicates whether the newly created agent should be
     * performing trickle ICE.
     * @param harvesters the list of {@link CandidateHarvester}s that the new
     * agent should use or <tt>null</tt> if it should include the default ones.
     *
     * @throws Throwable if anything goes wrong.
     */
    protected static Agent createAgent(int      rtpPort,
                                       boolean  isTrickling,
                                       List<CandidateHarvester>  harvesters)
        throws Throwable
    {
        long startTime = System.currentTimeMillis();
        Agent agent = new Agent();
        agent.setTrickling(isTrickling);

        if(harvesters == null)
        {
            // STUN
            StunCandidateHarvester stunHarv = new StunCandidateHarvester(
                new TransportAddress("stun.jitsi.net", 3478, Transport.UDP));
            StunCandidateHarvester stun6Harv = new StunCandidateHarvester(
                new TransportAddress("stun6.jitsi.net", 3478, Transport.UDP));

            agent.addCandidateHarvester(stunHarv);
            agent.addCandidateHarvester(stun6Harv);

            // TURN
            String[] hostnames = new String[]
                                 {
                                    "stun.jitsi.net",
                                    "stun6.jitsi.net"
                                 };
            int port = 3478;
            LongTermCredential longTermCredential
                = new LongTermCredential("guest", "anonymouspower!!");

            for (String hostname : hostnames)
                agent.addCandidateHarvester(
                        new TurnCandidateHarvester(
                                new TransportAddress(
                                    hostname, port, Transport.UDP),
                                longTermCredential));

            //UPnP: adding an UPnP harvester because they are generally slow
            //which makes it more convenient to test things like trickle.
            agent.addCandidateHarvester( new UPNPHarvester() );
        }
        else
        {
            for(CandidateHarvester harvester: harvesters)
            {
                agent.addCandidateHarvester(harvester);
            }
        }

        //STREAMS
        createStream(rtpPort, "audio", agent);
        createStream(rtpPort + 2, "video", agent);


        long endTime = System.currentTimeMillis();
        long total = endTime - startTime;

        logger.info("Total harvesting time: " + total + "ms.");

        return agent;
    }

    /**
     * Creates an <tt>IceMediaStream</tt> and adds to it an RTP and and RTCP
     * component.
     *
     * @param rtpPort the port that we should try to bind the RTP component on
     * (the RTCP one would automatically go to rtpPort + 1)
     * @param streamName the name of the stream to create
     * @param agent the <tt>Agent</tt> that should create the stream.
     *
     * @return the newly created <tt>IceMediaStream</tt>.
     * @throws Throwable if anything goes wrong.
     */
    private static IceMediaStream createStream(int    rtpPort,
                                               String streamName,
                                               Agent  agent)
        throws Throwable
    {
        IceMediaStream stream = agent.createMediaStream(streamName);

        long startTime = System.currentTimeMillis();

        //TODO: component creation should probably be part of the library. it
        //should also be started after we've defined all components to be
        //created so that we could run the harvesting for everyone of them
        //simultaneously with the others.

        //rtp
        agent.createComponent(
                stream, Transport.UDP, rtpPort, rtpPort, rtpPort + 100);

        long endTime = System.currentTimeMillis();
        logger.info("RTP Component created in "
            + (endTime - startTime) + " ms");
        startTime = endTime;
        //rtcpComp
        agent.createComponent(
                stream, Transport.UDP, rtpPort + 1, rtpPort + 1, rtpPort + 101);

        endTime = System.currentTimeMillis();
        logger.info("RTCP Component created in "
            + (endTime - startTime) + " ms");

        return stream;
    }
}
