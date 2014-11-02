/*
 * Jitsi Videobridge, OpenSource video conferencing.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import org.ice4j.*;
import org.ice4j.ice.*;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

/**
 * Uses the Amazon AWS APIs to retrieve the public and private IPv4 addresses
 * for an EC2 instance.
 *
 * @author Emil Ivov
 */
public class AwsCandidateHarvester
    extends MappingCandidateHarvester
{
    /**
     * The <tt>Logger</tt> used by the <tt>AwsCandidateHarvester</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(AwsCandidateHarvester.class.getName());

    /**
     * The URL where one obtains AWS public addresses.
     */
    private static final String PUBLIC_IP_URL
        = "http://169.254.169.254/latest/meta-data/public-ipv4";

    /**
     * The URL where one obtains AWS private/local addresses.
     */
    private static final String LOCAL_IP_URL
        = "http://169.254.169.254/latest/meta-data/local-ipv4";

    /**
     * The URL to use to test whether we are running on Amazon EC2.
     */
    private static final String EC2_TEST_URL
        = "http://169.254.169.254/latest/meta-data/";

    /**
     * Whether we are running on Amazon EC2.
     */
    private static Boolean RUNNING_ON_EC2 = null;

    /**
     * The addresses that we will use as a mask
     */
    private static TransportAddress mask;

    /**
     * The addresses that we will be masking
     */
    private static TransportAddress face;

    /**
     * Creates an AWS harvester. The actual addresses wil be retrieved later,
     * during the first harvest.
     */
    public AwsCandidateHarvester()
    {
        super(null, null);
    }

    /**
     * Maps all candidates to this harvester's mask and adds them to
     * <tt>component</tt>.
     *
     * @param component the {@link Component} that we'd like to map candidates
     * to.
     * @return  the <tt>LocalCandidate</tt>s gathered by this
     * <tt>CandidateHarvester</tt> or <tt>null</tt> if no mask is specified.
     */
    public Collection<LocalCandidate> harvest(Component component)
    {

        if (mask == null || face == null)
        {
            if(!obtainEC2Addresses())
                return null;
        }


        /*
         * Report the LocalCandidates gathered by this CandidateHarvester so
         * that the harvest is sure to be considered successful.
         */
        Collection<LocalCandidate> candidates = new HashSet<LocalCandidate>();

        for (Candidate<?> cand : component.getLocalCandidates())
        {
            if (!(cand instanceof HostCandidate)
                || !cand.getTransportAddress().getHostAddress()
                            .equals(face.getHostAddress()))
            {
                continue;
            }

            TransportAddress mappedAddress = new TransportAddress(
                mask.getHostAddress(),
                cand.getHostAddress().getPort(),
                cand.getHostAddress().getTransport());

            ServerReflexiveCandidate mappedCandidate
                = new ServerReflexiveCandidate(
                    mappedAddress,
                    (HostCandidate)cand,
                    cand.getStunServerAddress(),
                    CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE);

            //try to add the candidate to the component and then
            //only add it to the harvest not redundant
            if( !candidates.contains(mappedCandidate)
                && component.addLocalCandidate(mappedCandidate))
            {
                candidates.add(mappedCandidate);
            }
        }

        return candidates;
    }

    /**
     * Sends HTTP GET queries to
     * <tt>http://169.254.169.254/latest/meta-data/local-ipv4</tt> and
     * <tt>http://169.254.169.254/latest/meta-data/public-ipv4</tt> to learn the
     * private (face) and public (mask) addresses of this EC2 instance.
     *
     * @return <tt>true</tt> if we managed to obtain addresses or someone else
     * had already achieved that before us, <tt>false</tt> otherwise.
     */
    private static synchronized boolean obtainEC2Addresses()
    {
        if(mask != null && face != null)
            return true;

        String localIPStr = null;
        String publicIPStr = null;

        try
        {
            localIPStr = fetch(LOCAL_IP_URL);
            publicIPStr = fetch(PUBLIC_IP_URL);

            //now let's cross our fingers and hope that what we got above are
            //real IP addresses
            face = new TransportAddress(localIPStr, 9, Transport.UDP);
            mask = new TransportAddress(publicIPStr, 9, Transport.UDP);

            logger.info("Detected AWS local IP: " + face);
            logger.info("Detected AWS public IP: " + mask);


        }
        catch (Exception exc)
        {
            //whatever happens, we just log and bail
            logger.log(Level.INFO, "We failed to obtain EC2 instance addresses "
                + "for the following reason: ", exc);
            logger.info("String for local IP: " + localIPStr);
            logger.info("String for public IP: " + publicIPStr);

        }

        return true;
    }

    /**
     * Returns the discovered public (mask) address, or null.
     * @return the discovered public (mask) address, or null.
     */
    public static TransportAddress getMask()
    {
        if (smellsLikeAnEC2())
        {
            obtainEC2Addresses();
            return mask;
        }
        return null;
    }

    /**
     * Returns the discovered local (face) address, or null.
     * @return the discovered local (face) address, or null.
     */
    public static TransportAddress getFace()
    {
        if (smellsLikeAnEC2())
        {
            obtainEC2Addresses();
            return face;
        }
        return null;
    }

    /**
     * Determines if there is a decent chance for the box executing this
     * application to be an AWS EC2 instance and returns <tt>true</tt> if so.
     *
     * @return <tt>true</tt> if there appear to be decent chances for this
     * machine to be an AWS EC2 and <tt>false</tt> otherwise.
     */
    public static boolean smellsLikeAnEC2()
    {
        if (RUNNING_ON_EC2 == null)
        {
            RUNNING_ON_EC2 = doTestEc2();
        }
        return RUNNING_ON_EC2;
    }

    /**
     * Tries to connect to an Amazon EC2-specific URL in order to determine
     * whether we are running on EC2.
     *
     * @return <tt>true</tt> if the connection succeeded, <tt>false</tt>
     * otherwise.
     */
    private static boolean doTestEc2()
    {
        try
        {
            URLConnection conn = new URL(EC2_TEST_URL).openConnection();
            conn.setConnectTimeout(500); //don't hang for too long
            conn.getContent();

            return true;
        }
        catch(Exception exc)
        {
            //ah! I knew you weren't one of those ...
            return false;
        }
    }

    /**
     * Retrieves the content at the specified <tt>url</tt>. No more, no less.
     *
     * @param url the URL we'd like to open and query.
     *
     * @return the String we retrieved from the URL.
     *
     * @throws Exception if anything goes wrong.
     */
    private static String fetch(String url)
        throws Exception
    {
        URLConnection conn = new URL(url).openConnection();
        BufferedReader in = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), "UTF-8"));

        String retString = in.readLine();

        in.close();

        return retString;
    }
}
