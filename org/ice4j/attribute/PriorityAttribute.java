/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.*;

/**
 * This class is used to represent the PRIORITY attribute used for ICE processing
 * This attribute is not in the original specification of STUN
 * It is added as an extension to STUN to be used for ICE implementations
 *
 * PRIORITY attribute contains a 32 bit priority value
 * It is used in stun binding requests sent from ICE-Agents to their
 * peers
 *
 * @author Namal Senarathne
 */
public class PriorityAttribute
    extends Attribute
{
    /**
     * The length of the Data contained in the Priority Attribute
     */
    private static final char DATA_LENGTH_PRIORITY = 4;

    /**
     * The priority value specified in the attribute. An int should be enough
     * to store this value, but long is used, since candidate and candidate-pair
     * classes use long to store priority values
     */
    private long priority = 0;

    /**
     * Creates a priority attribute.
     */
    public PriorityAttribute()
    {
        super(PRIORITY);
    }

     /**
     * Reconstructs the priority value from the given byte array
     * and stores it in the 'long' variable
     *
     * @param attributeValue a binary array containing this attribute's
     *   field values and NOT containing the attribute header.
     * @param offset the position where attribute values begin (most often
     *   offset is equal to the index of the first byte after length)
     * @param length the length of the binary array.
     * @throws StunException if attrubteValue contains invalid data.
     */
    void decodeAttributeBody(byte[] attributeValue, char offset, char length)
            throws StunException
    {

        // array used to hold the intermediate long values reconstructed from
        // the attributeValue array
        long[] values = new long[4];

        // Reading in the network byte order (Big-Endian)
        values[0] = (long)((attributeValue[offset++] & 0xff) << 24);
        values[1] = (long)((attributeValue[offset++] & 0xff) << 16);
        values[2] = (long)((attributeValue[offset++] & 0xff) << 8);
        values[3] = (long)(attributeValue[offset++] & 0xff);

        // reconstructing the priority value
        this.priority = values[0] | values[1] | values[2] | values[3];
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

        //Priority
        binValue[4] = (byte)((priority & 0xFF000000L) >> 24);
        binValue[5] = (byte)((priority & 0x00FF0000L) >> 16);
        binValue[6] = (byte)((priority & 0x0000FF00L) >> 8);
        binValue[7] = (byte)(priority & 0x000000FFL);

        return binValue;
    }

    /**
     * Compares two STUN Attributes. Two attributes are considered equal when
     * they have the same type length and value.
     *
     * @param obj the object to compare this attribute with.
     * @return true if the attributes are equal and false otherwise.
     */
    public boolean equals(Object obj)
    {
        if (! (obj instanceof PriorityAttribute)
            || obj == null)
            return false;

        if (obj == this)
            return true;

        PriorityAttribute att = (PriorityAttribute) obj;
        if (att.getAttributeType() != getAttributeType()
            || att.getDataLength() != getDataLength()
            || (priority != att.priority))
            return false;

        return true;
    }

    /**
     * Returns the length of this attribute's body.
     *
     * @return the length of this attribute's value.
     */
    public char getDataLength()
    {
        return DATA_LENGTH_PRIORITY;
    }

    /**
     * Returns the human readable name of this attribute.
     *
     * @return this attribute's name.
     */
    public String getName()
    {
        return "PRIORITY";
    }

    /**
     * Returns the priority specified in the PRIORITY Attribute
     *
     * @return long specifying the priority
     */
    public long getPriority()
    {
        return priority;
    }

    /**
     * Sets the priority of the PRIORITY Attribute with the specified value
     *
     * @param priority     the long variable specifying the priority value
     *
     * @throws IllegalArgumentException if indicated priority value is illegal.
     */
    public void setPriority(long priority)
        throws IllegalArgumentException
    {
        /* Priority must be between 1 and (2^31 - 1) */
        if(priority <= 0 || priority > 0x7FFFFFFFL)
        {
            throw new IllegalArgumentException("Priority must be " +
                    "between 0 and (2**31 - 1)");
        }
        else
            this.priority = priority;
    }
}
