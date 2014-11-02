/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * Handles incoming requests.
 *
 * @author Emil Ivov
 */
public interface RequestListener
{
    /**
     * Called when delivering incoming STUN requests. Throwing an
     * {@link IllegalArgumentException} from within this method would cause the
     * stack to reply with a <tt>400 Bad Request</tt> {@link Response} using
     * the exception's message as a reason phrase for the error response. Any
     * other exception would result in a <tt>500 Server Error</tt> {@link
     * Response}.
     *
     * @param evt the event containing the incoming STUN request.
     *
     * @throws IllegalArgumentException if <tt>evt</tt> contains a malformed
     * {@link Request} and the stack would need to response with a
     * <tt>400 Bad Request</tt> {@link Response}.
     */
    public void processRequest(StunMessageEvent evt)
        throws IllegalArgumentException;
}
