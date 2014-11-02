/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

/**
 * An {@link IceControlAttribute} implementation representing the
 * ICE-CONTROLLING ICE {@link Attribute}s.
 */
public final class IceControllingAttribute
    extends IceControlAttribute
{
    /**
     * Constructs an ICE-CONTROLLING attribute.
     */
    public IceControllingAttribute()
    {
        super(true);
    }
}
