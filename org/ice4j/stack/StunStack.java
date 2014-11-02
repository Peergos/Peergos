/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.logging.*;

import javax.crypto.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.message.*;
import org.ice4j.security.*;
import org.ice4j.socket.*;

/**
 * The entry point to the Stun4J stack. The class is used to start, stop and
 * configure the stack.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 * @author Aakash Garg.
 */
public class StunStack
    implements MessageEventHandler
{

    /**
     * The number of threads to split our flow in.
     */
    public static final int DEFAULT_THREAD_POOL_SIZE = 3;

    /**
     * The <tt>Logger</tt> used by the <tt>StunStack</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(StunStack.class.getName());

    /**
     * The indicator which determines whether
     * <code>Mac.getInstance(MessageIntegrityAttribute.HMAC_SHA1_ALGORITHM)</code>
     * has been called.
     *
     * @see #StunStack()
     */
    private static Mac mac;

    /**
     * Our network gateway.
     */
    private final NetAccessManager netAccessManager;

    /**
     * The {@link CredentialsManager} that we are using for retrieving
     * passwords.
     */
    private final CredentialsManager credentialsManager
        = new CredentialsManager();

    /**
     * Stores active client transactions mapped against TransactionID-s.
     */
    private final Hashtable<TransactionID, StunClientTransaction>
        clientTransactions
            = new Hashtable<TransactionID, StunClientTransaction>();

    /**
     * The <tt>Thread</tt> which expires the <tt>StunServerTransaction</tt>s of
     * this <tt>StunStack</tt> and removes them from
     * {@link #serverTransactions}.
     */
    private Thread serverTransactionExpireThread;

    /**
     * Currently open server transactions. The vector contains transaction ids
     * for transactions corresponding to all non-answered received requests.
     */
    private final Hashtable<TransactionID, StunServerTransaction>
        serverTransactions
            = new Hashtable<TransactionID, StunServerTransaction>();

    /**
     * A dispatcher for incoming requests event;
     */
    private final EventDispatcher eventDispatcher = new EventDispatcher();

    /**
     * The packet logger instance.
     */
    private static PacketLogger packetLogger;

    /**
     * Sets the number of Message processors running in the same time.
     *
     * @param threadPoolSize the number of message process threads to run.
     * @throws IllegalArgumentException if threadPoolSize is not a valid size.
     */
    public void setThreadPoolSize(int threadPoolSize)
        throws IllegalArgumentException
    {
        netAccessManager.setThreadPoolSize(threadPoolSize);
    }

    /**
     * Creates and starts the specified Network Access Point based on the
     * specified socket and returns a relevant descriptor.
     *
     * @param sock The socket that the new access point should represent.
     */
    public void addSocket(IceSocketWrapper sock)
    {
        netAccessManager.addSocket(sock);
    }

    /**
     * Stops and deletes the connector listening on the specified local address.
     * Note this removes connectors with UDP sockets only, use
     * {@link #removeSocket(org.ice4j.TransportAddress, org.ice4j.TransportAddress)}
     * with the appropriate remote address for TCP.
     *
     * @param localAddr the local address of the socket to remove.
     */
    public void removeSocket(TransportAddress localAddr)
    {
        removeSocket(localAddr, null);
    }

    /**
     * Stops and deletes the connector listening on the specified local address
     * and remote address.
     *
     * @param localAddr the local address of the socket to remove.
     * @param remoteAddr the remote address of the socket to remove. Use
     * <tt>null</tt> for UDP.
     */
    public void removeSocket(TransportAddress localAddr,
                             TransportAddress remoteAddr)
    {
        //first cancel all transactions using this address.
        cancelTransactionsForAddress(localAddr, remoteAddr);

        netAccessManager.removeSocket(localAddr, remoteAddr);
    }

    /**
     * Returns the transaction with the specified <tt>transactionID</tt> or
     * <tt>null</tt> if no such transaction exists.
     *
     * @param transactionID the ID of the transaction we are looking for.
     *
     * @return the {@link StunClientTransaction} we are looking for.
     */
    protected StunClientTransaction getClientTransaction(byte[] transactionID)
    {
        synchronized (clientTransactions)
        {
            Collection<StunClientTransaction> cTrans
                = clientTransactions.values();

            for (StunClientTransaction tran : cTrans)
            {
                if (tran.getTransactionID().equals(transactionID))
                    return tran;
            }
        }
        return null;
    }

    /**
     * Returns the transaction with the specified <tt>transactionID</tt> or
     * <tt>null</tt> if no such transaction exists.
     *
     * @param transactionID the ID of the transaction we are looking for.
     *
     * @return the {@link StunClientTransaction} we are looking for.
     */
    protected StunServerTransaction getServerTransaction(byte[] transactionID)
    {
        synchronized (serverTransactions)
        {
            long now = System.currentTimeMillis();

            for (Iterator<StunServerTransaction> i
                        = serverTransactions.values().iterator();
                    i.hasNext();)
            {
                StunServerTransaction serverTransaction = i.next();

                if (serverTransaction.isExpired(now))
                    i.remove();
                else if (serverTransaction.getTransactionID().equals(
                        transactionID))
                    return serverTransaction;
            }
        }
        return null;
    }

    /**
     * Returns the transaction with the specified <tt>transactionID</tt> or
     * <tt>null</tt> if no such transaction exists.
     *
     * @param transactionID the ID of the transaction we are looking for.
     *
     * @return the {@link StunClientTransaction} we are looking for.
     */
    protected StunServerTransaction getServerTransaction(
            TransactionID transactionID)
    {
        StunServerTransaction serverTransaction;

        synchronized (serverTransactions)
        {
            serverTransaction = serverTransactions.get(transactionID);
        }
        /*
         * If a StunServerTransaction is expired, do not return it. It will be
         * removed from serverTransactions soon.
         */
        if ((serverTransaction != null) && serverTransaction.isExpired())
            serverTransaction = null;
        return serverTransaction;
    }

    /**
     * Cancels the {@link StunClientTransaction} with the specified
     * <tt>transactionID</tt>. Cancellation means that the stack will not
     * retransmit the request, will not treat the lack of response to be a
     * failure, but will wait the duration of the transaction timeout for a
     * response.
     *
     * @param transactionID the {@link TransactionID} of the
     * {@link StunClientTransaction} to cancel
     */
    public void cancelTransaction(TransactionID transactionID)
    {
        StunClientTransaction clientTransaction
            = clientTransactions.get(transactionID);

        if(clientTransaction != null)
            clientTransaction.cancel();
    }

    /**
     * Stops all transactions for the specified <tt>localAddr</tt> so that they
     * won't send messages through any longer and so that we could remove the
     * associated socket.
     *
     * @param localAddr the <tt>TransportAddress</tt> that we'd like to remove
     * transactions for.
     * @param remoteAddr the remote <tt>TransportAddress</tt> that we'd like to
     * remove transactions for. If <tt>null</tt>, then it will not be taken
     * into account (that is, all transactions with for <tt>localAddr</tt> will
     * be cancelled).
     */
    private void cancelTransactionsForAddress(TransportAddress localAddr,
                                              TransportAddress remoteAddr)
    {
        List<StunClientTransaction> clientTransactionsToCancel = null;

        synchronized (clientTransactions)
        {
            Iterator<StunClientTransaction> clientTransactionsIter
                = clientTransactions.values().iterator();

            while (clientTransactionsIter.hasNext())
            {
                StunClientTransaction tran = clientTransactionsIter.next();

                if (tran.getLocalAddress().equals(localAddr)
                        && (remoteAddr == null
                                || remoteAddr.equals(tran.getRemoteAddress())))
                {
                    clientTransactionsIter.remove();

                    /*
                     * Invoke StunClientTransaction.cancel() outside the
                     * clientTransactions-synchronized block in order to avoid a
                     * deadlock. Reported by Carl Hasselskog.
                     */
                    if (clientTransactionsToCancel == null)
                    {
                        clientTransactionsToCancel
                            = new LinkedList<StunClientTransaction>();
                    }
                    clientTransactionsToCancel.add(tran);
                }
            }
        }
        /*
         * Invoke StunClientTransaction.cancel() outside the
         * clientTransactions-synchronized block in order to avoid a deadlock.
         * Reported by Carl Hasselskog.
         */
        if (clientTransactionsToCancel != null)
        {
            for (StunClientTransaction tran : clientTransactionsToCancel)
                tran.cancel();
        }

        List<StunServerTransaction> serverTransactionsToExpire = null;

        synchronized (serverTransactions)
        {
            Iterator<StunServerTransaction> serverTransactionsIter
                = serverTransactions.values().iterator();

            while (serverTransactionsIter.hasNext())
            {
                StunServerTransaction tran = serverTransactionsIter.next();
                TransportAddress listenAddr = tran.getLocalListeningAddress();
                TransportAddress sendingAddr = tran.getSendingAddress();

                if (listenAddr.equals(localAddr)
                        || (sendingAddr != null
                                && sendingAddr.equals(localAddr)))
                {
                    if (remoteAddr == null
                          || remoteAddr.equals(tran.getRequestSourceAddress()))
                    {
                        serverTransactionsIter.remove();

                        if (serverTransactionsToExpire == null)
                        {
                            serverTransactionsToExpire
                                    = new LinkedList<StunServerTransaction>();
                        }
                        serverTransactionsToExpire.add(tran);
                    }
                }
            }
        }
        if (serverTransactionsToExpire != null)
        {
            for (StunServerTransaction tran : serverTransactionsToExpire)
                tran.expire();
        }
    }

    /**
     * Initializes a new <tt>StunStack</tt> instance with given
     * peerUdpMessageEventHandler and channelDataEventHandler.
     */
    public StunStack(PeerUdpMessageEventHandler peerUdpMessageEventHandler,
            ChannelDataEventHandler channelDataEventHandler)
    {
        /*
         * The Mac instantiation used in MessageIntegrityAttribute could take
         * several hundred milliseconds so we don't want it instantiated only
         * after we get a response because the delay may cause the transaction
         * to fail.
         */
        synchronized (StunStack.class)
        {
            if (mac == null)
            {
                try
                {
                    mac
                        = Mac.getInstance(
                                MessageIntegrityAttribute.HMAC_SHA1_ALGORITHM);
                }
                catch (NoSuchAlgorithmException nsaex)
                {
                    nsaex.printStackTrace();
                }
            }
        }
        netAccessManager =
            new NetAccessManager(this, peerUdpMessageEventHandler,
                channelDataEventHandler);
    }
    
    /**
     * Initializes a new <tt>StunStack</tt> instance.
     */
    public StunStack()
    {
        this(null,null);
    }
    
    /**
     * Returns the currently active instance of NetAccessManager.
     * @return the currently active instance of NetAccessManager.
     */
    NetAccessManager getNetAccessManager()
    {
        return netAccessManager;
    }

    /**
     * Sends a specific STUN <tt>Indication</tt> to a specific destination
     * <tt>TransportAddress</tt> through a socket registered with this
     * <tt>StunStack</tt> using a specific <tt>TransportAddress</tt>.
     *
     * @param channelData the STUN <tt>Indication</tt> to be sent to the
     * specified destination <tt>TransportAddress</tt> through the socket with
     * the specified <tt>TransportAddress</tt>
     * @param sendTo the <tt>TransportAddress</tt> of the destination to which
     * the specified <tt>indication</tt> is to be sent
     * @param sendThrough the <tt>TransportAddress</tt> of the socket registered
     * with this <tt>StunStack</tt> through which the specified
     * <tt>indication</tt> is to be sent
     * @throws StunException if anything goes wrong while sending the specified
     * <tt>indication</tt> to the destination <tt>sendTo</tt> through the socket
     * identified by <tt>sendThrough</tt>
     */
    public void sendChannelData(
            ChannelData channelData,
            TransportAddress sendTo,
            TransportAddress sendThrough)
        throws StunException
    {
        try
        {
            getNetAccessManager().sendMessage(channelData, sendThrough, sendTo);
        }
        catch (StunException stex)
        {
            throw stex;
        }
        catch (IllegalArgumentException iaex)
        {
            throw new StunException(
                    StunException.ILLEGAL_ARGUMENT,
                    "Failed to send STUN indication: " + channelData,
                    iaex);
        }
        catch (IOException ioex)
        {
            throw new StunException(
                    StunException.NETWORK_ERROR,
                    "Failed to send STUN indication: " + channelData,
                    ioex);
        }
    }
    

    /**
     * Sends a specific STUN <tt>Indication</tt> to a specific destination
     * <tt>TransportAddress</tt> through a socket registered with this
     * <tt>StunStack</tt> using a specific <tt>TransportAddress</tt>.
     *
     * @param udpMessage the <tt>RawMessage</tt> to be sent to the
     * specified destination <tt>TransportAddress</tt> through the socket with
     * the specified <tt>TransportAddress</tt>
     * @param sendTo the <tt>TransportAddress</tt> of the destination to which
     * the specified <tt>indication</tt> is to be sent
     * @param sendThrough the <tt>TransportAddress</tt> of the socket registered
     * with this <tt>StunStack</tt> through which the specified
     * <tt>indication</tt> is to be sent
     * @throws StunException if anything goes wrong while sending the specified
     * <tt>indication</tt> to the destination <tt>sendTo</tt> through the socket
     * identified by <tt>sendThrough</tt>
     */
    public void sendUdpMessage(
            RawMessage udpMessage,
            TransportAddress sendTo,
            TransportAddress sendThrough)
        throws StunException
    {
        
        try
        {
            getNetAccessManager().sendMessage(
                udpMessage.getBytes(), sendThrough, sendTo);
        }
        catch (StunException stex)
        {
            throw stex;
        }
        catch (IllegalArgumentException iaex)
        {
            throw new StunException(
                    StunException.ILLEGAL_ARGUMENT,
                    "Failed to send STUN indication: " + udpMessage,
                    iaex);
        }
        catch (IOException ioex)
        {
            throw new StunException(
                    StunException.NETWORK_ERROR,
                    "Failed to send STUN indication: " + udpMessage,
                    ioex);
        }
    }

    /**
     * Sends a specific STUN <tt>Indication</tt> to a specific destination
     * <tt>TransportAddress</tt> through a socket registered with this
     * <tt>StunStack</tt> using a specific <tt>TransportAddress</tt>.
     *
     * @param indication the STUN <tt>Indication</tt> to be sent to the
     * specified destination <tt>TransportAddress</tt> through the socket with
     * the specified <tt>TransportAddress</tt>
     * @param sendTo the <tt>TransportAddress</tt> of the destination to which
     * the specified <tt>indication</tt> is to be sent
     * @param sendThrough the <tt>TransportAddress</tt> of the socket registered
     * with this <tt>StunStack</tt> through which the specified
     * <tt>indication</tt> is to be sent
     * @throws StunException if anything goes wrong while sending the specified
     * <tt>indication</tt> to the destination <tt>sendTo</tt> through the socket
     * identified by <tt>sendThrough</tt>
     */
    public void sendIndication(
            Indication indication,
            TransportAddress sendTo,
            TransportAddress sendThrough)
        throws StunException
    {
        if (indication.getTransactionID() == null)
        {
            indication.setTransactionID(
                    TransactionID.createNewTransactionID().getBytes());
        }

        try
        {
            getNetAccessManager().sendMessage(indication, sendThrough, sendTo);
        }
        catch (IllegalArgumentException iaex)
        {
            throw new StunException(
                    StunException.ILLEGAL_ARGUMENT,
                    "Failed to send STUN indication: " + indication,
                    iaex);
        }
        catch (IOException ioex)
        {
            throw new StunException(
                    StunException.NETWORK_ERROR,
                    "Failed to send STUN indication: " + indication,
                    ioex);
        }
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the local address to use when sending the request
     * @param  collector   the instance to notify when a response arrives or the
     *                     the transaction timeouts
     *
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     */
    public TransactionID sendRequest(  Request           request,
                                       TransportAddress  sendTo,
                                       TransportAddress  sendThrough,
                                       ResponseCollector collector)
        throws IOException, IllegalArgumentException
    {
        return sendRequest(request, sendTo, sendThrough, collector,
                        TransactionID.createNewTransactionID());
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the local address to use when sending the request
     * @param  collector   the instance to notify when a response arrives or the
     * the transaction timeouts
     * @param transactionID the ID that we'd like the new transaction to use
     * in case the application created it in order to use it for application
     * data correlation.
     *
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     */
    public TransactionID sendRequest(Request           request,
                                     TransportAddress  sendTo,
                                     TransportAddress  sendThrough,
                                     ResponseCollector collector,
                                     TransactionID     transactionID)
        throws IllegalArgumentException,
               IOException
    {
        return
            sendRequest(
                    request, sendTo, sendThrough, collector, transactionID,
                    -1, -1, -1);
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the local address to use when sending the request
     * @param  collector   the instance to notify when a response arrives or the
     * the transaction timeouts
     * @param transactionID the ID that we'd like the new transaction to use
     * in case the application created it in order to use it for application
     * data correlation.
     * @param originalWaitInterval
     * @param maxWaitInterval
     * @param maxRetransmissions
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     */
    public TransactionID sendRequest(Request           request,
                                     TransportAddress  sendTo,
                                     TransportAddress  sendThrough,
                                     ResponseCollector collector,
                                     TransactionID     transactionID,
                                     int               originalWaitInterval,
                                     int               maxWaitInterval,
                                     int               maxRetransmissions)
        throws IllegalArgumentException,
               IOException
    {
        StunClientTransaction clientTransaction
            = new StunClientTransaction(
                    this,
                    request,
                    sendTo,
                    sendThrough,
                    collector,
                    transactionID);

        if (originalWaitInterval > 0)
            clientTransaction.originalWaitInterval = originalWaitInterval;
        if (maxWaitInterval > 0)
            clientTransaction.maxWaitInterval = maxWaitInterval;
        if (maxRetransmissions >= 0)
            clientTransaction.maxRetransmissions = maxRetransmissions;

        clientTransactions.put(
                clientTransaction.getTransactionID(),
                clientTransaction);

        clientTransaction.sendRequest();

        return clientTransaction.getTransactionID();
    }

    /**
     * Sends the specified request through the specified access point, and
     * registers the specified ResponseCollector for later notification.
     * @param  request     the request to send
     * @param  sendTo      the destination address of the request.
     * @param  sendThrough the socket that we should send the request through.
     * @param  collector   the instance to notify when a response arrives or the
     *                     the transaction timeouts
     *
     * @return the <tt>TransactionID</tt> of the <tt>StunClientTransaction</tt>
     * that we used in order to send the request.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     */
    public TransactionID sendRequest( Request           request,
                                      TransportAddress  sendTo,
                                      DatagramSocket    sendThrough,
                                      ResponseCollector collector )
        throws IOException, IllegalArgumentException
    {
        TransportAddress sendThroughAddr = new TransportAddress(
            sendThrough.getLocalAddress(), sendThrough.getLocalPort(),
                Transport.UDP);

        return sendRequest(request, sendTo, sendThroughAddr, collector);
    }

    /**
     * Sends the specified response message through the specified access point.
     *
     * @param transactionID the id of the transaction to use when sending the
     * response. Actually we are getting kind of redundant here as we already
     * have the id in the response object, but I am bringing out as an extra
     * parameter as the user might otherwise forget to explicitly set it.
     * @param response      the message to send.
     * @param sendThrough   the local address to use when sending the message.
     * @param sendTo        the destination of the message.
     *
     * @throws IOException  if an error occurs while sending message bytes
     * through the network socket.
     * @throws IllegalArgumentException if the apDescriptor references an
     * access point that had not been installed,
     * @throws StunException if message encoding fails
     */
    public void sendResponse(byte[]           transactionID,
                             Response         response,
                             TransportAddress sendThrough,
                             TransportAddress sendTo)
        throws StunException,
               IOException,
               IllegalArgumentException
    {
        TransactionID tid
            = TransactionID.createTransactionID(this, transactionID);
        StunServerTransaction sTran = getServerTransaction(tid);

        if(sTran == null)
        {
            throw new StunException(StunException.TRANSACTION_DOES_NOT_EXIST,
                                "The transaction specified in the response "
                                + "(tid="+ tid.toString() +") "
                                + "object does not exist.");
        }
        else if( sTran.isRetransmitting())
        {
            throw new StunException(StunException.TRANSACTION_ALREADY_ANSWERED,
                                    "The transaction specified in the response "
                                    + "(tid="+ tid.toString() +") "
                                    + "has already seen a previous response. "
                                    + "Response was:\n"
                                    + sTran.getResponse());
        }
        else
        {
            sTran.sendResponse(response, sendThrough, sendTo);
        }
    }

    /**
     * Adds a new <tt>MessageEventHandler</tt> which is to be notified about
     * STUN indications received at a specific local <tt>TransportAddress</tt>.
     *
     * @param localAddr the <tt>TransportAddress</tt> of the local socket for
     * which received STUN indications are to be reported to the specified
     * <tt>MessageEventHandler</tt>
     * @param indicationListener the <tt>MessageEventHandler</tt> which is to be
     * registered for notifications about STUN indications received at the
     * specified local <tt>TransportAddress</tt>
     */
    public void addIndicationListener(
            TransportAddress localAddr,
            MessageEventHandler indicationListener)
    {
        eventDispatcher.addIndicationListener(localAddr, indicationListener);
    }

    /**
     * Adds a new <tt>MessageEventHandler</tt> which is to be notified about
     * old indications received at a specific local <tt>TransportAddress</tt>.
     *
     * @param localAddr the <tt>TransportAddress</tt> of the local socket for
     * which received STUN indications are to be reported to the specified
     * <tt>MessageEventHandler</tt>
     * @param indicationListener the <tt>MessageEventHandler</tt> which is to be
     * registered for notifications about old indications received at the
     * specified local <tt>TransportAddress</tt>
     */
    public void addOldIndicationListener(
            TransportAddress localAddr,
            MessageEventHandler indicationListener)
    {
        eventDispatcher.addOldIndicationListener(localAddr, indicationListener);
    }

    /**
     * Sets the listener that should be notified when a new Request is received.
     * @param requestListener the listener interested in incoming requests.
     */
    public  void addRequestListener(RequestListener requestListener)
    {
        this.eventDispatcher.addRequestListener( requestListener );
    }

    /**
     * Removes an existing <tt>MessageEventHandler</tt> to no longer be notified
     * about STUN indications received at a specific local
     * <tt>TransportAddress</tt>.
     *
     * @param localAddr the <tt>TransportAddress</tt> of the local socket for
     * which received STUN indications are to no longer be reported to the
     * specified <tt>MessageEventHandler</tt>
     * @param indicationListener the <tt>MessageEventHandler</tt> which is to be
     * unregistered for notifications about STUN indications received at the
     * specified local <tt>TransportAddress</tt>
     */
    public void removeIndicationListener(
            TransportAddress localAddr,
            MessageEventHandler indicationListener)
    {
    }

    /**
     * Removes the specified listener from the local listener list. (If any
     * instances of this listener have been registered for a particular
     * access point, they will not be removed).
     * @param listener the RequestListener listener to unregister
     */
    public void removeRequestListener(RequestListener listener)
    {
        this.eventDispatcher.removeRequestListener(listener);
    }

    /**
     * Add a RequestListener for requests coming from a specific NetAccessPoint.
     * The listener will be invoked only when a request event is received on
     * that specific property.
     *
     * @param localAddress The local <tt>TransportAddress</tt> that we would
     * like to listen on.
     * @param listener The ConfigurationChangeListener to be added
     */
    public void addRequestListener( TransportAddress localAddress,
                                    RequestListener  listener)
    {
            eventDispatcher.addRequestListener(localAddress, listener);
    }

    /**
     * Removes a client transaction from this providers client transactions
     * list. The method is used by <tt>StunClientTransaction</tt>s themselves
     * when a timeout occurs.
     *
     * @param tran the transaction to remove.
     */
    void removeClientTransaction(StunClientTransaction tran)
    {
        synchronized (clientTransactions)
        {
            clientTransactions.remove(tran.getTransactionID());
        }
    }

    /**
     * Removes a server transaction from this provider's server transactions
     * list.
     * Method is used by StunServerTransaction-s themselves when they expire.
     * @param tran the transaction to remove.
     */
    void removeServerTransaction(StunServerTransaction tran)
    {
        synchronized (serverTransactions)
        {
            serverTransactions.remove(tran.getTransactionID());
        }
    }

    /**
     * Called to notify this provider for an incoming message.
     *
     * @param ev the event object that contains the new message.
     */
    @Override
    public void handleMessageEvent(StunMessageEvent ev)
    {
        Message msg = ev.getMessage();

        if(logger.isLoggable(Level.FINEST))
        {
            logger.finest(
                    "Received a message on " + ev.getLocalAddress()
                        + " of type:" + (int) msg.getMessageType());
        }

        //request
        if(msg instanceof Request)
        {
            logger.finest("parsing request");

            TransactionID serverTid = ev.getTransactionID();
            StunServerTransaction sTran  = getServerTransaction(serverTid);

            if( sTran != null)
            {
                //requests from this transaction have already been seen
                //retransmit the response if there was any
                logger.finest("found an existing transaction");

                try
                {
                    sTran.retransmitResponse();
                    logger.finest("Response retransmitted");
                }
                catch (Exception ex)
                {
                    //we couldn't really do anything here .. apart from logging
                    logger.log(Level.WARNING,
                               "Failed to retransmit a stun response",
                               ex);
                }

                if(!Boolean.getBoolean(
                        StackProperties.PROPAGATE_RECEIVED_RETRANSMISSIONS))
                {
                    return;
                }
            }
            else
            {
                logger.finest("existing transaction not found");
                sTran
                    = new StunServerTransaction(
                            this,
                            serverTid,
                            ev.getLocalAddress(),
                            ev.getRemoteAddress());

                // if there is an OOM error here, it will lead to
                // NetAccessManager.handleFatalError that will stop the
                // MessageProcessor thread and restart it that will lead again
                // to an OOM error and so on... So stop here right now
                try
                {
                    sTran.start();
                }
                catch(OutOfMemoryError t)
                {
                    logger.info("STUN transaction thread start failed:" + t);
                    return;
                }
                synchronized (serverTransactions)
                {
                    serverTransactions.put(serverTid, sTran);
                    maybeStartServerTransactionExpireThread();
                }
            }

            //validate attributes that need validation.
            try
            {
                validateRequestAttributes(ev);
            }
            catch(Exception exc)
            {
                //validation failed. log get lost.
                logger.log(Level.FINE, "Failed to validate msg: " + ev, exc);
                return;
            }

            try
            {
                eventDispatcher.fireMessageEvent(ev);
            }
            catch (Throwable t)
            {
                Response error;

                logger.log(Level.INFO, "Received an invalid request.", t);
                Throwable cause = t.getCause();

                if(((t instanceof StunException)
                            && ((StunException) t).getID()
                                    == StunException
                                        .TRANSACTION_ALREADY_ANSWERED)
                        || ((cause instanceof StunException)
                                && ((StunException) cause).getID()
                                        == StunException
                                            .TRANSACTION_ALREADY_ANSWERED))
                {
                    // do not try to send an error response since we will
                    // get another TRANSACTION_ALREADY_ANSWERED
                    return;
                }

                if(t instanceof IllegalArgumentException)
                {
                    error
                        = createCorrespondingErrorResponse(
                                msg.getMessageType(),
                                ErrorCodeAttribute.BAD_REQUEST,
                                t.getMessage());
                }
                else
                {
                    error
                        = createCorrespondingErrorResponse(
                                msg.getMessageType(),
                                ErrorCodeAttribute.SERVER_ERROR,
                                "Oops! Something went wrong on our side :(");
                }

                try
                {
                    sendResponse(
                            serverTid.getBytes(),
                            error,
                            ev.getLocalAddress(),
                            ev.getRemoteAddress());
                }
                catch(Exception exc)
                {
                    logger.log(Level.FINE,
                               "Couldn't send a server error response",
                               exc);
                }
            }
        }
        //response
        else if(msg instanceof Response)
        {
            TransactionID tid = ev.getTransactionID();
            StunClientTransaction tran = clientTransactions.remove(tid);

            if(tran != null)
            {
                tran.handleResponse(ev);
            }
            else
            {
                //do nothing - just drop the phantom response.
                logger.fine(
                        "Dropped response - no matching client tran found for"
                            + " tid " + tid + "\n" + "all tids in stock were "
                            + clientTransactions.keySet());
            }
        }
        // indication
        else if (msg instanceof Indication)
        {
            eventDispatcher.fireMessageEvent(ev);
        }
    }

    /**
     * Returns the {@link CredentialsManager} that this stack is using for
     * verification of {@link MessageIntegrityAttribute}s.
     *
     * @return the {@link CredentialsManager} that this stack is using for
     * verification of {@link MessageIntegrityAttribute}s.
     */
    public CredentialsManager getCredentialsManager()
    {
        return credentialsManager;
    }

    /**
     * Cancels all running transactions and prepares for garbage collection
     */
    public void shutDown()
    {
        eventDispatcher.removeAllListeners();

        // clientTransactions
        Collection<StunClientTransaction> clientTransactionsToCancel;

        synchronized (clientTransactions)
        {
            clientTransactionsToCancel
                = new ArrayList<StunClientTransaction>(
                        clientTransactions.values());
            clientTransactions.clear();
        }
        /*
         * Invoke StunClientTransaction.cancel() outside the
         * clientTransactions-synchronized block in order to avoid a deadlock.
         * Reported by Carl Hasselskog.
         */
        for (StunClientTransaction tran : clientTransactionsToCancel)
            tran.cancel();

        // serverTransactions
        Collection<StunServerTransaction> serverTransactionsToExpire;

        synchronized (serverTransactions)
        {
            serverTransactionsToExpire
                = new ArrayList<StunServerTransaction>(
                        serverTransactions.values());
            serverTransactions.clear();
        }
        for (StunServerTransaction tran : serverTransactionsToExpire)
            tran.expire();

        netAccessManager.stop();
    }

    /**
     * Executes actions related specific attributes like asserting proper
     * checksums or verifying the validity of user names.
     *
     * @param evt the {@link StunMessageEvent} that contains the {@link
     * Request} that we need to validate.
     *
     * @throws IllegalArgumentException if there's something in the
     * <tt>attribute</tt> that caused us to discard the whole message (e.g. an
     * invalid checksum
     * or username)
     * @throws StunException if we fail while sending an error response.
     * @throws IOException if we fail while sending an error response.
     */
    private void validateRequestAttributes(StunMessageEvent evt)
        throws IllegalArgumentException, StunException, IOException
    {
        Message request = evt.getMessage();

        //assert valid username
        UsernameAttribute unameAttr = (UsernameAttribute)request
            .getAttribute(Attribute.USERNAME);
        String username = null;

        if (unameAttr != null)
        {
            username = LongTermCredential.toString(unameAttr.getUsername());
            if (!validateUsername(username))
            {
                Response error = createCorrespondingErrorResponse(
                                request.getMessageType(),
                                ErrorCodeAttribute.UNAUTHORIZED,
                                "unknown user " + username);

                sendResponse(request.getTransactionID(), error,
                                evt.getLocalAddress(),
                                evt.getRemoteAddress());

                throw new IllegalArgumentException(
                    "Non-recognized username: " + username);
            }
        }

        //assert Message Integrity
        MessageIntegrityAttribute msgIntAttr
            = (MessageIntegrityAttribute)
                request.getAttribute(Attribute.MESSAGE_INTEGRITY);

        if (msgIntAttr != null)
        {
            //we should complain if we have msg integrity and no username.
            if (unameAttr == null)
            {
                Response error = createCorrespondingErrorResponse(
                                request.getMessageType(),
                                ErrorCodeAttribute.BAD_REQUEST,
                                "missing username");

                sendResponse(request.getTransactionID(), error,
                                evt.getLocalAddress(),
                                evt.getRemoteAddress());

                throw new IllegalArgumentException(
                    "Missing USERNAME in the presence of MESSAGE-INTEGRITY: ");
            }

            if (!validateMessageIntegrity(
                    msgIntAttr,
                    username,
                    true,
                    evt.getRawMessage()))
            {
                Response error = createCorrespondingErrorResponse(
                                request.getMessageType(),
                                ErrorCodeAttribute.UNAUTHORIZED,
                                "Wrong MESSAGE-INTEGRITY value");

                sendResponse(request.getTransactionID(), error,
                                evt.getLocalAddress(),
                                evt.getRemoteAddress());

                throw new IllegalArgumentException(
                    "Wrong MESSAGE-INTEGRITY value.");
            }
        }
        else if(Boolean.getBoolean(StackProperties.REQUIRE_MESSAGE_INTEGRITY))
        {
            // no message integrity
            Response error = createCorrespondingErrorResponse(
                            request.getMessageType(),
                            ErrorCodeAttribute.UNAUTHORIZED,
                            "Missing MESSAGE-INTEGRITY.");

            sendResponse(request.getTransactionID(), error,
                            evt.getLocalAddress(),
                            evt.getRemoteAddress());
            throw new IllegalArgumentException(
                "Missing MESSAGE-INTEGRITY.");
        }

        //look for unknown attributes.
        List<Attribute> allAttributes = request.getAttributes();
        StringBuffer sBuff = new StringBuffer();
        for(Attribute attr : allAttributes)
        {
            if(attr instanceof OptionalAttribute
                && attr.getAttributeType()
                    < Attribute.UNKNOWN_OPTIONAL_ATTRIBUTE)
                sBuff.append(attr.getAttributeType());
        }

        if (sBuff.length() > 0)
        {
            Response error = createCorrespondingErrorResponse(
                    request.getMessageType(),
                    ErrorCodeAttribute.UNKNOWN_ATTRIBUTE,
                    "unknown attribute ", sBuff.toString().toCharArray());

            sendResponse(request.getTransactionID(), error,
                            evt.getLocalAddress(),
                            evt.getRemoteAddress());

            throw new IllegalArgumentException(
                "Unknown attribute(s).");
        }
    }

    /**
     * Recalculates the HMAC-SHA1 signature of the <tt>message</tt> array so
     * that we could compare it with the value brought by the
     * {@link MessageIntegrityAttribute}.
     *
     * @param msgInt the attribute that we need to validate.
     * @param username the user name that the message integrity checksum is
     * supposed to have been built for.
     * @param shortTermCredentialMechanism <tt>true</tt> if <tt>msgInt</tt> is
     * to be validated as part of the STUN short-term credential mechanism or
     * <tt>false</tt> for the STUN long-term credential mechanism
     * @param message the message whose SHA1 checksum we'd need to recalculate.
     *
     * @return <tt>true</tt> if <tt>msgInt</tt> contains a valid SHA1 value and
     * <tt>false</tt> otherwise.
     */
    public boolean validateMessageIntegrity(
            MessageIntegrityAttribute msgInt,
            String                    username,
            boolean                   shortTermCredentialMechanism,
            RawMessage                message)
    {
        int colon = -1;

        if ((username == null)
                || (username.length() < 1)
                || (shortTermCredentialMechanism
                        && ((colon = username.indexOf(":")) < 1)))
        {
            if(logger.isLoggable(Level.FINE))
            {
                logger.log(Level.FINE, "Received a message with an improperly "
                        +"formatted username");
            }
            return false;
        }

        if (shortTermCredentialMechanism)
            username = username.substring(0, colon); // lfrag

        byte[] key = getCredentialsManager().getLocalKey(username);

        if(key == null)
            return false;

        /*
         * Now check whether the SHA1 matches. Using
         * MessageIntegrityAttribute.calculateHmacSha1 on the bytes of the
         * RawMessage will be incorrect if there are other Attributes after the
         * MessageIntegrityAttribute because the value of the
         * MessageIntegrityAttribute is calculated on a STUN "Message Length"
         * upto and including the MESSAGE-INTEGRITY and excluding any Attributes
         * after it.
         */
        byte[] binMsg = new byte[msgInt.getLocationInMessage()];

        System.arraycopy(message.getBytes(), 0, binMsg, 0, binMsg.length);

        char messageLength
            = (char)
                (binMsg.length
                    + Attribute.HEADER_LENGTH
                    + msgInt.getDataLength()
                    - Message.HEADER_LENGTH);

        binMsg[2] = (byte) (messageLength >> 8);
        binMsg[3] = (byte) (messageLength & 0xFF);

        byte[] expectedMsgIntHmacSha1Content;

        try
        {
            expectedMsgIntHmacSha1Content
                = MessageIntegrityAttribute.calculateHmacSha1(
                        binMsg, 0, binMsg.length,
                        key);
        }
        catch (IllegalArgumentException iaex)
        {
            expectedMsgIntHmacSha1Content = null;
        }

        byte[] msgIntHmacSha1Content = msgInt.getHmacSha1Content();

        if (!Arrays.equals(
                expectedMsgIntHmacSha1Content,
                msgIntHmacSha1Content))
        {
            if(logger.isLoggable(Level.FINE))
            {
                logger.log(
                        Level.FINE,
                        "Received a message with a wrong "
                            +"MESSAGE-INTEGRITY HMAC-SHA1 signature: "
                            + "expected: "
                            + toHexString(expectedMsgIntHmacSha1Content)
                            + ", received: "
                            + toHexString(msgIntHmacSha1Content));
            }
            return false;
        }

        if (logger.isLoggable(Level.FINEST))
            logger.finest("Successfully verified msg integrity");
        return true;
    }

    /**
     * Returns a <tt>String</tt> representation of a specific <tt>byte</tt>
     * array as an unsigned integer in base 16.
     *
     * @param bytes the <tt>byte</tt> to get the <tt>String</tt> representation
     * of as an unsigned integer in base 16
     * @return a <tt>String</tt> representation of the specified <tt>byte</tt>
     * array as an unsigned integer in base 16
     */
    private static String toHexString(byte[] bytes)
    {
        if (bytes == null)
            return null;
        else
        {
            StringBuilder hexStringBuilder
                = new StringBuilder(2 * bytes.length);
            char[] hexes
                = new char[]
                            {
                                '0', '1', '2', '3', '4', '5', '6', '7', '8',
                                '9', 'A', 'B', 'C', 'D', 'E', 'F'
                            };

            for (int i = 0; i < bytes.length; i++)
            {
                byte b = bytes[i];

                hexStringBuilder.append(hexes[(b & 0xF0) >> 4]);
                hexStringBuilder.append(hexes[b & 0x0F]);
            }
            return hexStringBuilder.toString();
        }
    }

    /**
     * Asserts the validity of a specific username (e.g. which we've received in
     * a USERNAME attribute).
     *
     * @param username the username to be validated
     * @return <tt>true</tt> if <tt>username</tt> contains a valid username;
     * <tt>false</tt>, otherwise
     */
    private boolean validateUsername(String username)
    {
        int colon = username.indexOf(":");

        if ((username.length() < 1) || (colon < 1))
        {
            if(logger.isLoggable(Level.FINE))
            {
                logger.log(Level.FINE, "Received a message with an improperly "
                        +"formatted username");
            }
            return false;
        }

        String lfrag = username.substring(0, colon);

        return getCredentialsManager().checkLocalUserName(lfrag);
    }

    /**
     * Returns the currently set packet logger.
     * @return the currently available packet logger.
     */
    public static PacketLogger getPacketLogger()
    {
        return packetLogger;
    }

    /**
     * Setting a packet logger for the stack.
     * @param packetLogger the packet logger to use.
     */
    public static void setPacketLogger(PacketLogger packetLogger)
    {
        StunStack.packetLogger = packetLogger;
    }

    /**
     * Checks whether packet logger is set and enabled.
     * @return <tt>true</tt> if we have a packet logger instance and
     *  it is enabled.
     */
    public static boolean isPacketLoggerEnabled()
    {
        return packetLogger != null && packetLogger.isEnabled();
    }

    /**
     * Initializes and starts {@link #serverTransactionExpireThread} if
     * necessary.
     */
    private void maybeStartServerTransactionExpireThread()
    {
        synchronized (serverTransactions)
        {
            if (!serverTransactions.isEmpty()
                    && (serverTransactionExpireThread == null))
            {
                Thread t
                    = new Thread()
                            {
                                @Override
                                public void run()
                                {
                                    runInServerTransactionExpireThread();
                                }
                            };

                t.setDaemon(true);
                t.setName(
                        getClass().getName()
                            + ".serverTransactionExpireThread");

                boolean started = false;

                serverTransactionExpireThread = t;
                try
                {
                    t.start();
                    started = true;
                }
                finally
                {
                    if (!started && (serverTransactionExpireThread == t))
                        serverTransactionExpireThread = null;
                }
            }
        }
    }

    /**
     * Runs in {@link #serverTransactionExpireThread} and expires the
     * <tt>StunServerTransaction</tt>s of this <tt>StunStack</tt> and removes
     * them from {@link #serverTransactions}.
     */
    private void runInServerTransactionExpireThread()
    {
        try
        {
            long idleStartTime = -1;

            do
            {
                synchronized (serverTransactions)
                {
                    try
                    {
                        serverTransactions.wait(StunServerTransaction.LIFETIME);
                    }
                    catch (InterruptedException ie)
                    {
                    }

                    /*
                     * Is the current Thread still designated to expire the
                     * StunServerTransactions of this StunStack?
                     */
                    if (Thread.currentThread() != serverTransactionExpireThread)
                        break;

                    long now = System.currentTimeMillis();

                    /*
                     * Has the current Thread been idle long enough to merit
                     * disposing of it?
                     */
                    if (serverTransactions.isEmpty())
                    {
                        if (idleStartTime == -1)
                            idleStartTime = now;
                        else if (now - idleStartTime > 60 * 1000)
                            break;
                    }
                    else
                    {
                        // Expire the StunServerTransactions of this StunStack.

                        idleStartTime = -1;

                        for (Iterator<StunServerTransaction> i
                                    = serverTransactions.values().iterator();
                                i.hasNext();)
                        {
                            StunServerTransaction serverTransaction = i.next();

                            if (serverTransaction == null)
                            {
                                i.remove();
                            }
                            else if (serverTransaction.isExpired(now))
                            {
                                i.remove();
                                serverTransaction.expire();
                            }
                        }
                    }
                }
            }
            while (true);
        }
        finally
        {
            synchronized (serverTransactions)
            {
                if (serverTransactionExpireThread == Thread.currentThread())
                    serverTransactionExpireThread = null;
                /*
                 * If serverTransactionExpireThread dies unexpectedly and yet it
                 * is still necessary, resurrect it.
                 */
                if (serverTransactionExpireThread == null)
                    maybeStartServerTransactionExpireThread();
            }
        }
    }
    
    /**
     * Returns the Error Response object with specified errorCode and
     * reasonPhrase corresponding to input type.
     * 
     * @param requestType the message type of Request.
     * @param errorCode the errorCode for Error Response object.
     * @param reasonPhrase the reasonPhrase for the Error Response object.
     * @return corresponding Error Response object.
     */
    public Response createCorrespondingErrorResponse(char requestType,
        char errorCode, String reasonPhrase,char... unknownAttributes)
    {
        if (requestType == Message.BINDING_REQUEST)
        {
            if (unknownAttributes != null)
            {
                return MessageFactory.createBindingErrorResponse(
                    errorCode, reasonPhrase, unknownAttributes);
            }
            else
            {
                return MessageFactory.createBindingErrorResponse(
                    errorCode, reasonPhrase);
            }
        }
        else
        {
            return null;
        }
    }
}
