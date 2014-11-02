/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import org.ice4j.*;

/**
 * The interface is used for collecting incoming ChannelData messages from the
 * NetAccessManager (and more precisely - MessageProcessors). This is our way of
 * keeping scalable network and stun layers.
 * 
 * @author Aakash Garg
 */
public interface ChannelDataEventHandler
{
    /**
     * Called when an incoming Channel Data message has been received, parsed
     * and is ready for delivery.
     * 
     * @param messageEvent the event object that encapsulates the newly received
     *                     message.
     */
    public void handleMessageEvent(ChannelDataMessageEvent messageEvent);
}
