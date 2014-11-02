/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;


/**
 * Class used as a segment structure
 *
 * @author Pawel Domas
 */
public class Segment
{
    long conv;
    long seq;
    long ack;
    byte flags;
    int wnd;
    long tsval;
    long tsecr;
    byte[] data;
    int len;
}