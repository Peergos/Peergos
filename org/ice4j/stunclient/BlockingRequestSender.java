/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stunclient;

import java.io.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * A utility used to flatten the multi-thread architecture of the Stack
 * and execute the discovery process in a synchronized manner. Roughly what
 * happens here is:
 * <code>
 * ApplicationThread:
 *     sendMessage()
 *        wait();
 *
 * StackThread:
 *     processMessage/Timeout()
 *     {
 *          saveMessage();
 *          notify();
 *     }
 *</code>
 *
 * @author Emil Ivov
 * @author Lubomir Marinov
 * @author Aakash Garg
 */
public class BlockingRequestSender
    extends AbstractResponseCollector
{
    /**
     * Our class logger
     */
    private static final Logger logger
        = Logger.getLogger(BlockingRequestSender.class.getName());

    /**
     * The stack that we are using to send requests through.
     */
    private final StunStack stunStack;

    /**
     * The transport address that we are bound on.
     */
    private final TransportAddress localAddress;

    /**
     * The <tt>StunMessageEvent</tt> that contains the response matching our
     * request.
     */
    private StunMessageEvent responseEvent = null;

    /**
     * Determines whether this request sender has completed its course.
     */
    private boolean ended = false;

    /**
     * A lock object that we are using to synchronize sending.
     */
    private final Object sendLock = new Object();

    /**
     * Creates a new request sender.
     * @param stunStack the stack that the sender should send requests
     * through.
     * @param localAddress the <tt>TransportAddress</tt> that requests should be
     * leaving from.
     */
    public BlockingRequestSender(StunStack stunStack,
                          TransportAddress localAddress)
    {
        this.stunStack = stunStack;
        this.localAddress = localAddress;
    }

    /**
     * Returns the local Address on which this Blocking Request Sender is bound
     * to.
     *
     * @return the localAddress of this RequestSender.
     */
    public TransportAddress getLocalAddress()
    {
        return localAddress;
    }

    /**
     * Notifies this <tt>ResponseCollector</tt> that a transaction described by
     * the specified <tt>BaseStunMessageEvent</tt> has failed. The possible
     * reasons for the failure include timeouts, unreachable destination, etc.
     * Notifies the discoverer so that it may resume.
     *
     * @param event the <tt>BaseStunMessageEvent</tt> which describes the failed
     * transaction and the runtime type of which specifies the failure reason
     * @see AbstractResponseCollector#processFailure(BaseStunMessageEvent)
     */
    @Override
    protected synchronized void processFailure(BaseStunMessageEvent event)
    {
        synchronized(sendLock)
        {
            ended = true;
            notifyAll();
        }
    }

    /**
     * Saves the message event and notifies the discoverer thread so that
     * it may resume.
     * @param evt the newly arrived message event.
     */
    @Override
    public synchronized void processResponse(StunResponseEvent evt)
    {
        synchronized(sendLock)
        {
            this.responseEvent = evt;
            ended = true;
            notifyAll();
        }
    }

    /**
     * Sends the specified request and blocks until a response has been
     * received or the request transaction has timed out.
     * @param request the request to send
     * @param serverAddress the request destination address
     * @return the event encapsulating the response or null if no response
     * has been received.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    public synchronized StunMessageEvent sendRequestAndWaitForResponse(
                                                Request request,
                                                TransportAddress serverAddress)
            throws StunException,
                   IOException
    {
        synchronized(sendLock)
        {
            stunStack.sendRequest(request, serverAddress, localAddress,
                                     BlockingRequestSender.this);
        }

        ended = false;
        while(!ended)
        {
            try
            {
                wait();
            }
            catch (InterruptedException ex)
            {
                logger.log(Level.WARNING, "Interrupted", ex);
            }
        }
        StunMessageEvent res = responseEvent;
        responseEvent = null; //prepare for next message

        return res;
    }
    

    /**
     * Sends the specified request and blocks until a response has been
     * received or the request transaction has timed out with given 
     * transactionID.
     * @param request the request to send
     * @param serverAddress the request destination address
     * @param tranID the TransactionID to set for this reuest.
     * @return the event encapsulating the response or null if no response
     * has been received.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    public synchronized StunMessageEvent sendRequestAndWaitForResponse(
                                                Request request,
                                                TransportAddress serverAddress,
                                                TransactionID tranID)
            throws StunException,
                   IOException
    {
        synchronized(sendLock)
        {
            stunStack.sendRequest(request, serverAddress, localAddress,
                                     BlockingRequestSender.this,tranID);
        }

        ended = false;
        while(!ended)
        {
            try
            {
                wait();
            }
            catch (InterruptedException ex)
            {
                logger.log(Level.WARNING, "Interrupted", ex);
            }
        }
        StunMessageEvent res = responseEvent;
        responseEvent = null; //prepare for next message

        return res;
    }
}
