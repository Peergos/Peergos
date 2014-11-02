/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package test;

import java.util.*;
import java.util.logging.*;

import javax.sdp.*;

import org.ice4j.ice.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.ice.sdp.*;

/**
 * An example of using trickle ICE.
 * <p>
 * @author Emil Ivov
 */
public class TrickleIce
    extends Ice
{
    /**
     * The <tt>Logger</tt> used by the <tt>TrickleIce</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(TrickleIce.class.getName());

    /**
     * Runs a test application that allocates streams, generates an SDP, dumps
     * it on stdout, waits for a remote peer SDP on stdin, then feeds that
     * to our local agent and starts ICE processing.
     *
     * @param args none currently handled
     * @throws Throwable every now and then.
     */
    public static void main(String[] args) throws Throwable
    {
        Agent localAgent = createAgent(2020, true);
        localAgent.setNominationStrategy(
                        NominationStrategy.NOMINATE_HIGHEST_PRIO);

        localAgent.addStateChangeListener(new IceProcessingListener());

        //let them fight ... fights forge character.
        localAgent.setControlling(false);
        String localSDP = SdpUtils.createSDPDescription(localAgent);

        //wait a bit so that the logger can stop dumping stuff:
        Thread.sleep(500);

        logger.info("=================== feed the following"
                    +" to the remote agent ===================");


        logger.info("\n" + localSDP);

        logger.info("======================================"
            + "========================================\n");

        CandidatePrinter printer = new CandidatePrinter();
        printer.agent = localAgent;


        localAgent.startCandidateTrickle(printer);


        List<Component> allComponents = new LinkedList<Component>();
        int allCandidates = 0;
        for (IceMediaStream stream : localAgent.getStreams())
        {
            for(Component component : stream.getComponents())
            {
                allComponents.add(component);
                allCandidates += component.getLocalCandidateCount();
            }
        }
        logger.info("all candidates = " + allCandidates);

        /*String sdp = IceDistributed.readSDP();

        startTime = System.currentTimeMillis();
        SdpUtils.parseSDP(localAgent, sdp);

        localAgent.startConnectivityEstablishment();
        */

        //Give processing enough time to finish. We'll System.exit() anyway
        //as soon as localAgent enters a final state.
        Thread.sleep(60000);
    }

    static class CandidatePrinter implements TrickleCallback
    {
        /**
         * Number of candidates that we have seen.
         */
        static int candidateCounter = 0;

        /**
         * The ICE agent that's running the show.
         */
        Agent agent;

        /**
         * Converts every trickled candidate to SDP and prints it on stdout.
         *
         * @param iceCandidates the newly discovered list of candidates or,
         * similarly to WebRTC, <tt>null</tt> in case all candidate harvesting
         * is now completed.
         */
        public void onIceCandidates(Collection<LocalCandidate> iceCandidates)
        {
            if( iceCandidates != null)
                candidateCounter++ ;

            Collection<Attribute> update
                = IceSdpUtils.createTrickleUpdate(iceCandidates);

            for(Attribute attribute : update)
            {
                logger.info(attribute.toString().trim());
            }

            if(iceCandidates == null)
            {
                try{Thread.sleep(1000);}catch(Exception e){};

                logger.info("ICE stats: time="
                    + agent.getTotalHarvestingTime() + "ms");

                //print statistics
                for (CandidateHarvester harvester : agent.getHarvesters())
                {
                    logger.info(
                        harvester.getHarvestStatistics().toString().trim());
                }



            }
        }
    }

}
