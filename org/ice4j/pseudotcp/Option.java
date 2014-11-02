/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * Options used in PseudoTCP
 *
 * @author Pawel Domas
 */
public enum Option
{
    /**
     * Whether to enable Nagle's algorithm (0 == off)
     */
    OPT_NODELAY,
    /**
     * The Delayed ACK timeout (0 == off).
     */
    OPT_ACKDELAY,
    /**
     * Set the receive buffer size, in bytes.
     */
    OPT_RCVBUF,
    /**
     * Set the send buffer size, in bytes.
     */
    OPT_SNDBUF,
    /**
     * Timeout in ms for read operations(0 - no timeout)
     */
    OPT_READ_TIMEOUT,
    /**
     * Timeout in ms for write operations(0 - no timeout)
     */
    OPT_WRITE_TIMEOUT
};