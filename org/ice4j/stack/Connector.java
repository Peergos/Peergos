/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.socket.*;

/**
 * The Network Access Point is the most outward part of the stack. It is
 * constructed around a datagram socket and takes care of forwarding incoming
 * messages to the MessageProcessor as well as sending datagrams to the STUN
 * server specified by the original NetAccessPointDescriptor.
 *
 * @author Emil Ivov
 */
class Connector
    implements Runnable
{
    /**
     * Our class logger.
     */
    private static final Logger logger
        = Logger.getLogger(Connector.class.getName());

    /**
     * The message queue is where incoming messages are added.
     */
    private final MessageQueue messageQueue;

    /**
     * The socket object that used by this access point to access the network.
     */
    private IceSocketWrapper sock;

    /**
     * The object that we use to lock socket operations (since the socket itself
     * is often null)
     */
    private final Object sockLock = new Object();

    /**
     * A flag that is set to false to exit the message processor.
     */
    private boolean running;

    /**
     * The instance to be notified if errors occur in the network listening
     * thread.
     */
    private final ErrorHandler errorHandler;

    /**
     * The address that we are listening to.
     */
    private final TransportAddress listenAddress;

    /**
     * The remote address of the socket of this <tt>Connector</tt> if it is
     * a TCP socket, or <tt>null</tt> if it is UDP.
     */
    private final TransportAddress remoteAddress;

    /**
     * Creates a network access point.
     * @param socket the socket that this access point is supposed to use for
     * communication.
     * @param messageQueue the FIFO list where incoming messages should be queued
     * @param errorHandler the instance to notify when errors occur.
     */
    protected Connector(IceSocketWrapper socket,
                        MessageQueue   messageQueue,
                        ErrorHandler   errorHandler)
    {
        this.sock = socket;
        this.messageQueue = messageQueue;
        this.errorHandler = errorHandler;

        Transport transport
            = socket.getUDPSocket() != null ? Transport.UDP : Transport.TCP;

        listenAddress
            = new TransportAddress(socket.getLocalAddress(),
                                   socket.getLocalPort(),
                                   transport);
        if (transport == Transport.UDP)
        {
            remoteAddress = null;
        }
        else
        {
            Socket tcpSocket = socket.getTCPSocket();

            remoteAddress
                = new TransportAddress(tcpSocket.getInetAddress(),
                                       tcpSocket.getPort(),
                                       transport);
        }
    }

    /**
     * Start the network listening thread.
     */
    void start()
    {
        this.running = true;

        Thread thread = new Thread(this, "IceConnector@" + hashCode());

        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Returns the <tt>DatagramSocket</tt> that contains the port and address
     * associated with this access point.
     *
     * @return the <tt>DatagramSocket</tt> associated with this AP.
     */
    protected IceSocketWrapper getSocket()
    {
        return sock;
    }

    /**
     * The listening thread's run method.
     */
    @Override
    public void run()
    {
        DatagramPacket packet = null;

        while (this.running)
        {
            try
            {
                IceSocketWrapper localSock;

                synchronized (sockLock)
                {
                    if (!running)
                        return;

                    localSock = this.sock;
                }

                /*
                 * Make sure localSock's receiveBufferSize is taken into
                 * account including after it gets changed.
                 */
                int receiveBufferSize = 1500;
                /*
                if(localSock.getTCPSocket() != null)
                {
                    receiveBufferSize = localSock.getTCPSocket().
                        getReceiveBufferSize();
                }
                else if(localSock.getUDPSocket() != null)
                {
                    receiveBufferSize = localSock.getUDPSocket().
                        getReceiveBufferSize();
                }
                */

                if (packet == null)
                {
                    packet
                        = new DatagramPacket(
                                new byte[receiveBufferSize],
                                receiveBufferSize);
                }
                else
                {
                    byte[] packetData = packet.getData();

                    if ((packetData == null)
                            || (packetData.length < receiveBufferSize))
                    {
                        packet.setData(
                                new byte[receiveBufferSize],
                                0,
                                receiveBufferSize);
                    }
                    else
                    {
                        /*
                         * XXX Tell the packet it is large enough because the
                         * socket will not look at the length of the data array
                         * property and will just respect the length property.
                         */
                        packet.setLength(receiveBufferSize);
                    }
                }

                localSock.receive(packet);

                //get lost if we are no longer running.
                if(!running)
                    return;

                logger.finest("received datagram");

                RawMessage rawMessage
                    = new RawMessage(
                            packet.getData(),
                            packet.getLength(),
                            new TransportAddress(
                                    packet.getAddress(),
                                    packet.getPort(),
                                    listenAddress.getTransport()),
                            listenAddress);

                messageQueue.add(rawMessage);
            }
            catch (SocketException ex)
            {
                if (running)
                {
                    logger.log(
                            Level.WARNING,
                            "Connector died: " + listenAddress + " -> "
                                    + remoteAddress,
                            ex);

                    stop();
                    //Something wrong has happened
                    errorHandler.handleFatalError(
                            this,
                            "A socket exception was thrown"
                                + " while trying to receive a message.",
                            ex);
                }
                else
                {
                    //The exception was most probably caused by calling
                    //this.stop().
                }
            }
            catch (ClosedChannelException cce)
            {
                logger.log(Level.WARNING,
                           "A net access point has gone useless:", cce);

                stop();
                errorHandler.handleFatalError(
                        this,
                        "ClosedChannelException occurred while listening"
                            + " for messages!",
                        cce);
            }
            catch (IOException ex)
            {
                logger.log(Level.WARNING,
                           "A net access point has gone useless:", ex);

                errorHandler.handleError(ex.getMessage(), ex);
                //do not stop the thread;
            }
            catch (Throwable ex)
            {
                logger.log(Level.WARNING,
                           "A net access point has gone useless:", ex);

                stop();
                errorHandler.handleFatalError(
                        this,
                        "Unknown error occurred while listening for messages!",
                        ex);
            }
        }
    }

    /**
     * Makes the access point stop listening on its socket.
     */
    protected void stop()
    {
        synchronized(sockLock)
        {
            this.running = false;
            if (this.sock != null)
            {
                this.sock.close();
                this.sock = null;
            }
        }
    }

    /**
     * Sends message through this access point's socket.
     *
     * @param message the bytes to send.
     * @param address message destination.
     *
     * @throws IOException if an exception occurs while sending the message.
     */
    void sendMessage(byte[] message, TransportAddress address)
        throws IOException
    {
        DatagramPacket datagramPacket
            = new DatagramPacket(message, 0, message.length, address);

        sock.send(datagramPacket);
    }

    /**
     * Returns a String representation of the object.
     * @return a String representation of the object.
     */
    @Override
    public String toString()
    {
        return
            "ice4j.Connector@" + listenAddress
                + " status: " + (running ? "not" : "") +" running";
     }

     /**
      * Returns the <tt>TransportAddress</tt> that this access point is bound
      * on.
      *
      * @return the <tt>TransportAddress</tt> associated with this AP.
      */
     TransportAddress getListenAddress()
     {
         return listenAddress;
     }

    /**
     * Returns the remote <tt>TransportAddress</tt> in case of TCP, or
     * <tt>null</tt> in case of UDP.
     *
     * @return  the remote <tt>TransportAddress</tt> in case of TCP, or
     * <tt>null</tt> in case of UDP.
     */
    TransportAddress getRemoteAddress()
    {
        return remoteAddress;
    }
}
