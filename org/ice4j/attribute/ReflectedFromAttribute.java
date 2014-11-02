/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * The REFLECTED-FROM attribute is present only in Binding Responses,
 * when the Binding Request contained a RESPONSE-ADDRESS attribute.  The
 * attribute contains the identity (in terms of IP address) of the
 * source where the request came from.  Its purpose is to provide
 * traceability, so that a STUN server cannot be used as a reflector for
 * denial-of-service attacks.
 *
 * Its syntax is identical to the MAPPED-ADDRESS attribute.
 *
 * @author Emil Ivov
 */
public class ReflectedFromAttribute extends AddressAttribute
{
    /**
     * Attribute name.
     */
    public static final String NAME = "REFLECTED-FROM";

    /**
     * Creates a REFLECTED-FROM attribute
     */
    public ReflectedFromAttribute()
    {
        super(REFLECTED_FROM);
    }
}
