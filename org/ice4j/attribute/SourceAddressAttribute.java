/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The SOURCE-ADDRESS attribute is present in Binding Responses.  It
 * indicates the source IP address and port that the server is sending
 * the response from.  Its syntax is identical to that of MAPPED-ADDRESS.
 *
 * @author Emil Ivov
 */
public class SourceAddressAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "SOURCE-ADDRESS";

    /**
     * Creates a SOURCE-ADDRESS attribute
     */
    SourceAddressAttribute()
    {
        super(SOURCE_ADDRESS);
    }
}
