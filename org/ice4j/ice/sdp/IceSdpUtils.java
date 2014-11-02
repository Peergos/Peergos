/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice.sdp;

import org.ice4j.*;
import org.ice4j.ice.*;

import javax.sdp.*;
import java.util.*;
import java.util.logging.*;

/**
 * A number of utility methods that WebRTC or SIP applications may find useful
 * in case they are also fine with using jain-sdp
 *
 * @author Emil Ivov
 */
public class IceSdpUtils
{
    /**
     * The name of the SDP attribute that contains an ICE user fragment.
     */
    public static final String ICE_UFRAG = "ice-ufrag";

    /**
     * The name of the SDP attribute that contains an ICE password.
     */
    public static final String ICE_PWD = "ice-pwd";

    /**
     * The name of the SDP attribute that contains an ICE options.
     */
    public static final String ICE_OPTIONS = "ice-options";

    /**
     * The name of the ICE SDP option that indicates support for trickle.
     */
    public static final String ICE_OPTION_TRICKLE = "trickle";

    /**
     * The name of the "mid" SDP attribute.
     */
    public static final String MID = "mid";

    /**
     * The name of the SDP attribute that contains RTCP address and port.
     */
    private static final String RTCP = "rtcp";

    /**
     * The name of the SDP attribute that indicates an end of candidate
     * trickling: "end-of-candidates".
     */
    private static final String END_OF_CANDIDATES = "end-of-candidates";

    /**
     * A reference to the currently valid SDP factory instance.
     */
    private static final SdpFactory sdpFactory = SdpFactory.getInstance();

    /**
     * The <tt>Logger</tt> used by the <tt>IceSdpUtils</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(IceSdpUtils.class.getName());

    /**
     * Sets the specified ICE user fragment and password as attributes of the
     * specified session description.
     *
     * @param sDes the session description where we'd like to set a user
     * fragment and a password.
     * @param uFrag the ICE user name fragment that we'd like to set on the
     * session description
     * @param pwd the ICE password that we'd like to set on the session
     * description
     *
     * @throws NullPointerException if the either of the parameters is null
     */
    @SuppressWarnings("unchecked") // SDP legacy
    public static void setIceCredentials(SessionDescription sDes,
                                         String             uFrag,
                                         String             pwd)
        throws NullPointerException
    {
        if (sDes == null || uFrag == null || pwd == null)
        {
            throw new NullPointerException(
                "sDes, uFrag and pwd, cannot be null");
        }

        try
        {
            Vector<Attribute> sessionAttributes = sDes.getAttributes(true);

            //ice u-frag and password
            sessionAttributes.add(sdpFactory.createAttribute(ICE_UFRAG, uFrag));
            sessionAttributes.add(sdpFactory.createAttribute(ICE_PWD, pwd));

            sDes.setAttributes(sessionAttributes);
        }
        catch (Exception cause)
        {
           // this is very unlikely to happen but we should still log.
           // Just in case.
           logger.log(Level.INFO,
               "Failed to set ICE credentials for some weird reason",
               cause);
        }
    }

    /**
     * Reflects the candidates from the various components in
     * <tt>iceMediaStream</tt> into the specified m-line. Also sets default
     * candidates into m= lines and c= lines. This method uses media level
     * c= lines. They override session level making them pointless. This
     * shouldn't be causing problems, but for good taste, make sure you don't
     * include session level ones and avoid duplication.
     *
     * @param mediaDescription the media descriptions that we'd like to add
     * candidates to.
     * @param iceMediaStream the media stream where we should extract candidates
     * from.
     */
    public static void initMediaDescription(MediaDescription mediaDescription,
                                            IceMediaStream   iceMediaStream)
    {
        try
        {
            //set mid-s
            mediaDescription.setAttribute(MID, iceMediaStream.getName());

            Component firstComponent = null;

            //add candidates
            for(Component component : iceMediaStream.getComponents())
            {
                //if this is the first component, remember it so that we can
                //later use it for default candidates.
                if(firstComponent == null)
                    firstComponent = component;

                for(Candidate<?> candidate : component.getLocalCandidates())
                {
                    mediaDescription.addAttribute(
                        new CandidateAttribute(candidate));
                }
            }

            //set the default candidate
            TransportAddress defaultAddress = firstComponent
                .getDefaultCandidate().getTransportAddress();

            mediaDescription.getMedia().setMediaPort(
                defaultAddress.getPort());

            String addressFamily = defaultAddress.isIPv6()
                                ? Connection.IP6
                                : Connection.IP4;

            mediaDescription.setConnection(sdpFactory.createConnection(
                "IN", defaultAddress.getHostAddress(), addressFamily));

            //now check if the RTCP port for the default candidate is different
            //than RTP.port +1, in which case we need to mention it.
            Component rtcpComponent
                = iceMediaStream.getComponent(Component.RTCP);

            if( rtcpComponent != null )
            {
                TransportAddress defaultRtcpCandidate = rtcpComponent
                    .getDefaultCandidate().getTransportAddress();

                if(defaultRtcpCandidate.getPort() != defaultAddress.getPort()+1)
                {
                    mediaDescription.setAttribute(
                        RTCP, Integer.toString(defaultRtcpCandidate.getPort()));
                }
            }
        }
        catch (SdpException exc)
        {
            //this shouldn't happen but let's rethrow an SDP exception just
            //in case.
            throw new IllegalArgumentException(
                "Something went wrong when setting default candidates",
                exc);
        }
    }

