/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.StunException;

/**
 * The requested address family attribute defined in RFC 6156.
 *
 * @author Aakash Garg
 */
public class RequestedAddressFamilyAttribute extends Attribute 
{
    /**
     * Attribute Name.
     */
    public static final String NAME  = "REQUESTED-ADDRESS-FAMILY";
    
    /**
     * The length of the data contained in this attribute.
     */
    public static final char DATA_LENGTH = 1;
    
    /**
     * The IPv4 family type.
     */
    public static final char IPv4 = 0x01;
    
    /**
     * The IPv6 family type.
     */
    public static final char IPv6 = 0x02;

    /**
     * The address family value.
     */
    char family = IPv4;
    
    /**
     * Constructor.
     */
    protected RequestedAddressFamilyAttribute() 
    {
		super(REQUESTED_ADDRESS_FAMILY);
    }

    /**
     * Returns the length of this attribute's body.
     * @return the length of this attribute's value (1 byte).
     */
    @Override
    public char getDataLength() 
    {
		return DATA_LENGTH;
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
     * Compares two TURN Attributes. Attributes are considered equal when their
     * type, length, and all data are the same.
     * @param obj the object to compare this attribute with.
     * @return true if the attributes are equal and false otherwise.
     */
    @Override
    public boolean equals(Object obj) 
    {
	    if (! (obj instanceof RequestedAddressFamilyAttribute))
            return false;

        if (obj == this)
            return true;

        RequestedAddressFamilyAttribute att
            = (RequestedAddressFamilyAttribute) obj;

        if (att.getAttributeType() != getAttributeType()
                || att.getDataLength() != getDataLength()
                /* compare data */
                || att.family != family
           )
            return false;

        return true;
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
        binValue[4] = (byte) family;

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
	    if(length != DATA_LENGTH)
        {
            throw new StunException("length invalid: " + length);
        }

        family = (char)(attributeValue[offset] & 0xff);

        if(family != IPv4 && family != IPv6)
        {
            // instead throw TurnException
            throw new StunException("invalid family value: " + family);
        }
    }

    /**
     * Gets the address family value
     * @return family the address family value
     */
    public char getFamily() 
    {
        return family;
    }
    
    /**
     * Sets the address family value
     * @param family the address family value to set
     * @return true if argument is IPv4 or IPv6 otherwise false
     */
    public boolean setFamily(char family) 
    {	
    	if(family == IPv4 || family == IPv6)
        {
            this.family = family;
            return true;
        }
        else
        {
            return false;
        }
    }
    
}
