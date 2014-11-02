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
 * Implements a <tt>DatagramPacketFilter</tt> which only accepts
 * <tt>DatagramPacket</tt>s which represent STUN messages defined in RFC 5389
 * "Session Traversal Utilities for NAT (STUN)" i.e. with method Binding or the
 * reserved method 0x000 and 0x002/SharedSecret.
 *
 * @author Lyubomir Marinov
 */
public class StunDatagramPacketFilter
    implements DatagramPacketFilter
{

    /**
     * The <tt>TransportAddress</tt> of the STUN server <tt>DatagramPacket</tt>s
     * representing STUN messages from and to which are accepted by this
     * instance.
     */
    protected final TransportAddress stunServer;

    /**
     * Initializes a new <tt>StunDatagramPacketFilter</tt> which will accept
     * <tt>DatagramPacket</tt>s which represent STUN messages received from
     * any destination.
     */
    public StunDatagramPacketFilter()
    {
        this(null);
    }

    /**
     * Initializes a new <tt>StunDatagramPacketFilter</tt> which will accept
     * <tt>DatagramPacket</tt>s which represent STUN messages and which are part
     * of the communication with a specific STUN server (or any server if
     * <tt>stunServer</tt> is <tt>null</tt>).
     *
     * @param stunServer the <tt>TransportAddress</tt> of the STUN server
     * <tt>DatagramPacket</tt>s representing STUN messages from and to which
     * will be accepted by the new instance or <tt>null</tt> if we would like
     * to accept stun messages from any destination.
     */
    public StunDatagramPacketFilter(TransportAddress stunServer)
    {
        this.stunServer = stunServer;
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> represents a STUN
     * message and whether it is part of the communication with the STUN server
     * if one was associated with this instance.
     *
     * @param p the <tt>DatagramPacket</tt> which is to be checked whether it is
     * a STUN message which is part of the communicator with the STUN server
     * associated with this instance
     * @return <tt>true</tt> if the specified <tt>DatagramPacket</tt> represents
     * a STUN message which is part of the communication with the STUN server
     * associated with this instance; otherwise, <tt>false</tt>
     */
    public boolean accept(DatagramPacket p)
    {
        /*
         * If we were instantiated for a specific STUN server, and the packet
         * did not originate there, we reject it.
         */
        if ((stunServer != null) && !stunServer.equals(p.getSocketAddress()))
            return false;

        // If this is a STUN packet.
        if (StunDatagramPacketFilter.isStunPacket(p))
        {
            byte[] data = p.getData();
            int offset = p.getOffset();

            byte b0 = data[offset];
            byte b1 = data[offset + 1];
            char method = (char) ((b0 & 0xFE) | (b1 & 0xEF));

            return acceptMethod(method);
        }
        return false;
    }

    /**
     * Determines whether this <tt>DatagramPacketFilter</tt> accepts a
     * <tt>DatagramPacket</tt> which represents a STUN message with a specific
     * STUN method. <tt>StunDatagramPacketFilter</tt> only accepts the method
     * Binding and the reserved methods 0x000 and 0x002/SharedSecret.
     *
     * @param method the STUN method of a STUN message represented by a
     * <tt>DatagramPacket</tt> to be checked whether it is accepted by this
     * <tt>DatagramPacketFilter</tt>
     * @return <tt>true</tt> if this <tt>DatagramPacketFilter</tt> accepts the
     * <tt>DatagramPacket</tt> which represents a STUN message with the
     * specified STUN method; otherwise, <tt>false</tt>
     */
    protected boolean acceptMethod(char method)
    {
        switch (method)
        {
        case Message.STUN_METHOD_BINDING:
        case 0x0000:
        case 0x0002:
            return true;
        default:
            return false;
        }
    }

    /**
     * Indicates whether some other object is "equal to" this one.
     *
     * @param obj the reference object with which to compare.
     * @return <tt>true</tt> if this <tt>StunDatagramPacketFilter</tt> is equal
     * to the <tt>obj</tt> argument; <tt>false</tt>, otherwise.
     */
    @Override
    public boolean equals(Object obj)
    {
        if (null == obj)
            return false;
        else if (this == obj)
            return true;
        else
            return getClass().equals(obj.getClass());
    }

    /**
     * Returns a hash code value for this object for the benefit of hashtables
     * such as those provided by <tt>Hashtable</tt>.
     *
     * @return a hash code value for this object
     */
    @Override
    public int hashCode()
    {
        /*
         * Overrides the super implementation in order to maintain the general
         * contract of the hashCode method which states that equal objects must
         * have equal hash codes.
         */
        return getClass().hashCode();
    }

    /**
     * Determines whether a specific <tt>DatagramPacket</tt> represents a STUN
     * (or TURN) packet.
     *
     * @param p the <tt>DatagramPacket</tt> which is to be checked whether it is
     * a STUN message which is part of the communicator with the STUN server
     * associated with this instance
     *
     * @return True if the <tt>DatagramPacket</tt> represents a STUN
     * (or TURN) packet. False, otherwise.
     */
    public static boolean isStunPacket(DatagramPacket p)
    {
        boolean isStunPacket = false;
        byte[] data = p.getData();
        int offset = p.getOffset();
        int length = p.getLength();

        // All STUN messages MUST start with a 20-byte header followed by zero
        // or more Attributes.
        if(length >= 20)
        {
            // If the MAGIC COOKIE is present this is a STUN packet (RFC5389
            // compliant).
            if(data[offset + 4] == Message.MAGIC_COOKIE[0]
                && data[offset + 5] == Message.MAGIC_COOKIE[1]
                && data[offset + 6] == Message.MAGIC_COOKIE[2]
                && data[offset + 7] == Message.MAGIC_COOKIE[3])
            {
                isStunPacket = true;
            }
            // Else, this packet may be a STUN packet (RFC3489 compliant). To
            // determine this, we must continue the checks.
            else
            {
                // The most significant 2 bits of every STUN message MUST be
                // zeroes.  This can be used to differentiate STUN packets from
                // other protocols when STUN is multiplexed with other protocols
                // on the same port.
                byte b0 = data[offset];
                boolean areFirstTwoBitsValid = ((b0 & 0xC0) == 0);

                // Checks if the length of the data correspond to the length
                // field of the STUN header. The message length field of the
                // STUN header does not include the 20-byte of the STUN header.
                int total_header_length
                    = ((((int)data[2]) & 0xff) << 8)
                    + (((int) data[3]) & 0xff)
                    + 20;
                boolean isHeaderLengthValid = (length == total_header_length);

                isStunPacket = areFirstTwoBitsValid && isHeaderLengthValid;
            }
        }
        return isStunPacket;
    }
}
