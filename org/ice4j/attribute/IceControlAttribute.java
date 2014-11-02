/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.*;

/**
 *
 * @author Emil Ivov
 */
public abstract class IceControlAttribute
    extends Attribute
{
    /**
     * The length of the data contained in this attribute
     */
    static final char DATA_LENGTH_ICE_CONTROL = 8;

    /**
     * The tie-breaker value stored in this attribute
     */
    long tieBreaker;

    /**
     * Indicates whether this is an <tt>ICE-CONTROLLING</tt> or an
     * <tt>ICE-CONTROLLED</tt> attribute.
     */
    boolean isControlling;

    /**
     * Constructs an ICE-CONTROLLING or an ICE-CONTROLLED attribute depending
     * on the value of <tt>isControlling</tt>.
     *
     * @param isControlling indicates the kind of attribute we are trying to
     * create
     */
    IceControlAttribute(boolean isControlling)
    {
        super(isControlling ? ICE_CONTROLLING : ICE_CONTROLLED);
        this.isControlling = isControlling;
    }

    /**
     * Sets this attribute's fields according to attributeValue array.
     *
     * @param attributeValue a binary array containing this attribute's field
     *                       values and NOT containing the attribute header.
     * @param offset the position where attribute values begin (most often
     *                  offset is equal to the index of the first byte after
     *                  length)
     * @param length the length of the attribute data.
     *
     * @throws StunException if attrubteValue contains invalid data.
     */
    void decodeAttributeBody(byte[] attributeValue, char offset, char length)
            throws StunException
    {
        // array used to hold the intermediate long values reconstructed from
        // the attributeValue array

        // Reading in the network byte order (Big-Endian)
        tieBreaker = ((attributeValue[offset++] & 0xffl) << 56)
                  | ((attributeValue[offset++] & 0xffl) << 48)
                  | ((attributeValue[offset++] & 0xffl) << 40)
                  | ((attributeValue[offset++] & 0xffl) << 32)
                  | ((attributeValue[offset++] & 0xffl) << 24)
                  | ((attributeValue[offset++] & 0xffl) << 16)
                  | ((attributeValue[offset++] & 0xffl) <<  8)
                  | (attributeValue[offset++]  & 0xffl);
    }

    /**
     * Returns a binary representation of this attribute.
     *
     * @return a binary representation of this attribute.
     */
    public byte[] encode()
    {
        char type = getAttributeType();
        byte[] binValue = new byte[HEADER_LENGTH + getDataLength()];

        //Type
        binValue[0] = (byte)(type >> 8);
        binValue[1] = (byte)(type & 0x00FF);

        //Length
        binValue[2] = (byte)(getDataLength() >> 8);
        binValue[3] = (byte)(getDataLength() & 0x00FF);

        //Tie-Breaker
        binValue[4]  = (byte)((tieBreaker & 0xFF00000000000000L) >> 56);
        binValue[5]  = (byte)((tieBreaker & 0x00FF000000000000L) >> 48);
        binValue[6]  = (byte)((tieBreaker & 0x0000FF0000000000L) >> 40);
        binValue[7]  = (byte)((tieBreaker & 0x000000FF00000000L) >> 32);
        binValue[8]  = (byte)((tieBreaker & 0x00000000FF000000L) >> 24);
        binValue[9]  = (byte)((tieBreaker & 0x0000000000FF0000L) >> 16);
        binValue[10] = (byte)((tieBreaker & 0x000000000000FF00L) >> 8);
        binValue[11] = (byte)( tieBreaker & 0x00000000000000FFL);

        return binValue;
    }

    /**
     * Compares two STUN Attributes. Attributes are considered equal when their
     * type, length, and all data are the same.
     *
     * @param obj the object to compare this attribute with.
     *
     * @return true if the attributes are equal and false otherwise.
     */
    public boolean equals(Object obj)
    {
        if(!(obj instanceof IceControlAttribute)
            || obj == null)
            return false;

        if(obj == this)
            return true;

        IceControlAttribute iceControlAtt = (IceControlAttribute)obj;
        if(iceControlAtt.getAttributeType() != getAttributeType()
            || iceControlAtt.isControlling != isControlling
            || iceControlAtt.getDataLength() != DATA_LENGTH_ICE_CONTROL
            || getTieBreaker() != iceControlAtt.getTieBreaker())
        {
            return false;
        }

        return true;
    }

    /**
     * Returns the data length of this attribute
     *
     * @return    the data length of this attribute
     */
    public char getDataLength()
    {
        return DATA_LENGTH_ICE_CONTROL;
    }

    /**
     * Returns the human readable name of this attribute.
     *
     * @return this attribute's name.
     */
    public String getName()
    {
        return isControlling ? "ICE-CONTROLLING" : "ICE-CONTROLLED";
    }

    /**
     * Sets the tie-breaker value.
     *
     * @param tieBreaker the the tie-breaker value
     */
    public void setTieBreaker(long tieBreaker)
    {
        this.tieBreaker = tieBreaker;
    }

    /**
     * Returns the value of the tie-breaker.
     *
     * @return the value of the tie-breaker.
     */
    public long getTieBreaker()
    {
        return tieBreaker;
    }
}
