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

import org.ice4j.ice.*;

/**
 * TCP Server Socket wrapper.
 *
 * @author Sebastien Vincent
 */
public class IceTcpServerSocketWrapper
    extends IceSocketWrapper
{
    /**
     * The <tt>Logger</tt> used by the <tt>LocalCandidate</tt> class and its
     * instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(IceTcpServerSocketWrapper.class.getName());

    /**
     * Thread that will wait new connections.
     */
    private Thread acceptThread = null;

    /**
     * The wrapped TCP ServerSocket.
     */
    private final ServerSocket serverSocket;

    /**
     * If the socket is still listening.
     */
    private boolean isRun = false;

    /**
     * STUN stack.
     */
    private final Component component;

    /**
     * List of TCP client sockets.
     */
    private final List<Socket> sockets = new ArrayList<Socket>();

    /**
     * Initializes a new <tt>IceTcpServerSocketWrapper</tt>.
     *
     * @param serverSocket TCP <tt>ServerSocket</tt>
     * @param component related <tt>Component</tt>
     */
    public IceTcpServerSocketWrapper(ServerSocket serverSocket,
        Component component)
    {
        this.serverSocket = serverSocket;
        this.component = component;
        acceptThread = new ThreadAccept();
        acceptThread.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void send(DatagramPacket p) throws IOException
    {
        /* Do nothing for the moment */
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void receive(DatagramPacket p) throws IOException
    {
        /* Do nothing for the moment */
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        try
        {
            isRun = false;
            serverSocket.close();
            for(Socket s : sockets)
            {
                s.close();
            }
        }
        catch(IOException e)
        {
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public InetAddress getLocalAddress()
    {
        return serverSocket.getInetAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int getLocalPort()
    {
        return serverSocket.getLocalPort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SocketAddress getLocalSocketAddress()
    {
        return serverSocket.getLocalSocketAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Socket getTCPSocket()
    {
        if(sockets.size() > 0)
        {
            return sockets.get(0);
        }

        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public DatagramSocket getUDPSocket()
    {
        return null;
    }

    /**
     * Thread that will wait for new TCP connections.
     *
     * @author Sebastien Vincent
     */
    private class ThreadAccept extends Thread
    {
        /**
         * Thread entry point.
         */
        @Override
        public void run()
        {
            isRun = true;

            while(isRun)
            {
                try
                {
                    Socket tcpSocket = serverSocket.accept();

                    if(tcpSocket != null)
                    {
                        MultiplexingSocket multiplexingSocket =
                            new MultiplexingSocket(tcpSocket);
                        component.getParentStream().getParentAgent().
                            getStunStack().addSocket(
                                new IceTcpSocketWrapper(multiplexingSocket));

                        sockets.add(multiplexingSocket);
                    }
                }
                catch(IOException e)
                {
                    logger.info("Failed to accept TCP socket " + e);
                }
            }
        }
    }
}
