/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

/**
 * Send flags used internally
 *
 * @author Pawel Domas
 */
enum SendFlags
{
    sfNone, sfImmediateAck, sfDelayedAck;
}