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
 * Is implemented by @link(PseudoTcpSocket) to expose stream functionality.
 * @author Pawel Domas
 */
interface IPseudoTcpNotify
{
    /**
     * Called when tcp enters opened state
     * @param tcp 
     */
    void OnTcpOpen(PseudoTCPBase tcp);

    /**
     * Called when any data is available in read buffer
     * @param tcp 
     */
    void OnTcpReadable(PseudoTCPBase tcp);

    /**
     * Called when there is free space available in the send buffer
     * @param tcp 
     */
    void OnTcpWriteable(PseudoTCPBase tcp);

    /**
     * Called when tcp enters closed state
     * @param tcp
     * @param e null means no error
     */
    void OnTcpClosed(PseudoTCPBase tcp, IOException e);

    /**
     * Called when protocol requests packet transfer through the network.
     * @param tcp
     * @param buffer data
     * @param len data length
     * @return the result, see {@link WriteResult} description for more info
     */    
    WriteResult TcpWritePacket(PseudoTCPBase tcp, byte[] buffer, int len);
}
