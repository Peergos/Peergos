/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The REMOTE-ADDRESS is present in Data Indication of old TURN versions.
 * It specifies the address and port where the data is sent. It is encoded
 * in the same way as MAPPED-ADDRESS.
 *
 * @author Sebastien Vincent
 */
public class RemoteAddressAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "REMOTE-ADDRESS";

    /**
     * Constructor.
     */
    RemoteAddressAttribute()
    {
        super(REMOTE_ADDRESS);
    }
}