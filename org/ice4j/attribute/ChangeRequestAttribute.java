/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.*;

/**
 * This class represents the STUN CHANGE-REQUEST attribute. The CHANGE-REQUEST
 * attribute is used by the client to request that the server use a different
 * address and/or port when sending the response.  The attribute is 32 bits
 * long, although only two bits (A and B) are used:
 *
 * 0                   1                   2                   3           <br/>
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1         <br/>
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+       <br/>
 * |0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 A B 0|       <br/>
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+       <br/>
 *
 * The meaning of the flags is:
 *
 * A: This is the "change IP" flag.  If true, it requests the server
 *    to send the Binding Response with a different IP address than the
 *    one the Binding Request was received on.
 *
 * B: This is the "change port" flag.  If true, it requests the
 *    server to send the Binding Response with a different port than the
 *    one the Binding Request was received on.
 *
 * @author Emil Ivov
 */

public class ChangeRequestAttribute
    extends Attribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "CHANGE-REQUEST";

    /**
     * This is the "change IP" flag.  If true, it requests the server
     * to send the Binding Response with a different IP address than the
     * one the Binding Request was received on.
     */
    private boolean changeIpFlag   = false;

    /**
     * This is the "change port" flag.  If true, it requests the
     * server to send the Binding Response with a different port than the
     * one the Binding Request was received on.
     */
    private boolean changePortFlag = false;

    /**
     * The length of the data contained by this attribute.
     */
    public static final char DATA_LENGTH = 4;


    /**
     * Creates an empty ChangeRequestAttribute.
     */
    ChangeRequestAttribute()
    {
        super(CHANGE_REQUEST);
    }

    /**
     * Returns the human readable name of this attribute. Attribute names do
     * not really matter from the protocol point of view. They are only used
     * for debugging and readability.
     * @return this attribute's name.
     */
    public String getName()
    {
        return NAME;
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
         if (! (obj instanceof ChangeRequestAttribute)
             || obj == null)
             return false;

         if (obj == this)
             return true;

         ChangeRequestAttribute att = (ChangeRequestAttribute) obj;
         if (att.getAttributeType()   != getAttributeType()
             || att.getDataLength()   != getDataLength()
             //compare data
             || att.getChangeIpFlag() != getChangeIpFlag()
             || att.getChangePortFlag()       != getChangePortFlag()
             )
             return false;

         return true;
    }

    /**
     * Returns the length of this attribute's body.
     * @return the length of this attribute's value (8 bytes).
     */
    public char getDataLength()
    {
        return DATA_LENGTH;
    }

    /**
     * Returns a binary representation of this attribute.
     * @return a binary representation of this attribute.
     */
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
        binValue[4] = 0x00;
        binValue[5] = 0x00;
        binValue[6] = 0x00;
        binValue[7] = (byte)((getChangeIpFlag() ? 4 : 0) +
                (getChangePortFlag() ? 2 : 0));

        return binValue;
    }

    //========================= set/get methods
    /**
     * Sets the value of the changeIpFlag. The "change IP" flag,  if true,
     * requests the server to send the Binding Response with a different IP
     * address than the one the Binding Request was received on.
     *
     * @param changeIP the new value of the changeIpFlag.
     */
    public void setChangeIpFlag(boolean changeIP)
    {
        this.changeIpFlag = changeIP;
    }

    /**
     * Returns the value of the changeIpFlag. The "change IP" flag,  if true,
     * requests the server to send the Binding Response with a different IP
     * address than the one the Binding Request was received on.
     *
     * @return the value of the changeIpFlag.
     */
    public boolean getChangeIpFlag()
    {
        return changeIpFlag;
    }

    /**
     * Sets the value of the changePortFlag. The "change port" flag.  If true,
     * requests the server to send the Binding Response with a different port
     * than the one the Binding Request was received on.
     *
     * @param changePort the new value of the changePort flag.
     */
    public void setChangePortFlag(boolean changePort)
    {
        this.changePortFlag = changePort;
    }

    /**
     * Returns the value of the changePortFlag. The "change port" flag. If true,
     * requests the server to send the Binding Response with a different port
     * than the one the Binding Request was received on.
     *
     * @return the value of the changePort flag.
     */
    public boolean getChangePortFlag()
    {
        return changePortFlag;
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
         offset += 3; // first three bytes of change req att are not used
         setChangeIpFlag((attributeValue[offset] & 4) > 0);
         setChangePortFlag((attributeValue[offset] & 0x2) > 0);
     }
}
