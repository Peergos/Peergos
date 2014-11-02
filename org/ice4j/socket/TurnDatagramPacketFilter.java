/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.net.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * Implements a <tt>DatagramPacketFilter</tt> which accepts
 * <tt>DatagramPacket</tt>s which represent TURN messages defined in
 * RFC 5766 "Traversal Using Relays around NAT (TURN): Relay Extensions to
 * Session Traversal Utilities for NAT (STUN)" and which are part of the
 * communication with a specific TURN server. <tt>TurnDatagramPacketFilter</tt>
 * does not accept TURN ChannelData messages because they require knowledge of
 * the value of the "Channel Number" field.
 *
 * @author Lubomir Marinov
 */
public class TurnDatagramPacketFilter
    extends StunDatagramPacketFilter
{

    /**
     * Initializes a new <tt>TurnDatagramPacketFilter</tt> which will accept
     * <tt>DatagramPacket</tt>s which represent TURN messages and which are part
     * of the communication with a specific TURN server.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server
     * <tt>DatagramPacket</tt>s representing TURN messages from and to which
     * will be accepted by the new instance
     */
    public TurnDatagramPacketFilter(TransportAddress turnServer)
    {
        super(turnServer);
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> represents a TURN
     * message which is part of the communication with the TURN server
     * associated with this instance.
     *
     * @param p the <tt>DatagramPacket</tt> to be checked whether it represents
     * a TURN message which is part of the communicator with the TURN server
     * associated with this instance
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> represents
     * a TURN message which is part of the communication with the TURN server
     * associated with this instance; otherwise, <tt>false</tt>
     */
    @Override
    public boolean accept(DatagramPacket p)
    {
        if (super.accept(p))
        {
            /*
             * The specified DatagramPacket represents a STUN message with a
             * TURN method.
             */
            return true;
        }
        else
        {

            /*
             * The specified DatagramPacket does not come from or is not being
             * sent to the TURN server associated with this instance or is a
             * ChannelData message which is not supported by
             * TurnDatagramPacketFilter.
             */
            return false;
        }
    }

    /**
     * Determines whether this <tt>DatagramPacketFilter</tt> accepts a
     * <tt>DatagramPacket</tt> which represents a STUN message with a specific
     * STUN method. <tt>TurnDatagramPacketFilter</tt> accepts TURN methods.
     *
     * @param method the STUN method of a STUN message represented by a
     * <tt>DatagramPacket</tt> to be checked whether it is accepted by this
     * <tt>DatagramPacketFilter</tt>
     * @return <tt>true</tt> if this <tt>DatagramPacketFilter</tt> accepts the
     * <tt>DatagramPacket</tt> which represents a STUN message with the
     * specified STUN method; otherwise, <tt>false</tt>
     * @see StunDatagramPacketFilter#acceptMethod(char)
     */
    @Override
    protected boolean acceptMethod(char method)
    {
        if (super.acceptMethod(method))
            return true;
        else
        {
            switch (method)
            {
            case Message.TURN_METHOD_ALLOCATE:
            case Message.TURN_METHOD_CHANNELBIND:
            case Message.TURN_METHOD_CREATEPERMISSION:
            case Message.TURN_METHOD_DATA:
            case Message.TURN_METHOD_REFRESH:
            case Message.TURN_METHOD_SEND:
            case 0x0005: /* old TURN DATA indication */
                return true;
            default:
                return false;
            }
        }
    }
}
