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
 * Classes implementing this interface follow the factory pattern, generating
 * DatagramSocket objects for use by other classes in the stack.
 * 
 * By extending this interface and using the method
 * DelegatingDatagramSocket#setDefaultDelegateFactory()
 * it is possible for the application developer to ensure their own variety
 * of DatagramSocket is used by the ice4j stack and passed back to their
 * application when the ICE protocol is completed.
 * 
 * @author Daniel Pocock
 * @author Vincent Lucas
 */
public interface DatagramSocketFactory
{
    /**
     * Creates an unbound DatagramSocket:
     * - i.e <tt>return new DatagramSocket((SocketAddress) null)</tt>.
     *
     * @return An unbound DatagramSocket.
     *
     * @throws SocketException if the socket could not be opened.
     */
    public DatagramSocket createUnboundDatagramSocket()
        throws SocketException;
}
