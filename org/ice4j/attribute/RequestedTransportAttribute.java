/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.*;

/**
 * The REQUESTED-TRANSPORT attribute is used to allocate a
 * TURN address of certain transport protocol.
 *
 * In the original TURN specification, only UDP is supported.
 * Support of TCP is detailed in draft-ietf-behave-turn-tcp-07.
 *
 * @author Sebastien Vincent
 * @author Aakash Garg
 */
public class RequestedTransportAttribute extends Attribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "REQUESTED-TRANSPORT";

    /**
     * The length of the data contained by this attribute.
     */
    public static final char DATA_LENGTH = 4;

    public static final byte UDP = 17;

    public static final byte TCP = 6;

    /**
     * Transport protocol.
     *
     * 17 = UDP;
     * 6 = TCP.
     */
    byte transportProtocol = UDP;
    
    /**
     * Constructor.
     */
    RequestedTransportAttribute()
    {
        super(REQUESTED_TRANSPORT);
    }

    /**
     * Compares two STUN Attributes. Attributes are considered equal when their
     * type, length, and all data are the same.
     * @param obj the object to compare this attribute with.
     * @return true if the attributes are equal and false otherwise.
     */
    @Override
    public boolean equals(Object obj)
    {
        if (! (obj instanceof RequestedTransportAttribute)
                || obj == null)
            return false;

        if (obj == this)
            return true;

        RequestedTransportAttribute att = (RequestedTransportAttribute) obj;
        if (att.getAttributeType()   != getAttributeType()
                || att.getDataLength()   != getDataLength()
                /* compare data */
                || att.transportProtocol != transportProtocol
           )
            return false;

        return true;
    }

    /**
     * Returns the human readable name of this attribute. Attribute names do
     * not really matter from the protocol point of view. They are only used
     * for debugging and readability.
     * @return this attribute's name.
     */
    @Override
    public String getName()
    {
        return NAME;
    }

    /**
     * Returns the length of this attribute's body.
     * @return the length of this attribute's value (8 bytes).
     */
    @Override
    public char getDataLength()
    {
        return DATA_LENGTH;
    }

    /**
     * Returns a binary representation of this attribute.
     * @return a binary representation of this attribute.
     */
    @Override
    public byte[] encode()
    {
        byte binValue[] = new byte[HEADER_LENGTH + DATA_LENGTH];

        //Type
        binValue[0] = (byte)(getAttributeType() >> 8);
        binValue[1] = (byte)(getAttributeType() & 0x00FF);
        //Length
        binValue[2] = (byte)(getDataLength() >> 8);
        binValue[3] = (byte)(getDataLength() & 0x00FF);
        //Data
        binValue[4] = transportProtocol;
        binValue[5] = 0x00;
        binValue[6] = 0x00;
        binValue[7] = 0x00;

        return binValue;
    }

    /**
     * Sets this attribute's fields according to attributeValue array.
     * @param attributeValue a binary array containing this attribute's field
     *                       values and NOT containing the attribute header.
     * @param offset the position where attribute values begin (most often
     *          offset is equal to the index of the first byte after
     *          length)
     * @param length the length of the binary array.
     * @throws StunException if attrubteValue contains invalid data.
     */
    @Override
    void decodeAttributeBody(byte[] attributeValue, char offset, char length)
        throws StunException
    {
        if(length != 4)
        {
            throw new StunException("length invalid");
        }

        transportProtocol = attributeValue[offset];
    }

    /**
     * Set the transport protocol.
     * @param transportProtocol transport protocol
     */
    public void setRequestedTransport(byte transportProtocol)
    {
        this.transportProtocol = transportProtocol;
    }

    /**
     * Get the transport protocol.
     * @return transport protocol
     */
    public int getRequestedTransport()
    {
        return transportProtocol;
    }
}