    /**
     * Sets ice credentials, ICE options, media lines and candidates from agent,
     * on the specified session description.
     *
     * @param sDes the {@link SessionDescription} that we'd like to setup as per
     * the specified agent.
     * @param agent the {@link Agent} that we need to use when initializing
     * the session description.
     *
     * @throws  IllegalArgumentException Obviously, if there's a problem with
     * the arguments ... duh!
     */
    @SuppressWarnings("unchecked") // jain-sdp legacy
    public static void initSessionDescription(SessionDescription sDes,
                                              Agent              agent)
        throws IllegalArgumentException
    {
        //now add ICE options
        StringBuilder allOptionsBuilder = new StringBuilder();

        //if(agent.supportsTrickle())
            allOptionsBuilder.append(ICE_OPTION_TRICKLE).append(" ");

        String allOptions = allOptionsBuilder.toString().trim();

        try
        {
            if (allOptions.length() > 0)
            {
                //get the attributes so that we could easily modify them
                Vector<Attribute> sessionAttributes = sDes.getAttributes(true);

                sessionAttributes.add(
                    sdpFactory.createAttribute(ICE_OPTIONS, allOptions));
            }

            //take care of the origin: first extract one of the default
            // addresses so that we could set the origin.
            TransportAddress defaultAddress = agent.getStreams().get(0)
                .getComponent(Component.RTP).getDefaultCandidate()
                    .getTransportAddress();

            String addressFamily = defaultAddress.isIPv6()
                                        ? Connection.IP6
                                        : Connection.IP4;

            //origin
            Origin o = sDes.getOrigin();

            if( o == null || "user".equals(o.getUsername()))
            {
                //looks like there wasn't any origin set: jain-sdp creates a
                //default origin that has "user" as the user name so we use this
                //to detect it. it's quite hacky but couldn't fine another way.
                o = sdpFactory.createOrigin("ice4j.org", 0, 0, "IN",
                        addressFamily, defaultAddress.getHostAddress());
            }
            else
            {
                //if an origin existed, we just make sure it has the right
                // address now and are care ful not to touch anything else.
                o.setAddress(defaultAddress.getHostAddress());
                o.setAddressType(addressFamily);
            }

            sDes.setOrigin(o);

            //m lines
            List<IceMediaStream> streams = agent.getStreams();
            Vector<MediaDescription> mDescs = new Vector<MediaDescription>(
                            agent.getStreamCount());
            for(IceMediaStream stream : streams)
            {
               MediaDescription mLine = sdpFactory.createMediaDescription(
                               stream.getName(), 0, //default port comes later
                               1, SdpConstants.RTP_AVP, new int[]{0});

               IceSdpUtils.initMediaDescription(mLine, stream);

               mDescs.add(mLine);
            }

            sDes.setMediaDescriptions(mDescs);
        }
        catch (SdpException exc)
        {
            //this shouldn't happen but let's rethrow an SDP exception just
            //in case.
            throw new IllegalArgumentException(
                "Something went wrong when setting ICE options",
                exc);
        }

        //first set credentials
        setIceCredentials(
            sDes, agent.getLocalUfrag(), agent.getLocalPassword());
    }


    /**
     * Generates and returns a set of attributes that can be used for a trickle
     * update, such as a SIP INFO, with the specified <tt>localCandidates</tt>.
     *
     * @param localCandidates the list of {@link LocalCandidate}s that we'd like
     * to generate the update for.
     *
     * @return a collection of {@link CandidateAttribute}s and an MID attribute
     * that we can use in a SIP INFO trickle update.
     */
    public static Collection<Attribute> createTrickleUpdate(
                                Collection<LocalCandidate> localCandidates)
    {
        List<Attribute> trickleUpdate = null;

        if(localCandidates == null || localCandidates.size() == 0)
        {
            trickleUpdate = new ArrayList<Attribute>(1);
            trickleUpdate.add(
                sdpFactory.createAttribute(END_OF_CANDIDATES, null));

            return trickleUpdate;
        }

        trickleUpdate
            = new ArrayList<Attribute>(localCandidates.size() + 1);

        String streamName = null;

        for(LocalCandidate candidate : localCandidates)
        {
           streamName = candidate.getParentComponent()
               .getParentStream().getName();

            trickleUpdate.add(new CandidateAttribute(candidate));
        }

        trickleUpdate.add(0, sdpFactory.createAttribute(MID, streamName));

        return trickleUpdate;
    }
}
