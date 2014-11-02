/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.net.*;

/**
 * Implements a <tt>DatagramPacketFilter</tt> which only accepts
 * <tt>DatagramPacket</tt>s which represent RTCP messages according to the rules
 * described in RFC5761.
 *
 * @author Emil Ivov
 * @author Boris Grozev
 */
public class RtcpDemuxPacketFilter
    implements DatagramPacketFilter
{
    /**
     * Determines whether a specific <tt>DatagramPacket</tt> is an RTCP.
     * <tt>DatagramPacket</tt> in a selection based on this filter.
     *
     * RTP/RTCP packets are distinguished from other packets (such as STUN,
     * DTLS or ZRTP) by the value of their first byte. See
     * {@link "http://tools.ietf.org/html/rfc5764#section-5.1.2"} and
     * {@link "http://tools.ietf.org/html/rfc6189#section-5"}.
     *
     * RTCP packets are distinguished from RTP packet based on the second byte
     * (either Packet Type (RTCP) or M-bit and Payload Type (RTP). See
     * {@link "http://tools.ietf.org/html/rfc5761#section-4"}
     *
     * We assume that RTCP packets have a packet type in [200, 211]. This means
     * that RTP packets with Payload Types in [72, 83] (which should not
     * appear, because these PTs are reserved or unassigned by IANA, see
     * {@link "http://www.iana.org/assignments/rtp-parameters/rtp-parameters.xhtml"}
     * ) with the M-bit set will be misidentified as RTCP packets.
     * Also, any RTCP packets with Packet Types not in [200, 211] will be
     * misidentified as RTP packets.
     *
     * @param p the <tt>DatagramPacket</tt> whose protocol we'd like to
     * determine.
     * @return <tt>true</tt> if <tt>p</tt> is an RTCP and this filter accepts it
     * and <tt>false</tt> otherwise.
     */
    public static boolean isRtcpPacket(DatagramPacket p)
    {
        int len = p.getLength();
        if (len >= 4) //minimum RTCP message length
        {
            byte[] data = p.getData();
            int off = p.getOffset();

            if (((data[off + 0] & 0xc0) >> 6) == 2) //RTP/RTCP version field
            {
                int pt = data[off + 1] & 0xff;
                return (200 <= pt && pt <= 211);
            }
        }

        return false;
    }

    /**
     * Returns <tt>true</tt> if this <tt>RtcpDemuxPacketFilter</tt> should
     * accept <tt>p</tt>, that is, if <tt>p</tt> looks like an RTCP packet.
     * See {@link #isRtcpPacket(java.net.DatagramPacket)}
     * @return <tt>true</tt> if <tt>p</tt> looks like an RTCP packet.
     */
    public boolean accept(DatagramPacket p)
    {
        return isRtcpPacket(p);
    }

}
