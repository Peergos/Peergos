/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import java.net.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * The XOR-MAPPED-ADDRESS attribute is only present in Binding
 * Responses.  It provides the same information that is present in the
 * MAPPED-ADDRESS attribute.  However, the information is encoded by
 * performing an exclusive or (XOR) operation between the mapped address
 * and the transaction ID.  Unfortunately, some NAT devices have been
 * found to rewrite binary encoded IP addresses and ports that are
 * present in protocol payloads.  This behavior interferes with the
 * operation of STUN.  By providing the mapped address in an obfuscated
 * form, STUN can continue to operate through these devices.
 *
 * The format of the XOR-MAPPED-ADDRESS is:
 *
 *
 * 0                   1                   2                   3
 * 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |x x x x x x x x|    Family     |         X-Port                |
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                X-Address (Variable)
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * The Family represents the IP address family, and is encoded
 * identically to the Family in MAPPED-ADDRESS.
 *
 * X-Port is equal to the port in MAPPED-ADDRESS, exclusive or'ed with
 * most significant 16 bits of the transaction ID.  If the IP address
 * family is IPv4, X-Address is equal to the IP address in MAPPED-
 * ADDRESS, exclusive or'ed with the most significant 32 bits of the
 * transaction ID.  If the IP address family is IPv6, the X-Address is
 * equal to the IP address in MAPPED-ADDRESS, exclusive or'ed with the
 * entire 128 bit transaction ID.
 *
 * @author Emil Ivov
 */
public class XorMappedAddressAttribute
    extends AddressAttribute
{
    /**
     * The name of this attribute
     */
    public static final String NAME = "XOR-MAPPED-ADDRESS";

    /**
     * Creates an instance of this attribute
     */
    XorMappedAddressAttribute()
    {
        super(XOR_MAPPED_ADDRESS);
    }

    /**
     * Constructor.
     * @param type other type than XOR-MAPPED-ADDRESS
     */
    XorMappedAddressAttribute(char type)
    {
      super(type);
    }

    /**
     * Returns the result of applying XOR on the specified attribute's address.
     * The method may be used for both encoding and decoding XorMappedAddresses.
     *
     * @param address the address on which XOR should be applied
     * @param transactionID the transaction id to use for the XOR
     *
     * @return the XOR-ed address.
     */
    public static TransportAddress applyXor(TransportAddress address,
                                       byte[] transactionID)
    {
        byte[] addressBytes = address.getAddressBytes();
        char port = (char)address.getPort();

        char portModifier = (char)( (transactionID[0] << 8 & 0x0000FF00)
                                  | (transactionID[1] & 0x000000FF));

        port ^= portModifier;

        for(int i = 0; i < addressBytes.length; i++)
            addressBytes[i] ^= transactionID[i];

        TransportAddress xoredAdd;
        try
        {
            xoredAdd = new TransportAddress(addressBytes, port, Transport.UDP);
        }
        catch (UnknownHostException e)
        {
            //shouldn't happen so just throw an illegal arg
            throw new IllegalArgumentException(e);
        }

        return xoredAdd;
    }

    /**
     * Returns the result of applying XOR on this attribute's address, using the
     * specified transaction ID when converting IPv6 addresses.
     *
     * @param transactionID the transaction ID to use in case this attribute is
     * encapsulating an IPv6 address.
     *
     * @return the XOR-ed address.
     */
    public TransportAddress getAddress(byte[] transactionID)
    {
        byte[] xorMask = new byte[16];

        System.arraycopy(Message.MAGIC_COOKIE, 0, xorMask, 0, 4);
        System.arraycopy(transactionID, 0, xorMask, 4, 12);

        return applyXor(xorMask);
    }

    /**
     * Returns the result of applying XOR on this attribute's address, using the
     * specified XOR mask. The method may be used for both encoding and
     * decoding <tt>XorMappedAddresses</tt>.
     *
     * @param xorMask the XOR mask to use when obtaining the original address.
     *
     * @return the XOR-ed address.
     */
    public TransportAddress applyXor(byte[] xorMask)
    {
        return applyXor(getAddress(), xorMask);
    }

    /**
     * Applies a XOR mask to the specified address and then sets it as the value
     * transported by this attribute.
     *
     * @param address the address that we should xor and then record in this
     * attribute.
     * @param transactionID the transaction identifier that we should use
     * when creating the XOR mask.
     */
    public void setAddress(TransportAddress address, byte[] transactionID)
    {
        byte[] xorMask = new byte[16];

        System.arraycopy(Message.MAGIC_COOKIE, 0, xorMask, 0, 4);
        System.arraycopy(transactionID, 0, xorMask, 4, 12);

        TransportAddress xorAddress = applyXor(address, xorMask);

        super.setAddress(xorAddress);
    }
}
