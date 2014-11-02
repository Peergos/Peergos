/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * A STUN client retransmits requests as specified by the protocol.
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
 * A server transaction is therefore responsible for retransmitting the same
 * response that was saved for the original request, and not let any
 * retransmissions go through to the user application.
 *
 * @author Emil Ivov
 */
public class StunServerTransaction
{
    /**
     * The time that we keep server transactions active.
     */
    static final long LIFETIME = 16000;

    /**
     * The <tt>StunStack</tt> that created us.
     */
    private final StunStack stackCallback;

    /**
     * The address that we are sending responses to.
     */
    private TransportAddress responseDestination = null;

    /**
     * The address that we are receiving requests from.
     */
    private final TransportAddress requestSource;

    /**
     * The response sent in response to the request.
     */
    private Response response = null;

    /**
     * The <tt>TransportAddress</tt> that we received our request on.
     */
    private final TransportAddress localListeningAddress;

    /**
     * The <tt>TransportAddress</tt> we use when sending responses
     */
    private TransportAddress localSendingAddress = null;

    /**
     * The id of the transaction.
     */
    private final TransactionID transactionID;

    /**
     * The time in milliseconds when the next retransmission should follow.
     */
    private long expirationTime = -1;

    /**
     * Determines whether or not the transaction has expired.
     */
    private boolean expired = true;

    /**
     * Determines whether or not the transaction is in a retransmitting state.
     * In other words whether a response has already been sent once to the
     * transaction request.
     */
    private boolean isRetransmitting = false;

    /**
     * Creates a server transaction
     * @param stackCallback the stack that created us.
     * @param tranID the transaction id contained by the request that was the
     * cause for this transaction.
     * @param localListeningAddress the <tt>TransportAddress</tt> that this
     * transaction is receiving requests on.
     * @param requestSource the <tt>TransportAddress</tt> that this
     * transaction is receiving requests from.
     */
    public StunServerTransaction(StunStack        stackCallback,
                                 TransactionID    tranID,
                                 TransportAddress localListeningAddress,
                                 TransportAddress requestSource)
    {
        this.stackCallback  = stackCallback;
        this.transactionID  = tranID;
        this.localListeningAddress = localListeningAddress;
        this.requestSource = requestSource;
    }

    /**
     * Start the transaction. This launches the countdown to the moment the
     * transaction would expire.
     */
    public synchronized void start()
    {
        if (expirationTime == -1)
        {
            expired = false;
            expirationTime = LIFETIME + System.currentTimeMillis();
        }
        else
        {
            throw new IllegalStateException(
                    "StunServerTransaction " + getTransactionID()
                        + " has already been started!");
        }
    }

    /**
     * Sends the specified response through the <code>sendThrough</code>
     * NetAccessPoint descriptor to the specified destination and changes
     * the transaction's state to retransmitting.
     *
     * @param response the response to send the transaction to.
     * @param sendThrough the local address through which responses are to
     * be sent
     * @param sendTo the destination for responses of this transaction.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    public void sendResponse(Response         response,
                             TransportAddress sendThrough,
                             TransportAddress sendTo)
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        if(!isRetransmitting){
            this.response = response;
            //the transaction id might already have been set, but its our job
            //to make sure of that
            response.setTransactionID(this.transactionID.getBytes());
            this.localSendingAddress   = sendThrough;
            this.responseDestination   = sendTo;
        }

        isRetransmitting = true;
        retransmitResponse();
    }

    /**
     * Retransmits the response that was originally sent to the request that
     * caused this transaction.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails,
     */
    protected void retransmitResponse()
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        //don't retransmit if we are expired or if the user application
        //hasn't yet transmitted a first response
        if(isExpired() || !isRetransmitting)
            return;

        stackCallback.getNetAccessManager().sendMessage(
                response,
                localSendingAddress,
                responseDestination);
    }

    /**
     * Cancels the transaction. Once this method is called the transaction is
     * considered terminated and will stop retransmissions.
     */
    public synchronized void expire()
    {
        expired = true;
        /*
         * StunStack has a background Thread running with the purpose of
         * removing expired StunServerTransactions.
         */
    }

    /**
     * Determines whether this <tt>StunServerTransaction</tt> is expired now.
     *
     * @return <tt>true</tt> if this <tt>StunServerTransaction</tT> is expired
     * now; otherwise, <tt>false</tt>
     */
    public boolean isExpired()
    {
        return isExpired(System.currentTimeMillis());
    }

    /**
     * Determines whether this <tt>StunServerTransaction</tt> will be expired at
     * a specific point in time.
     *
     * @param now the time in milliseconds at which the <tt>expired</tt> state
     * of this <tt>StunServerTransaction</tt> is to be returned
     * @return <tt>true</tt> if this <tt>StunServerTransaction</tt> will be
     * expired at the specified point in time; otherwise, <tt>false</tt>
     */
    public synchronized boolean isExpired(long now)
    {
        if (expirationTime == -1)
            return false;
        else if (expirationTime < now)
            return true;
        else
            return expired;
    }

    /**
     * Returns the ID of the current transaction.
     *
     * @return the ID of the transaction.
     */
    public TransactionID getTransactionID()
    {
        return transactionID;
    }

    /**
     * Specifies whether this server transaction is in the retransmitting state.
     * Or in other words - has it already sent a first response or not?
     *
     * @return <tt>true</tt> if this transaction is still retransmitting and
     * false <tt>otherwise</tt>
     */
    public boolean isRetransmitting()
    {
        return isRetransmitting;
    }

    /**
     * Returns the local <tt>TransportAddress</tt> that this transaction is
     * sending responses from.
     *
     * @return the local <tt>TransportAddress</tt> that this transaction is
     * sending responses from.
     */
    public TransportAddress getSendingAddress()
    {
        return localSendingAddress;
    }

    /**
     * Returns the remote <tt>TransportAddress</tt> that this transaction is
     * receiving requests from.
     *
     * @return the remote <tt>TransportAddress</tt> that this transaction is
     * receiving requests from.
     */
    public TransportAddress getResponseDestinationAddress()
    {
        return responseDestination;
    }

    /**
     * Returns the local <tt>TransportAddress</tt> that this transaction is
     * receiving requests on.
     *
     * @return the local <tt>TransportAddress</tt> that this transaction is
     * receiving requests on.
     */
    public TransportAddress getLocalListeningAddress()
    {
        return localListeningAddress;
    }

    /**
     * Returns the remote <tt>TransportAddress</tt> that this transaction is
     * receiving requests from.
     *
     * @return the remote <tt>TransportAddress</tt> that this transaction is
     * receiving requests from.
     */
    public TransportAddress getRequestSourceAddress()
    {
        return requestSource;
    }

    /**
     * Returns the <tt>Response</tt> that the <tt>StunStack</tt> has sent
     * through this transaction or <tt>null</tt> if no <tt>Response</tt> has
     * been sent yet.
     *
     * @return the <tt>Response</tt> that the <tt>StunStack</tt> has sent
     * through this transaction or <tt>null</tt> if no <tt>Response</tt> has
     * been sent yet.
     */
    protected Response getResponse()
    {
        return response;
    }
}
