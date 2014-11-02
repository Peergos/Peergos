/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * TCP states defined for pseudoTCP
 * @author Pawel Domas
 */
public enum PseudoTcpState
{
    /**
     * Initial state, can accept connection
     */
    TCP_LISTEN,// = 0,
    /**
     * SYN sent to remote peer, wits for SYN
     */
    TCP_SYN_SENT,// = 1,
    /**
     * SYN received from remote peer, sends back SYN
     */
    TCP_SYN_RECEIVED,// = 2;
    /**
     * SYN sent and received - connection established
     */
    TCP_ESTABLISHED,// = 3;
    /**
     * Closed state. In current implementation reached on error
     * or explicite by close method with force option
     * TODO: closing procedure
     */
    TCP_CLOSED// = 4;
};
