/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 * 
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

import java.util.*;

import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * The class is used to dispatch incoming ChannelData messages. Apart from the
 * message itself one could also obtain the address from where the message is
 * coming (used by a server implementation to determine the mapped address) as
 * well as the Descriptor of the NetAccessPoint that received it (In case the
 * stack is used on more than one ports/addresses).
 * 
 * @author Aakash Garg
 */
public class ChannelDataMessageEvent
    extends EventObject
{
    /**
     * A dummy version UID to suppress warnings.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The <tt>StunStack</tt> associated with this instance.
     */
    private final StunStack stunStack;

    /**
     * Remote address causing this event.
     */
    private final TransportAddress remoteAddress;

    /**
     * Local address receiving this event.
     */
    private final TransportAddress localAddress;
    
    /**
     * The ChannelData <tt>Message</tt> associated with this event.
     */
    private final ChannelData channelDataMessage;

    /**
     * Initializes a new <tt>ChannelDataMessageEvent</tt> associated with a
     * specific ChannelData <tt>Message</tt>.
     * 
     * @param stunStack the <tt>StunStack</tt> to be associated with the new
     *            instance.
     * @param remoteAddress the <tt>TransportAddress</tt> which is to be
     *            reported as the source of the new event.
     * @param localAddress the <tt>TransportAddress</tt> which is to be reported
     *            as the receiving location of the new event.
     * @param channelDataMessage the ChannelData <tt>Message</tt> associated
     *            with the new event.
     */
    public ChannelDataMessageEvent(StunStack stunStack,
        TransportAddress remoteAddress,
        TransportAddress localAddress,
        ChannelData channelDataMessage)
    {
        super(remoteAddress);
        
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
        this.stunStack = stunStack;
        this.channelDataMessage = channelDataMessage;
    }

    /**
     * Gets the ChannelData <tt>Message</tt> associated with this event.
     * 
     * @return the ChannelData <tt>Message</tt> associated with this event
     */
    public ChannelData getChannelDataMessage()
    {
        return channelDataMessage;
    }

    /**
     * Gets the <tt>TransportAddress</tt> which is the remote address of this
     * event.
     * 
     * @return the <tt>TransportAddress</tt> which is the address who caused
     *         this event
     */
    public TransportAddress getRemoteAddress()
    {
        return this.remoteAddress;
    }
    
    /**
     * Gets the <tt>TransportAddress</tt> which is local address on which this
     * event was received.
     */
    public TransportAddress getLocalAddress()
    {
        return this.localAddress;
    }

    /**
     * Gets the <tt>StunStack</tt> associated with this instance.
     * 
     * @return the <tt>StunStack</tt> associated with this instance
     */
    public StunStack getStunStack()
    {
        return stunStack;
    }

}
