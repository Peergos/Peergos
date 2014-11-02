/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package test;

import java.util.logging.*;

import org.ice4j.ice.*;


/**
 * An ICE sample that kind of works like ICE lite. The sample would create an
 * agent, make it print its SDP, then wait for a similar SDP to be fed through
 * standard input. Once this happens, it would also answer incoming connectivity
 * checks without making checks of its own.
 * <p>
 * This sample is easily used in conjunction with our {@link IceDistributed}
 * sample app.
 *
 * @author Emil Ivov
 */
public class IceLite
    extends IceDistributed
{
    /**
     * The <tt>Logger</tt> used by the <tt>IceLite</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger = Logger.getLogger(IceLite.class
                    .getName());
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
        logger.severe("Start");
        startTime = System.currentTimeMillis();

        Agent localAgent = createAgent(3030);
        localAgent.setControlling(false);
        localAgent.setNominationStrategy(
                        NominationStrategy.NOMINATE_HIGHEST_PRIO);

        localAgent.addStateChangeListener(new IceProcessingListener());

        String localSDP = SdpUtils.createSDPDescription(localAgent);

        System.out.println("=================== feed the following"
                        +" to the remote agent ===================");

        System.out.println(localSDP);

        System.out.println("======================================"
                        +"========================================\n");

        //Give processing enough time to finish. We'll System.exit() anyway
        //as soon as localAgent enters a final state.
        Thread.sleep(60000);
    }

}
