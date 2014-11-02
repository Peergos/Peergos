package org.ice4j.attribute;

import org.ice4j.*;

/**
 * This class implements the USE-CANDIDATE attribute
 * This attribute is an extension to the original STUN protocol
 * This is used only during an ICE implementation
 *
 * This attribute serves as only a flag, it does not have any data
 * so the data length is zero
 */
public class UseCandidateAttribute
    extends Attribute
{
    /**
     * Data length.
     */
    private static final char DATA_LENGTH_USE_CANDIDATE = 0;

    /**
     * Constructor.
     */
    protected UseCandidateAttribute()
    {
        super(USE_CANDIDATE);
    }

    /**
     * Decodes the USE-CANDIDATE attribute's body, which is empty
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
        // Do nothing, empty attribute body
    }

    /**
     * Returns a binary representation of this attribute.
     *
     * @return a binary representation of this attribute.
     */
    public byte[] encode()
    {
        char type = getAttributeType();
        byte[] binValue = new byte[HEADER_LENGTH + DATA_LENGTH_USE_CANDIDATE];

        // Type
        binValue[0] = (byte)(type >> 8);
        binValue[1] = (byte)(type & 0x00FF);

        // Length
        binValue[2] = (byte)(DATA_LENGTH_USE_CANDIDATE >> 8);
        binValue[3] = (byte)(DATA_LENGTH_USE_CANDIDATE & 0x00FF);

        return binValue;
    }

    /**
     * Compares two STUN Attributes. Two attributes are considered equal when
     * they have the same type, length and value.
     *
     * @param obj the object to compare this attribute with.
     * @return true if the attributes are equal and false otherwise.
     */
    public boolean equals(Object obj)
    {
        if(!(obj instanceof UseCandidateAttribute)
            || obj == null)
            return false;

        if(obj == this)
            return true;

        UseCandidateAttribute useCandidateAtt = (UseCandidateAttribute)obj;
        if(useCandidateAtt.getAttributeType() != getAttributeType()
            || useCandidateAtt.getDataLength() != getDataLength())
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
        return DATA_LENGTH_USE_CANDIDATE;
    }

    /**
     * Returns the human readable name of this attribute.
     *
     * @return this attribute's name.
     */
    public String getName()
    {
        return "USE-CANDIDATE";
    }
}
