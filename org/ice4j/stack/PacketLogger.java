/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

/**
 * The interface which interested implementers will use in order
 * to track and log packets send and received by this stack.
 * 
 * @author Damian Minkov
 */
public interface PacketLogger
{
    /**
     * Logs a incoming or outgoing packet.
     *
     * @param sourceAddress the source address of the packet.
     * @param sourcePort the source port.
     * @param destinationAddress the destination address of the packet.
     * @param destinationPort the destination port.
     * @param packetContent the content of the packet.
     * @param sender whether we are sending or not the packet.
     */
    public void logPacket(
            byte[] sourceAddress,
            int sourcePort,
            byte[] destinationAddress,
            int destinationPort,
            byte[] packetContent,
            boolean sender);

    /**
     * Checks whether the logger is enabled. 
     * @return <tt>true</tt> if the logger is enabled, <tt>false</tt>
     *  otherwise.
     */
    public boolean isEnabled();
}
