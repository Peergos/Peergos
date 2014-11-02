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
 * A <tt>DatagramPacketFilter</tt> which accepts only DTLS packets.
 *
 * @author Boris Grozev
 */
public class DTLSDatagramFilter
    implements DatagramPacketFilter
{
    /**
     * Returns <tt>true</tt> if <tt>p</tt> looks like a DTLS packet.
     * @param p the <tt>DatagramPacket</tt> to check.
     * @return <tt>true</tt>if <tt>p</tt> looks like a DTLS packet.
     */
    public static boolean isDTLS(DatagramPacket p)
    {
        byte[] data = p.getData();
        int off = p.getOffset();
        int len = p.getLength();

        if (len > 0)
        {
            int fb = data[off] & 0xff;
            return 19 < fb && fb < 64;
        }

        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean accept(DatagramPacket p)
    {
        return isDTLS(p);
    }
}
