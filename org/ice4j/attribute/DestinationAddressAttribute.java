/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The DESTINATION-ADDRESS is present in Send Requests of old TURN versions.
 * It specifies the address and port where the data is to be sent. It is encoded
 * in the same way as MAPPED-ADDRESS.
 *
 * @author Sebastien Vincent
 */
public class DestinationAddressAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "DESTINATION-ADDRESS";

    /**
     * Constructor.
     */
    DestinationAddressAttribute()
    {
        super(DESTINATION_ADDRESS);
    }
}