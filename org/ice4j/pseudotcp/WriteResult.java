/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * The result of write packet operations
 * @author Pawel Domas
 */
public enum WriteResult
{
    /**
     * Packet successfully transmitted
     */
    WR_SUCCESS, 
    /**
     * Packet was too large
     */
    WR_TOO_LARGE, 
    /**
     * Write failed
     */
    WR_FAIL
};
