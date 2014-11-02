/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The XOR-PEER-ADDRESS attribute is given by a TURN client to
 * indicates the peer destination address of its relayed packet.
 *
 * It has the same format as XOR-MAPPED-ADDRESS.
 *
 * @author Sebastien Vincent
 */
public class XorPeerAddressAttribute extends XorMappedAddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "XOR-PEER-ADDRESS";

    /**
     * Constructor.
     */
    XorPeerAddressAttribute()
    {
        super(XOR_PEER_ADDRESS);
    }
}
