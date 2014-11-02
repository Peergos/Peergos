/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import java.net.*;

import org.ice4j.*;

/**
 * This class is used to represent Stun attributes that contain an address. Such
 * attributes are:
 *<p>
 * MAPPED-ADDRESS <br/>
 * RESPONSE-ADDRESS <br/>
 * SOURCE-ADDRESS <br/>
 * CHANGED-ADDRESS <br/>
 * REFLECTED-FROM <br/>
 * ALTERNATE-SERVER <br/>
 * XOR-PEER-ADDRESS <br/>
 * XOR-RELAYED-ADDRESS <br/>
 *</p>
 *<p>
 * The different attributes are distinguished by the attributeType of
 * org.ice4j.attribute.Attribute.
 *</p>
 *<p>
 * Address attributes indicate the mapped IP address and
 * port.  They consist of an eight bit address family, and a sixteen bit
 * port, followed by a fixed length value representing the IP address.
 *<code>
 *  0                   1                   2                   3          <br/>
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1        <br/>
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+       <br/>
 * |x x x x x x x x|    Family     |           Port                |       <br/>
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+       <br/>
 * |                             Address                           |       <br/>
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+       <br/>
 *                                                                         <br/>
 * </p>
 * <p>
 * The port is a network byte ordered representation of the mapped port.
 * The address family is always 0x01, corresponding to IPv4.  The first
 * 8 bits of the MAPPED-ADDRESS are ignored, for the purposes of
 * aligning parameters on natural boundaries.  The IPv4 address is 32
 * bits.
 * </p>
 * @author Emil Ivov
 */
abstract class AddressAttribute extends Attribute
{
    /**
     * Indicates that this attribute is transporting an IPv4 address
     */
    static final byte ADDRESS_FAMILY_IPV4 = 0x01;

    /**
     * Indicates that this attribute is transporting an IPv6 address
     */
    static final byte ADDRESS_FAMILY_IPV6 = 0x02;

     /**
      * The address represented by this message;
      */
     protected TransportAddress address = null;

     /**
      * The length of the data contained by this attribute in the case of an
      * IPv6 address.
      */
     private static final char DATA_LENGTH_FOR_IPV6 = 20;

     /**
      * The length of the data contained by this attribute in the case of an
      * IPv4 address.
      */
     private static final char DATA_LENGTH_FOR_IPV4 = 8;

    /**
     * Constructs an address attribute with the specified type.
     *
     * @param attributeType the type of the address attribute.
     */
    AddressAttribute(char attributeType)
    {
        super(attributeType);
    }
    /**
     * Verifies that type is a valid address attribute type.
     * @param type the type to test
     * @return true if the type is a valid address attribute type and false
     * otherwise
     */
    private boolean isTypeValid(char type)
    {
        return (type == MAPPED_ADDRESS || type == RESPONSE_ADDRESS
                || type == SOURCE_ADDRESS || type == CHANGED_ADDRESS
                || type == REFLECTED_FROM || type == XOR_MAPPED_ADDRESS
                || type == ALTERNATE_SERVER || type == XOR_PEER_ADDRESS
                || type == XOR_RELAYED_ADDRESS || type == DESTINATION_ADDRESS);
    }

    /**
     * Sets it as this attribute's type.
     *
     * @param type the new type of the attribute.
     */
    protected void setAttributeType(char  type)
    {
        if (!isTypeValid(type))
            throw new IllegalArgumentException(((int)type)
                                + "is not a valid address attribute!");

        super.setAttributeType(type);
    }

    /**
     * Returns the human readable name of this attribute. Attribute names do
     * not really matter from the protocol point of view. They are only used
     * for debugging and readability.
     * @return this attribute's name.
     */
    public String getName()
    {
        switch(getAttributeType())
        {
            case MAPPED_ADDRESS:     return MappedAddressAttribute.NAME;
            case RESPONSE_ADDRESS:   return ResponseAddressAttribute.NAME;
            case SOURCE_ADDRESS:     return SourceAddressAttribute.NAME;
            case CHANGED_ADDRESS:    return ChangedAddressAttribute.NAME;
            case REFLECTED_FROM:     return ReflectedFromAttribute.NAME;
            case XOR_MAPPED_ADDRESS: return XorMappedAddressAttribute.NAME;
            case ALTERNATE_SERVER:   return AlternateServerAttribute.NAME;
            case XOR_PEER_ADDRESS:   return XorPeerAddressAttribute.NAME;
            case XOR_RELAYED_ADDRESS:return XorRelayedAddressAttribute.NAME;
        }

        return "UNKNOWN ATTRIBUTE";
    }

