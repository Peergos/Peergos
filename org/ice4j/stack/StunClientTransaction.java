/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * The ClientTransaction class retransmits (what a surprise) requests as
 * specified by rfc 3489.
 *
 * Once formulated and sent, the client sends the Binding Request.  Reliability
 * is accomplished through request retransmissions.  The ClientTransaction
 * retransmits the request starting with an interval of 100ms, doubling
 * every retransmit until the interval reaches 1.6s.  Retransmissions
 * continue with intervals of 1.6s until a response is received, or a
 * total of 9 requests have been sent. If no response is received by 1.6
 * seconds after the last request has been sent, the client SHOULD
 * consider the transaction to have failed. In other words, requests
 * would be sent at times 0ms, 100ms, 300ms, 700ms, 1500ms, 3100ms,
 * 4700ms, 6300ms, and 7900ms. At 9500ms, the client considers the
 * transaction to have failed if no response has been received.
 *
 *
 * @author Emil Ivov.
 * @author Pascal Mogeri (contributed configuration of client transactions).
 * @author Lyubomir Marinov
 */
public class StunClientTransaction
    implements Runnable
{
    /**
     * Our class logger.
     */
    private static final Logger logger
        = Logger.getLogger(StunClientTransaction.class.getName());

    /**
     * The number of times to retransmit a request if no explicit value has been
     * specified by org.ice4j.MAX_RETRANSMISSIONS.
     */
    public static final int DEFAULT_MAX_RETRANSMISSIONS = 6;

    /**
     * The maximum number of milliseconds a client should wait between
     * consecutive retransmissions, after it has sent a request for the first
     * time.
     */
    public static final int DEFAULT_MAX_WAIT_INTERVAL = 1600;

    /**
     * The number of milliseconds a client should wait before retransmitting,
     * after it has sent a request for the first time.
     */
    public static final int DEFAULT_ORIGINAL_WAIT_INTERVAL = 100;

    /**
     * The pool of <tt>Thread</tt>s which retransmit
     * <tt>StunClientTransaction</tt>s.
     */
    private static final ExecutorService retransmissionThreadPool
        = Executors.newCachedThreadPool(
                new ThreadFactory()
                        {
                            /**
                             * The default <tt>ThreadFactory</tt> implementation
                             * which is augmented by this instance to create
                             * daemon <tt>Thread</tt>s.
                             */
                            private final ThreadFactory defaultThreadFactory
                                = Executors.defaultThreadFactory();

                            @Override
                            public Thread newThread(Runnable r)
                            {
                                Thread t = defaultThreadFactory.newThread(r);

                                if (t != null)
                                {
                                    t.setDaemon(true);

                                    /*
                                     * Additionally, make it known through the
                                     * name of the Thread that it is associated
                                     * with the StunClientTransaction class for
                                     * debugging/informational purposes.
                                     */
                                    String name = t.getName();

                                    if (name == null)
                                        name = "";
                                    t.setName("StunClientTransaction-" + name);
                                }
                                return t;
                            }
                        });

    /**
     * Maximum number of retransmissions. Once this number is reached and if no
     * response is received after MAX_WAIT_INTERVAL milliseconds the request is
     * considered unanswered.
     */
    public int maxRetransmissions = DEFAULT_MAX_RETRANSMISSIONS;

    /**
     * The number of milliseconds to wait before the first retransmission of the
     * request.
     */
    public int originalWaitInterval = DEFAULT_ORIGINAL_WAIT_INTERVAL;

    /**
     * The maximum wait interval. Once this interval is reached we should stop
     * doubling its value.
     */
    public int maxWaitInterval = DEFAULT_MAX_WAIT_INTERVAL;

    /**
     * The <tt>StunStack</tt> that created us.
     */
    private final StunStack stackCallback;

    /**
     * The request that we are retransmitting.
     */
    private final Request request;

    /**
     * The destination of the request.
     */
    private final TransportAddress requestDestination;

    /**
     * The id of the transaction.
     */
    private final TransactionID transactionID;

    /**
     * The <tt>TransportAddress</tt> through which the original request was sent
     * and that we are supposed to be retransmitting through.
     */
    private final TransportAddress localAddress;

    /**
     * The instance to notify when a response has been received in the current
     * transaction or when it has timed out.
     */
    private final ResponseCollector responseCollector;

    /**
     * Determines whether the transaction is active or not.
     */
    private boolean cancelled = false;

    /**
     * The <tt>Lock</tt> which synchronizes the access to the state of this
     * instance. Introduced along with {@link #lockCondition} in order to allow
     * the invocation of {@link #cancel(boolean)} without a requirement to
     * acquire the synchronization root. Otherwise, callers of
     * <tt>cancel(boolean)</tt> may (and have be reported multiple times to)
     * fall into a deadlock merely because they want to cancel this
     * <tt>StunClientTransaction</tt>.
     */
    private final Lock lock = new ReentrantLock();

    /**
     * The <tt>Condition</tt> of {@link #lock} which this instance uses to wait
     * for either the next retransmission interval or the cancellation of this
     * <tt>StunClientTransaction</tt>.
     */
    private final Condition lockCondition = lock.newCondition();

    /**
     * Creates a client transaction.
     *
     * @param stackCallback the stack that created us.
     * @param request the request that we are living for.
     * @param requestDestination the destination of the request.
     * @param localAddress the local <tt>TransportAddress</tt> this transaction
     * will be communication through.
     * @param responseCollector the instance that should receive this request's
     * response retransmit.
     */
    public StunClientTransaction(StunStack         stackCallback,
                                 Request           request,
                                 TransportAddress  requestDestination,
                                 TransportAddress  localAddress,
                                 ResponseCollector responseCollector)
    {
        this(stackCallback,
             request,
             requestDestination,
             localAddress,
             responseCollector,
             TransactionID.createNewTransactionID());
    }

    /**
     * Creates a client transaction.
     *
     * @param stackCallback the stack that created us.
     * @param request the request that we are living for.
     * @param requestDestination the destination of the request.
     * @param localAddress the local <tt>TransportAddress</tt> this transaction
     * will be communication through.
     * @param responseCollector the instance that should receive this request's
     * response retransmit.
     * @param transactionID the ID that we'd like the new transaction to have
     * in case the application created it in order to use it for application
     * data correlation.
     */
    public StunClientTransaction(StunStack         stackCallback,
                                 Request           request,
                                 TransportAddress  requestDestination,
                                 TransportAddress  localAddress,
                                 ResponseCollector responseCollector,
                                 TransactionID     transactionID)
    {
        this.stackCallback      = stackCallback;
        this.request            = request;
        this.localAddress       = localAddress;
        this.responseCollector  = responseCollector;
        this.requestDestination = requestDestination;

        initTransactionConfiguration();

        this.transactionID = transactionID;

        try
        {
            request.setTransactionID(transactionID.getBytes());
        }
        catch (StunException ex)
        {
            //Shouldn't happen so lets just through a runtime exception in case
            //anything is real messed up
            throw new IllegalArgumentException(
                    "The TransactionID class generated an invalid transaction"
                        + " ID");
        }
    }

    /**
     * Implements the retransmissions algorithm. Retransmits the request
     * starting with an interval of 100ms, doubling every retransmit until the
     * interval reaches 1.6s.  Retransmissions continue with intervals of 1.6s
     * until a response is received, or a total of 7 requests have been sent.
     * If no response is received by 1.6 seconds after the last request has been
     * sent, we consider the transaction to have failed.
     * <p>
     * The method acquires {@link #lock} and invokes {@link #runLocked()}.
     * </p>
     */
    @Override
    public void run()
    {
        lock.lock();
        try
        {
            runLocked();
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Implements the retransmissions algorithm. Retransmits the request
     * starting with an interval of 100ms, doubling every retransmit until the
     * interval reaches 1.6s.  Retransmissions continue with intervals of 1.6s
     * until a response is received, or a total of 7 requests have been sent.
     * If no response is received by 1.6 seconds after the last request has been
     * sent, we consider the transaction to have failed.
     * <p>
     * The method assumes that the current thread has already acquired
     * {@link #lock}.
     * </p>
     */
    private void runLocked()
    {
        // Indicates how many times we have retransmitted so far.
        int retransmissionCounter = 0;
        // How much did we wait after our last retransmission?
        int nextWaitInterval = originalWaitInterval;

        for (retransmissionCounter = 0;
             retransmissionCounter < maxRetransmissions;
             retransmissionCounter ++)
        {
            waitFor(nextWaitInterval);

            //did someone tell us to get lost?
            if(cancelled)
                return;

            int curWaitInterval = nextWaitInterval;
            if(nextWaitInterval < maxWaitInterval)
                nextWaitInterval *= 2;

            try
            {
                logger.fine(
                        "retrying STUN tid " + transactionID + " from "
                            + localAddress + " to " + requestDestination
                            + " waited " + curWaitInterval + " ms retrans "
                            + (retransmissionCounter + 1) + " of "
                            + maxRetransmissions);
                sendRequest0();
            }
            catch (Exception ex)
            {
                //I wonder whether we should notify anyone that a retransmission
                // has failed
                logger.log(
                        Level.INFO,
                        "A client tran retransmission failed",
                        ex);
            }
        }

        //before stating that a transaction has timeout-ed we should first wait
        //for a reception of the response
        if(nextWaitInterval < maxWaitInterval)
            nextWaitInterval *= 2;

        waitFor(nextWaitInterval);

        if(cancelled)
            return;

        stackCallback.removeClientTransaction(this);
        responseCollector.processTimeout(
                new StunTimeoutEvent(
                        stackCallback,
                        this.request, getLocalAddress(), transactionID));
    }

    /**
     * Sends the request and schedules the first retransmission for after
     * ORIGINAL_WAIT_INTERVAL and thus starts the retransmission algorithm.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed
     *
     */
    void sendRequest()
        throws IllegalArgumentException, IOException
    {
        logger.fine(
                "sending STUN " + " tid " + transactionID + " from "
                    + localAddress + " to " + requestDestination);
        sendRequest0();

        retransmissionThreadPool.execute(this);
    }

    /**
     * Simply calls the sendMessage method of the accessmanager.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     */
    private void sendRequest0()
        throws IllegalArgumentException, IOException
    {
        if(cancelled)
        {
            logger.finer("Trying to resend a cancelled transaction.");
        }
        else
        {
            stackCallback.getNetAccessManager().sendMessage(
                    this.request,
                    localAddress,
                    requestDestination);
        }
    }

    /**
     * Returns the request that was the reason for creating this transaction.
     * @return the request that was the reason for creating this transaction.
     */
    Request getRequest()
    {
        return this.request;
    }

    /**
     * Waits until next retransmission is due or until the transaction is
     * cancelled (whichever comes first).
     *
     * @param millis the number of milliseconds to wait for.
     */
    void waitFor(long millis)
    {
        lock.lock();
        try
        {
            lockCondition.await(millis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            throw new RuntimeException(ex);
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Cancels the transaction. Once this method is called the transaction is
     * considered terminated and will stop retransmissions.
     *
     * @param waitForResponse indicates whether we should wait for the current
     * RTO to expire before ending the transaction or immediately terminate.
     */
    void cancel(boolean waitForResponse)
    {
        /*
         * XXX The cancelled field is initialized to false and then the one and
         * only write access to it is here to set it to true. The rest of the
         * code just checks whether it has become true. Consequently, there
         * shouldn't be a problem if the set is outside a synchronized block.
         * However, it being outside a synchronized block will decrease the risk
         * of deadlocks.
         */
        cancelled = true;

        if(!waitForResponse)
        {
            /*
             * Try to interrupt #waitFor(long) if possible. But don't risk a
             * deadlock. It is not a problem if it is not possible to interrupt
             * #waitFor(long) here because it will complete in finite time and
             * this StunClientTransaction will eventually notice that it has
             * been cancelled.    
             */
            if (lock.tryLock())
            {
                try
                {
                    lockCondition.signal();
                }
                finally
                {
                    lock.unlock();
                }
            }
        }
    }

    /**
     * Cancels the transaction. Once this method is called the transaction is
     * considered terminated and will stop retransmissions.
     */
    void cancel()
    {
        cancel(false);
    }

    /**
     * Dispatches the response then cancels itself and notifies the StunStack
     * for its termination.
     *
     * @param evt the event that contains the newly received message
     */
    public void handleResponse(StunMessageEvent evt)
    {
        lock.lock();
        try
        {
            TransactionID transactionID = getTransactionID();

            logger.log(Level.FINE, "handleResponse tid " + transactionID);
            if(!Boolean.getBoolean(StackProperties.KEEP_CRANS_AFTER_A_RESPONSE))
                cancel();

            responseCollector.processResponse(
                    new StunResponseEvent(
                            stackCallback,
                            evt.getRawMessage(),
                            (Response) evt.getMessage(),
                            request,
                            transactionID));
        }
        finally
        {
            lock.unlock();
        }
    }

    /**
     * Returns the ID of the current transaction.
     *
     * @return the ID of the transaction.
     */
    TransactionID getTransactionID()
    {
        return this.transactionID;
    }

    /**
     * Init transaction duration/retransmission parameters. (Mostly contributed
     * by Pascal Maugeri.)
     */
    private void initTransactionConfiguration()
    {
        //Max Retransmissions
        String maxRetransmissionsStr
            = System.getProperty(StackProperties.MAX_CTRAN_RETRANSMISSIONS);

        if(maxRetransmissionsStr != null
                && maxRetransmissionsStr.trim().length() > 0)
        {
            try
            {
                maxRetransmissions = Integer.parseInt(maxRetransmissionsStr);
            }
            catch (NumberFormatException e)
            {
                logger.log(Level.FINE,
                           "Failed to parse MAX_RETRANSMISSIONS",
                           e);
                maxRetransmissions = DEFAULT_MAX_RETRANSMISSIONS;
            }
        }

        //Original Wait Interval
        String originalWaitIntervalStr
            = System.getProperty(StackProperties.FIRST_CTRAN_RETRANS_AFTER);

        if(originalWaitIntervalStr != null
                && originalWaitIntervalStr.trim().length() > 0)
        {
            try
            {
                originalWaitInterval
                    = Integer.parseInt(originalWaitIntervalStr);
            }
            catch (NumberFormatException e)
            {
                logger.log(Level.FINE,
                           "Failed to parse ORIGINAL_WAIT_INTERVAL",
                           e);
                originalWaitInterval = DEFAULT_ORIGINAL_WAIT_INTERVAL;
            }
        }

        //Max Wait Interval
        String maxWaitIntervalStr
                = System.getProperty(StackProperties.MAX_CTRAN_RETRANS_TIMER);

        if(maxWaitIntervalStr != null
                && maxWaitIntervalStr.trim().length() > 0)
        {
            try
            {
                maxWaitInterval = Integer.parseInt(maxWaitIntervalStr);
            }
            catch (NumberFormatException e)
            {
                logger.log(Level.FINE, "Failed to parse MAX_WAIT_INTERVAL", e);
                maxWaitInterval = DEFAULT_MAX_WAIT_INTERVAL;
            }
        }
    }

    /**
     * Returns the local <tt>TransportAddress</tt> that this transaction is
     * sending requests from.
     *
     * @return  the local <tt>TransportAddress</tt> that this transaction is
     * sending requests from.
     */
    public TransportAddress getLocalAddress()
    {
        return localAddress;
    }

    /**
     * Returns the remote <tt>TransportAddress</tt> that this transaction is
     * sending requests to.
     *
     * @return the remote <tt>TransportAddress</tt> that this transaction is
     * sending requests to.
     */
    public TransportAddress getRemoteAddress()
    {
        return requestDestination;
    }
}
