/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package test;

import java.util.*;
import java.util.StringTokenizer;

import javax.sdp.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.ice.Agent;
import org.ice4j.ice.sdp.*;

/**
 * Utilities for manipulating SDP. Some of the utilities in this method <b>do
 * not</b> try to act smart and make a lot of assumptions (e.g. at least one
 * media stream with at least one component) that may not always be true in real
 * life and lead to exceptions. Therefore, make sure you reread the code if
 * reusing it in an application. It should be fine for the purposes of our ice4j
 * examples though.
 *
 * @author Emil Ivov
 */
public class SdpUtils
{
    /**
     * Creates a session description containing the streams from the specified
     * <tt>agent</tt> using dummy codecs. This method is unlikely to be of use
     * to integrating applications as they would likely just want to feed a
     * {@link MediaDescription} and have it populated with all the necessary
     * attributes.
     *
     * @param agent the {@link Agent} we'd like to generate.
     *
     * @return a {@link SessionDescription} representing <tt>agent</tt>'s
     * streams.
     *
     * @throws Throwable on rainy days
     */
    public static String createSDPDescription(Agent agent) throws Throwable
    {
        SdpFactory factory = SdpFactory.getInstance();
        SessionDescription sdess = factory.createSessionDescription();

        IceSdpUtils.initSessionDescription(sdess, agent);

        return sdess.toString();
    }

    /**
     * Configures <tt>localAgent</tt> the the remote peer streams, components,
     * and candidates specified in <tt>sdp</tt>
     *
     * @param localAgent the {@link Agent} that we'd like to configure.
     *
     * @param sdp the SDP string that the remote peer sent.
     *
     * @throws Exception for all sorts of reasons.
     */
    @SuppressWarnings("unchecked") // jain-sdp legacy code.
    public static void parseSDP(Agent localAgent, String sdp)
        throws Exception
    {
        SdpFactory factory = SdpFactory.getInstance();
        SessionDescription sdess = factory.createSessionDescription(sdp);

        for(IceMediaStream stream : localAgent.getStreams())
        {
            stream.setRemotePassword(sdess.getAttribute("ice-pwd"));
            stream.setRemoteUfrag(sdess.getAttribute("ice-ufrag"));
        }

        Connection globalConn = sdess.getConnection();
        String globalConnAddr = null;
        if(globalConn != null)
            globalConnAddr = globalConn.getAddress();

        Vector<MediaDescription> mdescs = sdess.getMediaDescriptions(true);

        for (MediaDescription desc : mdescs)
        {
            String streamName = desc.getMedia().getMediaType();

            IceMediaStream stream = localAgent.getStream(streamName);

            if(stream == null)
                continue;

            Vector<Attribute> attributes = desc.getAttributes(true);
            for( Attribute attribute : attributes)
            {
                if(!attribute.getName().equals(CandidateAttribute.NAME))
                    continue;

                parseCandidate(attribute, stream);
            }

            //set default candidates
            Connection streamConn = desc.getConnection();
            String streamConnAddr = null;
            if(streamConn != null)
                streamConnAddr = streamConn.getAddress();
            else
                streamConnAddr = globalConnAddr;

            int port = desc.getMedia().getMediaPort();

            TransportAddress defaultRtpAddress =
                new TransportAddress(streamConnAddr, port, Transport.UDP);

            int rtcpPort = port + 1;
            String rtcpAttributeValue = desc.getAttribute("rtcp");

            if (rtcpAttributeValue != null)
                rtcpPort = Integer.parseInt(rtcpAttributeValue);

            TransportAddress defaultRtcpAddress =
                new TransportAddress(streamConnAddr, rtcpPort, Transport.UDP);

            Component rtpComponent = stream.getComponent(Component.RTP);
            Component rtcpComponent = stream.getComponent(Component.RTCP);

            Candidate<?> defaultRtpCandidate
                = rtpComponent.findRemoteCandidate(defaultRtpAddress);
            rtpComponent.setDefaultRemoteCandidate(defaultRtpCandidate);

            if(rtcpComponent != null)
            {
                Candidate<?> defaultRtcpCandidate
                    = rtcpComponent.findRemoteCandidate(defaultRtcpAddress);
                rtcpComponent.setDefaultRemoteCandidate(defaultRtcpCandidate);
            }
        }
    }

    /**
     * Parses the <tt>attribute</tt>.
     *
     * @param attribute the attribute that we need to parse.
     * @param stream the {@link IceMediaStream} that the candidate is supposed
     * to belong to.
     *
     * @return a newly created {@link RemoteCandidate} matching the
     * content of the specified <tt>attribute</tt> or <tt>null</tt> if the
     * candidate belonged to a component we don't have.
     */
    private static RemoteCandidate parseCandidate(Attribute      attribute,
                                                  IceMediaStream stream)
    {
        String value = null;

        try{
            value = attribute.getValue();
        }catch (Throwable t){}//can't happen

        StringTokenizer tokenizer = new StringTokenizer(value);

        //XXX add exception handling.
        String foundation = tokenizer.nextToken();
        int componentID = Integer.parseInt( tokenizer.nextToken() );
        Transport transport = Transport.parse(tokenizer.nextToken());
        long priority = Long.parseLong(tokenizer.nextToken());
        String address = tokenizer.nextToken();
        int port = Integer.parseInt(tokenizer.nextToken());

        TransportAddress transAddr
            = new TransportAddress(address, port, transport);

        tokenizer.nextToken(); //skip the "typ" String
        CandidateType type = CandidateType.parse(tokenizer.nextToken());

        Component component = stream.getComponent(componentID);

        if(component == null)
            return null;

        // check if there's a related address property

        RemoteCandidate relatedCandidate = null;
        if (tokenizer.countTokens() >= 4)
        {
            tokenizer.nextToken(); // skip the raddr element
            String relatedAddr = tokenizer.nextToken();
            tokenizer.nextToken(); // skip the rport element
            int relatedPort = Integer.parseInt(tokenizer.nextToken());

            TransportAddress raddr = new TransportAddress(
                            relatedAddr, relatedPort, Transport.UDP);

            relatedCandidate = component.findRemoteCandidate(raddr);
        }

        RemoteCandidate cand = new RemoteCandidate(transAddr, component, type,
                        foundation, priority, relatedCandidate);

        component.addRemoteCandidate(cand);

        return cand;
    }


}
