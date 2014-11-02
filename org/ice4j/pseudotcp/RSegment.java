/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;


/**
 * Class used internally as a structure for receive segments
 *
 * @author Pawel
 */
class RSegment
{
    public long seq, len;

    public RSegment(long seq, long len)
    {
        this.seq = seq;
        this.len = len;
    }
}