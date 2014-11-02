/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * Class used internally as a structure for send segments
 *
 * @author Pawel Domas
 */
class SSegment
{
    long seq, len;
    //uint32 tstamp;
    short xmit;
    boolean bCtrl;

    SSegment(long s, long l, boolean c)
    {
        seq = s;
        len = l;
        xmit = 0;
        bCtrl = c;
    }
}