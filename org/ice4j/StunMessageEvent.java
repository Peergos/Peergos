/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * The class is used to dispatch incoming stun messages. Apart from the message
 * itself one could also obtain the address from where the message is coming
 * (used by a server implementation to determine the mapped address)
 * as well as the Descriptor of the NetAccessPoint that received it (In case the
 * stack is used on more than one ports/addresses).
 *
 * @author Emil Ivov
 */
public class StunMessageEvent
    extends BaseStunMessageEvent
{
    /**
     * Serial version UID for this Serializable class.
     */
    private static final long serialVersionUID = 41267843L;

    /**
     * The message as we got it off the wire.
     */
    private final RawMessage rawMessage;

    /**
     * Constructs a StunMessageEvent according to the specified message.
     *
     * @param stunStack the <tt>StunStack</tt> to be associated with the new
     * instance
     * @param rawMessage the crude message we got off the wire.
     * @param parsedMessage the message itself
     */
    public StunMessageEvent(
            StunStack stunStack,
            RawMessage rawMessage,
            Message parsedMessage)
    {
        super(stunStack, rawMessage.getLocalAddress(), parsedMessage);

        this.rawMessage = rawMessage;
    }

    /**
     * Returns a <tt>TransportAddress</tt> referencing the access point where
     * the message was received.
     *
     * @return a descriptor of the access point where the message arrived.
     */
    public TransportAddress getLocalAddress()
    {
        return getSourceAddress();
    }

    /**
     * Returns the address that sent the message.
     *
     * @return the address that sent the message.
     */
    public TransportAddress getRemoteAddress()
    {
        return rawMessage.getRemoteAddress();
    }

    /**
     * Returns a <tt>String</tt> representation of this event, containing the
     * corresponding message, remote and local addresses.
     *
     * @return a <tt>String</tt> representation of this event, containing the
     * corresponding message, remote and local addresses.
     */
    @Override
    public String toString()
    {
        StringBuffer buff = new StringBuffer("StunMessageEvent:\n\tMessage=");

        buff.append(getMessage());
        buff.append(" remoteAddr=").append(getRemoteAddress());
        buff.append(" localAddr=").append(getLocalAddress());

        return buff.toString();
    }

    /**
     * Returns the raw message that caused this event.
     *
     * @return the {@link RawMessage} that caused this event.
     */
    public RawMessage getRawMessage()
    {
        return rawMessage;
    }
}
