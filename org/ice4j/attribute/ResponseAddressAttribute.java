/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The RESPONSE-ADDRESS attribute indicates where the response to a
 * Binding Request should be sent.  Its syntax is identical to MAPPED-
 * ADDRESS.
 *
 * @author Emil Ivov
 */

public class ResponseAddressAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "RESPONSE-ADDRESS";

    /**
     * Creates a RESPONSE_ADDRESS attribute
     */
    public ResponseAddressAttribute()
    {
        super(RESPONSE_ADDRESS);
    }
}
