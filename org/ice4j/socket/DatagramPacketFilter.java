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
 * Represents a filter which selects or deselects <tt>DatagramPacket</tt>s.
 *
 * @author Lubomir Marinov
 */
public interface DatagramPacketFilter
{
    /**
     * Determines whether a specific <tt>DatagramPacket</tt> is accepted by this
     * filter i.e. whether the caller should include the specified
     * <tt>DatagramPacket</tt> in a selection based on this filter.
     *
     * @param p the <tt>DatagramPacket</tt> which is to be checked whether it is
     * accepted by this filter
     * @return <tt>true</tt> if this filter accepts the specified
     * <tt>DatagramPacket</tt> i.e. if the caller should include the specified
     * <tt>DatagramPacket</tt> in a selection based on this filter; otherwise,
     * <tt>false</tt>
     */
    public boolean accept(DatagramPacket p);
}
