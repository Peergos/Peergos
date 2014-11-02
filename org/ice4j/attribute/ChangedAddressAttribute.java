/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The CHANGED-ADDRESS attribute indicates the IP address and port where
 * responses would have been sent from if the "change IP" and "change
 * port" flags had been set in the CHANGE-REQUEST attribute of the
 * Binding Request.  The attribute is always present in a Binding
 * Response, independent of the value of the flags.  Its syntax is
 * identical to MAPPED-ADDRESS.
 *
 * @author Emil Ivov
 */

public class ChangedAddressAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "CHANGED-ADDRESS";

    /**
     * Creates a CHANGED_ADDRESS attribute
     */
    public ChangedAddressAttribute()
    {
        super(CHANGED_ADDRESS);
    }
}
