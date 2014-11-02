/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * <tt>EnShutdown</tt> enumeration used internally
 *
 * @author Pawel Domas
 */
enum EnShutdown
{
    /**
     * There was no shutdown
     */
    SD_NONE,
    /**
     * There was a graceful shutdown
     */
    SD_GRACEFUL,
    /**
     * There was a forceful shutdown
     */
    SD_FORCEFUL
};