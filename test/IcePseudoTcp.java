/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package test;

import java.beans.*;
import java.io.*;
import java.net.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.pseudotcp.*;
import org.ice4j.security.*;

/**
 * Sample program which first uses ICE to discover UDP connectivity. After that
 * selected cadidates are used by "remote" and "local" pseudoTCP peers to
 * transfer some test data.
 *
 * @author Pawel Domas
 */
public class IcePseudoTcp
{
    /**
     * The logger.
     */
    private static final Logger logger =
        Logger.getLogger(IcePseudoTcp.class.getName());
    private static long startTime;
    /**
     * Local job thread variable
     */
    private static LocalPseudoTcpJob localJob = null;
    /**
     * Remote job thread variable
     */
    private static RemotePseudoTcpJob remoteJob = null;
    /**
     * Test data size
     */
    private static final int TEST_BYTES_COUNT = 15000000;
    /**
     * Flag inidcates if STUN should be used
     */
    private static final boolean USE_STUN = true;
    /**
     * Flag inidcates if TURN should be used
     */
    private static final boolean USE_TURN = true;
    /**
     * Monitor object used to wait for remote agent to finish it's job
     */
    private static final Object remoteAgentMonitor = new Object();
    /**
     * Monitor object used to wait for local agent to finish it's job
     */
    private static final Object localAgentMonitor = new Object();
    /**
     * Timeout for ICE discovery
     */
    private static long agentJobTimeout = 15000;

    protected static Agent createAgent(int pTcpPort)
        throws Throwable
    {
        Agent agent = new Agent();
        // STUN
        if (USE_STUN)
        {
            StunCandidateHarvester stunHarv = new StunCandidateHarvester(
                new TransportAddress("sip-communicator.net",
                                     3478, Transport.UDP));
            StunCandidateHarvester stun6Harv = new StunCandidateHarvester(
                new TransportAddress("ipv6.sip-communicator.net",
                                     3478, Transport.UDP));

            agent.addCandidateHarvester(stunHarv);
            agent.addCandidateHarvester(stun6Harv);
        }
        // TURN 
        if (USE_TURN)
        {
            String[] hostnames = new String[]
            {
                "130.79.90.150",
                "2001:660:4701:1001:230:5ff:fe1a:805f"
            };
            int port = 3478;
            LongTermCredential longTermCredential = new LongTermCredential(
                "guest", "anonymouspower!!");

            for (String hostname : hostnames)
            {
                agent.addCandidateHarvester(new TurnCandidateHarvester(
                    new TransportAddress(hostname, port,
                                         Transport.UDP), longTermCredential));
            }
        }
        //STREAM
        createStream(pTcpPort, "data", agent);

        return agent;
    }

    private static IceMediaStream createStream(int pTcpPort,
                                               String streamName,
                                               Agent agent)
        throws Throwable
    {
        IceMediaStream stream = agent.createMediaStream(streamName);

        long startTime = System.currentTimeMillis();

        //udp component
        agent.createComponent(
            stream, Transport.UDP, pTcpPort, pTcpPort, pTcpPort + 100);

        long endTime = System.currentTimeMillis();
        logger.log(Level.INFO,
                   "UDP Component created in " + (endTime - startTime) + " ms");
        startTime = endTime;

        return stream;
    }

    private static final class LocalIceProcessingListener
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

