/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.harvest.*;
import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * Google TURN delegate object that will handle GTalk TURN send/receive
 * operations.
 *
 * @author Sebastien Vincent
 */
public class GoogleRelayedCandidateDelegate
    implements MessageEventHandler,
               ResponseCollector
{
    /**
     * The <tt>Logger</tt> used by the
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> class and its instances for
     * logging output.
     */
    private static final Logger logger = Logger
        .getLogger(GoogleRelayedCandidateDelegate.class.getName());

    /**
     * The indicator which determines whether this instance has started
     * executing or has executed its {@link #close()} method.
     */
    private boolean closed = false;

    /**
     * The <tt>DatagramPacket</tt>s which are to be received through this
     * <tt>DatagramSocket</tt> upon calls to its
     * {@link #receive(DatagramPacket)} method. They have been received from the
     * TURN server in the form of Data indications.
     */
    private final List<DatagramPacket> packetsToReceive =
        new LinkedList<DatagramPacket>();

    /**
     * The <tt>DatagramSocket</tt>s which have been sent through this
     * <tt>DatagramSocket</tt> using its {@link #send(DatagramPacket)} method
     * and which are to be relayed through its associated TURN server in the
     * form of Send indications.
     */
    private final List<DatagramPacket> packetsToSend =
        new LinkedList<DatagramPacket>();

    /**
     * The <tt>Thread</tt> which is to send the {@link #packetsToSend} to the
     * associated TURN server.
     */
    private Thread sendThread;

    /**
     * The <tt>GoogleTurnCandidateHarvest</tt> which has harvested
     * {@link #relayedCandidate}.
     */
    private final GoogleTurnCandidateHarvest turnCandidateHarvest;

    /**
     * Username.
     */
    private final String username;

    /**
     * Initializes a new <tt>GoogleRelayedCandidateDatagramSocket</tt> instance
     * which is to be the <tt>socket</tt> of a specific
     * <tt>RelayedCandidate</tt> harvested by a specific
     * <tt>TurnCandidateHarvest</tt>.
     *
     * @param turnCandidateHarvest the <tt>TurnCandidateHarvest</tt> which has
     * harvested <tt>relayedCandidate</tt>
     * @param username username
     * @throws SocketException if anything goes wrong while initializing the new
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> instance
     */
    public GoogleRelayedCandidateDelegate(
        GoogleTurnCandidateHarvest turnCandidateHarvest, String username)
        throws SocketException
    {
        this.turnCandidateHarvest = turnCandidateHarvest;
        this.username = username;

        this.turnCandidateHarvest.harvester.getStunStack()
            .addOldIndicationListener(
                this.turnCandidateHarvest.hostCandidate.getTransportAddress(),
                this);
    }

    /**
     * Closes this datagram socket.
     *
     * @see DatagramSocket#close()
     */
    public void close()
    {
        synchronized (this)
        {
            if (this.closed)
                return;
            else
                this.closed = true;
        }
        synchronized (packetsToReceive)
        {
            packetsToReceive.notifyAll();
        }
        synchronized (packetsToSend)
        {
            packetsToSend.notifyAll();
        }

        turnCandidateHarvest.harvester.getStunStack().removeIndicationListener(
            turnCandidateHarvest.hostCandidate.getTransportAddress(), this);
    }

    /**
     * Notifies this <tt>MessageEventHandler</tt> that a specific STUN message
     * has been received, parsed and is ready for delivery.
     * <tt>GoogleRelayedCandidateDatagramSocket</tt> handles STUN indications
     * sent from the associated TURN server and received at the associated local
     * <tt>TransportAddress</tt>.
     *
     * @param e a <tt>StunMessageEvent</tt> which encapsulates the received STUN
     *            message
     */
    public void handleMessageEvent(StunMessageEvent e)
    {
        Message message = e.getMessage();
        char messageType = message.getMessageType();

        if (messageType != Message.OLD_DATA_INDICATION)
            return;

        if (!turnCandidateHarvest.hostCandidate.getTransportAddress().equals(
            e.getLocalAddress()))
            return;

        // Is it from our TURN server?
        if (!turnCandidateHarvest.harvester.stunServer.equals(e
            .getRemoteAddress()))
            return;

        logger.finest("handle old DATA Indication");

        /*
         * as REMOTE-ADDRESS and XOR-PEER-ADDRESS has the same attribute type we
         * cast it to XorPeerAddressAttribute but we do not apply XOR to get the
         * address
         */
        XorPeerAddressAttribute peerAddressAttribute =
            (XorPeerAddressAttribute) message
                .getAttribute(Attribute.REMOTE_ADDRESS);

        if (peerAddressAttribute == null)
        {
            logger.info("peerAddressAttribute is null");
            return;
        }

        DataAttribute dataAttribute =
            (DataAttribute) message.getAttribute(Attribute.DATA);

        if (dataAttribute == null)
        {
            logger.info("data is null");
            return;
        }

        TransportAddress peerAddress = peerAddressAttribute.getAddress();
        if (peerAddress == null)
            return;

        byte[] data = dataAttribute.getData();

        if (data == null)
            return;

        DatagramPacket packetToReceive;

        try
        {
            packetToReceive
                = new DatagramPacket(data, 0, data.length, peerAddress);
        }
        catch (Throwable t)
        {
            /*
             * The signature of the DatagramPacket constructor was changed
             * in JDK 8 to not declare that it may throw a SocketException.
             */
            if (t instanceof SocketException)
            {
                packetToReceive = null;
            }
            else if (t instanceof Error)
            {
                throw (Error) t;
            }
            else if (t instanceof RuntimeException)
            {
                throw (RuntimeException) t;
            }
            else
            {
                /*
                 * Unfortunately, we cannot re-throw it. Anyway, it was
                 * unlikely to occur on JDK 7.
                 */
                if (t instanceof InterruptedException)
                {
                    Thread.currentThread().interrupt();
                }
                packetToReceive = null;
            }
        }
        if (packetToReceive != null)
        {
            synchronized (packetsToReceive)
            {
                packetsToReceive.add(packetToReceive);
                packetsToReceive.notifyAll();
            }
        }
    }

    /**
     * Notifies this <tt>GoogleRelayedCandidateDatagramSocket</tt> that a
     * specific <tt>Request</tt> it has sent has either failed or received a
     * STUN error <tt>Response</tt>.
     *
     * @param response the <tt>Response</tt> which responds to <tt>request</tt>
     * @param request the <tt>Request</tt> sent by this instance to which
     *            <tt>response</tt> responds
     * @return <tt>true</tt> if the failure or error condition has been handled
     *         and the caller should assume this instance has recovered from it;
     *         otherwise, <tt>false</tt>
     */
    public boolean processErrorOrFailure(Response response, Request request)
    {
        return false;
    }

    /**
     * Notifies this <tt>GoogleRelayedCandidateDatagramSocket</tt> that a
     * specific <tt>Request</tt> it has sent has received a STUN success
     * <tt>Response</tt>.
     *
     * @param response the <tt>Response</tt> which responds to <tt>request</tt>
     * @param request the <tt>Request</tt> sent by this instance to which
     *            <tt>response</tt> responds
     */
    public void processSuccess(Response response, Request request)
    {
    }

    /**
     * Dispatch the specified response.
     *
     * @param response the response to dispatch.
     */
    public void processResponse(StunResponseEvent response)
    {
    }

    /**
     * Notifies this collector that no response had been received after repeated
     * retransmissions of the original request (as described by rfc3489) and
     * that the request should be considered unanswered.
     *
     * @param event the <tt>StunTimeoutEvent</tt> containing a reference to the
     *            transaction that has just failed.
     */
    public void processTimeout(StunTimeoutEvent event)
    {
    }

    /**
     * Receives a datagram packet from this socket. When this method returns,
     * the <tt>DatagramPacket</tt>'s buffer is filled with the data received.
     * The datagram packet also contains the sender's IP address, and the port
     * number on the sender's machine.
     *
     * @param p the <tt>DatagramPacket</tt> into which to place the incoming
     *            data
     * @throws IOException if an I/O error occurs
     * @see DatagramSocket#receive(DatagramPacket)
     */
    public void receive(DatagramPacket p) throws IOException
    {
        synchronized (packetsToReceive)
        {
            while (true)
            {
                /*
                 * According to the javadoc of DatagramSocket#close(), any
                 * thread currently blocked in #receive(DatagramPacket) upon
                 * this socket will throw a SocketException.
                 */
                if (closed)
                {
                    throw new SocketException(
                        GoogleRelayedCandidateDatagramSocket.class
                            .getSimpleName() + " has been closed.");
                }
                else if (packetsToReceive.isEmpty())
                {
                    try
                    {
                        packetsToReceive.wait();
                    }
                    catch (InterruptedException iex)
                    {
                    }
                    continue;
                }
                else
                {
                    DatagramPacket packetToReceive = packetsToReceive.remove(0);

                    MultiplexingDatagramSocket.copy(packetToReceive, p);
                    packetsToReceive.notifyAll();
                    break;
                }
            }
        }
    }

    /**
     * Sends a datagram packet from this socket. The <tt>DatagramPacket</tt>
     * includes information indicating the data to be sent, its length, the IP
     * address of the remote host, and the port number on the remote host.
     *
     * @param p the <tt>DatagramPacket</tt> to be sent
     * @throws IOException if an I/O error occurs
     * @see DatagramSocket#send(DatagramPacket)
     */
    public void send(DatagramPacket p) throws IOException
    {
        synchronized (packetsToSend)
        {
            if (closed)
            {
                throw new IOException(
                    GoogleRelayedCandidateDatagramSocket.class.getSimpleName()
                        + " has been closed.");
            }
            else
            {
                packetsToSend.add(MultiplexingDatagramSocket.clone(p));
                if (sendThread == null)
                    createSendThread();
                else
                    packetsToSend.notifyAll();
            }
        }
    }

    /**
     * Creates {@link #sendThread} which is to send {@link #packetsToSend} to
     * the associated TURN server.
     */
    private void createSendThread()
    {
        sendThread = new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    runInSendThread();
                }
                finally
                {
                    /*
                     * If sendThread is dying and there are packetsToSend, then
                     * spawn a new sendThread.
                     */
                    synchronized (packetsToSend)
                    {
                        if (sendThread == Thread.currentThread())
                            sendThread = null;
                        if ((sendThread == null) && !closed
                            && !packetsToSend.isEmpty())
                            createSendThread();
                    }
                }
            }
        };
        sendThread.start();
    }

    /**
     * Runs in {@link #sendThread} to send {@link #packetsToSend} to the
     * associated TURN server.
     */
    private void runInSendThread()
    {
        synchronized (packetsToSend)
        {
            while (!closed)
            {
                if (packetsToSend.isEmpty())
                {
                    try
                    {
                        packetsToSend.wait();
                    }
                    catch (InterruptedException iex)
                    {
                    }
                    continue;
                }

                int packetToSendCount = packetsToSend.size();

                for (int packetToSendIndex = 0; packetToSendIndex < packetToSendCount; packetToSendIndex++)
                {
                    DatagramPacket packetToSend = packetsToSend.remove(0);
                    TransportAddress peerAddress =
                        new TransportAddress(packetToSend.getAddress(),
                            packetToSend.getPort(), Transport.UDP);
                    byte[] pData = packetToSend.getData();
                    int pOffset = packetToSend.getOffset();
                    int pLength = packetToSend.getLength();
                    byte[] data;

                    if ((pOffset == 0) && (pLength == pData.length))
                        data = pData;
                    else
                    {
                        data = new byte[pLength];
                        System.arraycopy(pData, pOffset, data, 0, pLength);
                    }

                    byte[] transactionID =
                        TransactionID.createNewTransactionID().getBytes();
                    Request sendRequest =
                        MessageFactory.createSendRequest(username, peerAddress,
                            data);

                    try
                    {
                        sendRequest.setTransactionID(transactionID);
                        turnCandidateHarvest.harvester.getStunStack()
                            .sendRequest(
                                sendRequest,
                                turnCandidateHarvest.harvester.stunServer,
                                turnCandidateHarvest.hostCandidate
                                    .getTransportAddress(), this);
                    }
                    catch (Exception e)
                    {
                        logger.fine("Failed to send TURN Send request: " + e);
                    }
                }

                /*
                 * If no packetToSend has been sent by the current iteration,
                 * then we must be waiting for some condition to change in order
                 * to be able to send.
                 */
                if (packetsToSend.size() == packetToSendCount)
                {
                    try
                    {
                        packetsToSend.wait();
                    }
                    catch (InterruptedException iex)
                    {
                    }
                }
            }
        }
    }
}
