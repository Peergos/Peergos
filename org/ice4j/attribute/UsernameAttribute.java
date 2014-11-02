/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import java.util.*;

/**
 * The USERNAME attribute is used for message integrity.
 * The value of USERNAME is a variable length value.
 *
 * @author Sebastien Vincent
 * @author Emil Ivov
 */
public class UsernameAttribute extends Attribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "USERNAME";

    /**
     * Username value.
     */
    private byte username[] = null;

    /**
     * Constructor.
     */
    UsernameAttribute()
    {
        super(USERNAME);
    }

    /**
     * Copies the value of the username attribute from the specified
     * attributeValue.
     *
     * @param attributeValue a binary array containing this attribute's
     *   field values and NOT containing the attribute header.
     * @param offset the position where attribute values begin (most often
     *   offset is equal to the index of the first byte after length)
     * @param length the length of the binary array.
     */
    void decodeAttributeBody(byte[] attributeValue, char offset, char length)
    {
        username = new byte[length];
        System.arraycopy(attributeValue, offset, username, 0, length);
    }

    /**
     * Returns a binary representation of this attribute.
     *
     * @return a binary representation of this attribute.
     */
    public byte[] encode()
    {
        char type = getAttributeType();
        byte binValue[] = new byte[HEADER_LENGTH + getDataLength()
                                   //add padding
                                   + (4 - getDataLength() % 4) % 4];

        //Type
        binValue[0] = (byte)(type >> 8);
        binValue[1] = (byte)(type & 0x00FF);

        //Length
        binValue[2] = (byte)(getDataLength() >> 8);
        binValue[3] = (byte)(getDataLength() & 0x00FF);

        //username
        System.arraycopy(username, 0, binValue, 4, getDataLength());

        return binValue;
    }

    /**
     * Returns the length of this attribute's body.
     *
     * @return the length of this attribute's value.
     */
    public char getDataLength()
    {
        return (char)username.length;
    }

    /**
     * Returns the human readable name of this attribute.
     *
     * @return this attribute's name.
     */
    public String getName()
    {
        return NAME;
    }

    /**
     * Returns a (cloned) byte array containing the data value of the username
     * attribute.
     *
     * @return the binary array containing the username.
     */
    public byte[] getUsername()
    {
        return (username == null) ? null : username.clone();
    }

    /**
     * Copies the specified binary array into the the data value of the username
     * attribute.
     *
     * @param username the binary array containing the username.
     */
    public void setUsername(byte[] username)
    {
        if (username == null)
        {
            this.username = null;
            return;
        }

        this.username = new byte[username.length];
        System.arraycopy(username, 0, this.username, 0, username.length);
    }

    /**
     * Compares two STUN Attributes. Two attributes are considered equal when
     * they have the same type length and value.
     *
     * @param obj the object to compare this attribute with.
     *
     * @return true if the attributes are equal and false otherwise.
     */
    public boolean equals(Object obj)
    {
        if (! (obj instanceof UsernameAttribute) || obj == null)
            return false;

        if (obj == this)
            return true;

        UsernameAttribute att = (UsernameAttribute) obj;
        if (att.getAttributeType() != getAttributeType()
                || att.getDataLength() != getDataLength()
                || !Arrays.equals( att.username, username))
            return false;

        return true;
    }
}
