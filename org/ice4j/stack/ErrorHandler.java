/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

/**
 * Generic Error Handler.
 *
 * @author Emil Ivov
 */
interface ErrorHandler
{
    /**
     * Called when an error has occurred which may have caused data loss but the
     * calling thread is still running.
     *
     * @param message A message describing the error
     * @param error   The error itself.
     */
    public void handleError(String message, Throwable error);

    /**
     * Called when a fatal error has occurred and the calling thread will exit.
     *
     * @param callingThread the thread where the error has occurred
     * @param message       a message describing the error.
     * @param error         the error itself.
     */
    public void handleFatalError(Runnable callingThread,
                                 String message,
                                 Throwable error);
}
