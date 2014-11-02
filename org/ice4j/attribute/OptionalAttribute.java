/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import java.util.*;

import org.ice4j.*;

/**
 * This class is used for representing attributes not explicitly supported by
 * the stack. Such attributes will generally be kept in  binary form and won't
 * be subdued to any processing by the stack. One could use this class for both
 * dealing with attributes in received messages, and generating messages
 * containing attributes not explicitly supported by the stack.
 *
 * @author Emil Ivov
 */
public class OptionalAttribute
    extends Attribute
{
    byte[] attributeValue = null;

    protected OptionalAttribute(char attributeType)
    {
        super(attributeType);
    }

    /**
     * Sets this attribute's fields according to attributeValue array.
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
        this.attributeValue = new byte[length];
        System.arraycopy(attributeValue, offset, this.attributeValue, 0,
                length);
    }

    /**
     * Returns a binary representation of this attribute.
     *
     * @return a binary representation of this attribute.
     */
    public byte[] encode()
    {
        char type = getAttributeType();

        byte binValue[] = new byte[HEADER_LENGTH + attributeValue.length];

        //Type
        binValue[0] = (byte)(type >> 8);
        binValue[1] = (byte)(type & 0x00FF);
        //Length
        binValue[2] = (byte)(getDataLength() >> 8);
        binValue[3] = (byte)(getDataLength() & 0x00FF);

        System.arraycopy(attributeValue, 0,
                         binValue, HEADER_LENGTH, attributeValue.length);

        return binValue;
    }

    /**
     * Returns the length of this attribute's body.
     *
     * @return the length of this attribute's value.
     */
    public char getDataLength()
    {
        return (char)attributeValue.length;
    }

    /**
     * Returns the human readable name of this attribute.
     *
     * @return this attribute's name.
     */
    public String getName()
    {
        return "Unknown Attribute";
    }

    /**
     * Returns a reference to the unparsed body of this attribute.
     *
     * @return a reference to this attribute's unparsed value.
     */
    public byte[] getBody()
    {
        return attributeValue;
    }

    /**
     * Copies the speicified byte array segment as the body of this attribute.
     *
     * @param body the body to copy
     * @param offset the position to start
     * @param length the length to copy
     */
    public void setBody(byte[] body, int offset, int length)
    {
        this.attributeValue = new byte[length];
        System.arraycopy(body, offset, this.attributeValue, 0, length);
    }

    /**
     * Compares two STUN Attributes. Two attributes are considered equal when they
     * have the same type length and value.
     *
     * @param obj the object to compare this attribute with.
     * @return true if the attributes are equal and false otherwise.
     */
    public boolean equals(Object obj)
    {
        if(obj == null
          || !(obj instanceof OptionalAttribute) )
            return false;

        return
            (obj == this
             || Arrays.equals(((OptionalAttribute)obj).
                              attributeValue, attributeValue));
    }
}
