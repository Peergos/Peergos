/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

import java.net.*;

/**
 * 
 * @author Pawel Domas
 */
public class PseudoTcpJavaSocket extends Socket {
	public PseudoTcpJavaSocket(long conv_id) throws SocketException {
		super(new PseudoTcpSocketImpl(conv_id));
	}

	public PseudoTcpJavaSocket(long conv_id, DatagramSocket socket)
			throws SocketException {
		super(new PseudoTcpSocketImpl(conv_id, socket));
	}

}