   /**
    * Compares two STUN Attributes. Attributes are considered equal when their
    * type, length, and all data are the same.
    *
    * @param obj the object to compare this attribute with.
    * @return true if the attributes are equal and false otherwise.
    */
    public boolean equals(Object obj)
    {
        if (! (obj instanceof AddressAttribute)
            || obj == null)
            return false;

        if (obj == this)
            return true;

        AddressAttribute att = (AddressAttribute) obj;
        if (att.getAttributeType() != getAttributeType()
            || att.getDataLength() != getDataLength()
            //compare data
            || att.getFamily()     != getFamily()
            || (att.getAddress()   != null
                && !address.equals(att.getAddress()))
            )
            return false;

        //addresses
        if( att.getAddress() == null && getAddress() == null)
            return true;

        return true;
    }

    /**
     * Returns the length of this attribute's body.
     * @return the length of this attribute's value (8 bytes).
     */
    public char getDataLength()
    {
        if (getFamily() == ADDRESS_FAMILY_IPV6)
            return DATA_LENGTH_FOR_IPV6;
        else
            return DATA_LENGTH_FOR_IPV4;
    }

    /**
     * Returns a binary representation of this attribute.
     * @return a binary representation of this attribute.
     */
    public byte[] encode()
    {
        char type = getAttributeType();
        if (!isTypeValid(type))
            throw new IllegalStateException(((int)type)
                            + "is not a valid address attribute!");
        byte binValue[] = new byte[HEADER_LENGTH + getDataLength()];

        //Type
        binValue[0] = (byte)(type >> 8);
        binValue[1] = (byte)(type & 0x00FF);
        //Length
        binValue[2] = (byte)(getDataLength() >> 8);
        binValue[3] = (byte)(getDataLength() & 0x00FF);
        //Not used
        binValue[4] = 0x00;
        //Family
        binValue[5] = getFamily();
        //port
        binValue[6] = (byte)(getPort() >> 8);
        binValue[7] = (byte)(getPort() & 0x00FF);

        //address
        if(getFamily() == ADDRESS_FAMILY_IPV6){
            System.arraycopy(getAddressBytes(), 0, binValue, 8, 16);
        }
        else{
            System.arraycopy(getAddressBytes(), 0, binValue, 8, 4);
        }

        return binValue;
    }

    /**
     * Sets address to be the address transported by this attribute.
     * @param address that this attribute should encapsulate.
     */
    public void setAddress(TransportAddress address)
    {
        this.address = address;
    }

    /**
     * Returns the address encapsulated by this attribute.
     *
     * @return the address encapsulated by this attribute.
     */
    public TransportAddress getAddress()
    {
        return address;
    }

    /**
     * Returns the bytes of the address.
     *
     * @return the <tt>byte[]</tt> array containing the address.
     */
    public byte[] getAddressBytes()
    {
        return address.getAddressBytes();
    }

    /**
     * Returns the family that the this.address belongs to.
     * @return the family that the this.address belongs to.
     */
    public byte getFamily()
    {
        if ( address.getAddress() instanceof Inet6Address )
            return ADDRESS_FAMILY_IPV6;
        else
            return ADDRESS_FAMILY_IPV4;
    }

    /**
     * Returns the port associated with the address contained by the attribute.
     * @return the port associated with the address contained by the attribute.
     */
    public int getPort()
    {
        return address.getPort();
    }

    /**
      * Sets this attribute's fields according to attributeValue array.
      *
      * @param attributeValue a binary array containing this attribute's field
      *                       values and NOT containing the attribute header.
      * @param offset the position where attribute values begin (most often
      *                  offset is equal to the index of the first byte after
      *                  length)
      * @param length the length of the binary array.
      * @throws StunException if attrubteValue contains invalid data.
      */
    void decodeAttributeBody(byte[] attributeValue, char offset, char length)
        throws StunException
    {
        //skip through padding
        offset ++;

        //get family
        byte family = attributeValue[offset++];

        //port
        char port = ((char)((attributeValue[offset++] << 8 )
                        | (attributeValue[offset++] & 0xFF) ));

        //address
        byte address[] = null;
        if(family == ADDRESS_FAMILY_IPV6)
        {
            address = new byte[16];
        }
        else
        {
            //ipv4
            address = new byte[4];
        }

        System.arraycopy(attributeValue, offset, address, 0, address.length);
        try
        {
            setAddress(new TransportAddress(address, port, Transport.UDP));
        }
        catch (UnknownHostException e)
        {
            throw new StunException(e);
        }
    }
}
