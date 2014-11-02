/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

import java.io.*;

/**
 * Notification of tcp events.
 * Is implemented by <tt>PseudoTcpSocketImpl</tt> to expose stream functionality.
 *
 * @author Pawel Domas
 */
public interface PseudoTcpNotify
{
    /**
     * Called when TCP enters opened state
     * @param tcp 
     */
    void onTcpOpen(PseudoTCPBase tcp);

    /**
     * Called when any data is available in read buffer
     * @param tcp 
     */
    void onTcpReadable(PseudoTCPBase tcp);

    /**
     * Called when there is free space available in the send buffer
     * @param tcp 
     */
    void onTcpWriteable(PseudoTCPBase tcp);

    /**
     * Called when tcp enters closed state
     * @param tcp
     * @param e null means no error
     */
    void onTcpClosed(PseudoTCPBase tcp, IOException e);

    /**
     * Called when protocol requests packet transfer through the network.
     * @param tcp
     * @param buffer data
     * @param len data length
     * @return the result, see {@link WriteResult} description for more info
     */    
    WriteResult tcpWritePacket(PseudoTCPBase tcp, byte[] buffer, int len);
}