            logger.log(
                    Level.INFO,
                    "Local agent entered the " + iceProcessingState
                        + " state.");
            if (iceProcessingState == IceProcessingState.COMPLETED)
            {
                logger.log(
                        Level.INFO,
                        "Local - Total ICE processing time: "
                            + (processingEndTime - startTime) + "ms");
                Agent agent = (Agent) evt.getSource();
                logger.log(Level.INFO, "Local: Create pseudo tcp stream");
                IceMediaStream dataStream = agent.getStream("data");
                Component udpComponent = dataStream.getComponents().get(0);
                CandidatePair selectedPair = udpComponent.getSelectedPair();
                if (selectedPair != null)
                {
                    LocalCandidate localCandidate
                        = selectedPair.getLocalCandidate();
                    Candidate<?> remoteCandidate
                        = selectedPair.getRemoteCandidate();
                    logger.log(Level.INFO, "Local: " + localCandidate);
                    logger.log(Level.INFO, "Remote: " + remoteCandidate);
                    try
                    {
                        localJob = new LocalPseudoTcpJob(
                            localCandidate.getDatagramSocket());                        
                    }
                    catch (UnknownHostException ex)
                    {
                        logger.log(Level.SEVERE,
                                   "Error while trying to create"
                            + " local pseudotcp thread " + ex);
                    }
                }
                else
                {
                    logger.log(
                            Level.INFO,
                            "Failed to select any candidate pair");
                }
            }
            else
            {
                if (iceProcessingState == IceProcessingState.TERMINATED
                    || iceProcessingState == IceProcessingState.FAILED)
                {
                    /*
                     * Though the process will be instructed to die, demonstrate
                     * that Agent instances are to be explicitly prepared for
                     * garbage collection.
                     */
                    if ((localJob != null)
                            && (iceProcessingState
                                    == IceProcessingState.TERMINATED))
                    {
                    	localJob.start();                        
                    }
                    synchronized (localAgentMonitor)
                    {
                        localAgentMonitor.notifyAll();
                    }
                }
            }
        }
    }

    private static final class RemoteIceProcessingListener
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

            logger.log(
                    Level.INFO,
                    "Remote agent entered the " + iceProcessingState
                        + " state.");
            if (iceProcessingState == IceProcessingState.COMPLETED)
            {
                logger.log(Level.INFO,
                           "Remote: Total ICE processing time: "
                    + (processingEndTime - startTime) + " ms ");
                Agent agent = (Agent) evt.getSource();

                logger.log(Level.INFO, "Remote: Create pseudo tcp stream");
                IceMediaStream dataStream = agent.getStream("data");
                Component udpComponent = dataStream.getComponents().get(0);
                CandidatePair usedPair = udpComponent.getSelectedPair();
                if (usedPair != null)
                {
                    LocalCandidate localCandidate
                        = usedPair.getLocalCandidate();
                    Candidate<?> remoteCandidate
                        = usedPair.getRemoteCandidate();
                    logger.log(Level.INFO,
                               "Remote: Local address " + localCandidate);
                    logger.log(Level.INFO,
                               "Remote: Peer address " + remoteCandidate);
                    try
                    {
                        remoteJob = new RemotePseudoTcpJob(
                            localCandidate.getDatagramSocket(),
                            remoteCandidate.getTransportAddress());                        
                    }
                    catch (UnknownHostException ex)
                    {
                        logger.log(Level.SEVERE,
                                   "Error while trying to create"
                            + " remote pseudotcp thread " + ex);
                    }
                }
                else
                {
                    logger.log(Level.SEVERE,
                               "Remote: Failed to select any candidate pair");
                }
            }
            else
            {
                if (iceProcessingState == IceProcessingState.TERMINATED
                    || iceProcessingState == IceProcessingState.FAILED)
                {
                    /*
                     * Though the process will be instructed to die, demonstrate
                     * that Agent instances are to be explicitly prepared for
                     * garbage collection.
                     */
                    if ((remoteJob != null)
                            && (iceProcessingState
                                    == IceProcessingState.TERMINATED))
                    {
                    	remoteJob.start();                        
                    }
                    synchronized (remoteAgentMonitor)
                    {
                        remoteAgentMonitor.notifyAll();
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Throwable
    {
        startTime = System.currentTimeMillis();
        
        int localPort = 7999;
        int remotePort = 6000;

        Agent localAgent = createAgent(localPort);
        localAgent.setNominationStrategy(
            NominationStrategy.NOMINATE_HIGHEST_PRIO);
        Agent remotePeer =
            createAgent(remotePort);

        localAgent.addStateChangeListener(
                new IcePseudoTcp.LocalIceProcessingListener());
        remotePeer.addStateChangeListener(
                new IcePseudoTcp.RemoteIceProcessingListener());

        //let them fight ... fights forge character.
        localAgent.setControlling(true);
        remotePeer.setControlling(false);

        long endTime = System.currentTimeMillis();

        Ice.transferRemoteCandidates(localAgent, remotePeer);
        for (IceMediaStream stream : localAgent.getStreams())
        {
            stream.setRemoteUfrag(remotePeer.getLocalUfrag());
            stream.setRemotePassword(remotePeer.getLocalPassword());
        }

        Ice.transferRemoteCandidates(remotePeer, localAgent);

        for (IceMediaStream stream : remotePeer.getStreams())
        {
            stream.setRemoteUfrag(localAgent.getLocalUfrag());
            stream.setRemotePassword(localAgent.getLocalPassword());
        }

        logger.log(Level.INFO, "Total candidate gathering time: {0} ms",
                   (endTime - startTime));
        logger.log(Level.INFO, "LocalAgent: {0}",
                   localAgent);

        localAgent.startConnectivityEstablishment();

        //if (START_CONNECTIVITY_ESTABLISHMENT_OF_REMOTE_PEER)
        remotePeer.startConnectivityEstablishment();


        IceMediaStream dataStream = localAgent.getStream("data");

        if (dataStream != null)
        {
            logger.log(Level.INFO,
                       "Local data clist:" + dataStream.getCheckList());
        }
        //wait for one of the agents to complete it's job 
        synchronized (remoteAgentMonitor)
        {
            remoteAgentMonitor.wait(agentJobTimeout);
        }
        if (remoteJob != null)
        {
            logger.log(Level.FINEST, "Remote thread join started");
            remoteJob.join();
            logger.log(Level.FINEST, "Remote thread joined");            
        }
        remotePeer.free();
        if (localJob != null)
        {
            logger.log(Level.FINEST, "Local thread join started");
            localJob.join();
            logger.log(Level.FINEST, "Local thread joined");            
        }
        localAgent.free();
        System.exit(0);
    }

    private static class LocalPseudoTcpJob extends Thread implements Runnable
    {
    	private DatagramSocket dgramSocket;

        public LocalPseudoTcpJob(DatagramSocket socket)
            throws UnknownHostException
        {
            this.dgramSocket = socket;
        }

        @Override
        public void run()
        {
            logger.log(Level.FINEST, "Local pseudotcp worker started");
            try
            {
                logger.log(Level.INFO,
                           "Local pseudotcp is using: " 
                    + dgramSocket.getLocalSocketAddress()+dgramSocket);
                
                PseudoTcpSocket socket = new PseudoTcpSocketFactory().
                    createSocket(dgramSocket);
                socket.setConversationID(1073741824);
                socket.setMTU(1500);
                socket.setDebugName("L");
                socket.accept(5000);                
                byte[] buffer = new byte[TEST_BYTES_COUNT];
                int read = 0;
                while (read != TEST_BYTES_COUNT)
                {
                    read += socket.getInputStream().read(buffer);
                    logger.log(Level.FINEST, "Local job read: " + read);
                }
                //TODO: close when all received data is acked
                //socket.close();
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            logger.log(Level.FINEST, "Local pseudotcp worker finished");
        }
    }

    private static class RemotePseudoTcpJob extends Thread implements Runnable
    {
        private DatagramSocket dgramSocket;
        private InetSocketAddress peerAddr;

        public RemotePseudoTcpJob(DatagramSocket socket,
                                  InetSocketAddress peerAddr)
            throws UnknownHostException
        {
        	this.dgramSocket = socket;
            this.peerAddr = peerAddr;
        }

        @Override
        public void run()
        {
            logger.log(Level.FINEST, "Remote pseudotcp worker started");
            try
            {
                logger.log(Level.INFO,
                           "Remote pseudotcp is using: " +
                           dgramSocket.getLocalSocketAddress()
                           +" and will comunicate with: " + peerAddr);
                PseudoTcpSocket socket = new PseudoTcpSocketFactory().
                    createSocket(dgramSocket);
                socket.setConversationID(1073741824);
                socket.setMTU(1500);
                socket.setDebugName("R");
                long start, end;
                start = System.currentTimeMillis();
                socket.connect(peerAddr, 5000);
                byte[] buffer = new byte[TEST_BYTES_COUNT];
                socket.getOutputStream().write(buffer);
                socket.getOutputStream().flush();
                //Socket will be closed by the iceAgent
                //socket.close();
                end = System.currentTimeMillis();
                logger.log(Level.INFO,
                           "Transferred " + TEST_BYTES_COUNT
                    + " bytes in " + ((end - start) / 1000) + " sec");
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            logger.log(Level.FINEST, "Remote pseudotcp worker finished");
        }
    }
}
