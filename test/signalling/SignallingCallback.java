/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package test.signalling;

/**
 * A simple signalling callback interface where we deliever newly received
 * signalling from the {@link Signalling}
 *
 * @author Emil Ivov
 */
public interface SignallingCallback
{
    /**
     * Processes the specified signalling string
     *
     * @param signalling the signalling string to process
     */
    public void onSignalling(String signalling);
}
