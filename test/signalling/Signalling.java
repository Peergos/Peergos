/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package test.signalling;

import java.net.*;

/**
 * A simple signalling utility that we use for ICE tests.
 *
 * @author Emil Ivov
 */
public class Signalling
{
    /**
     * The socket where we send and receive signalling
     */
//    private final Socket signallingSocket;

//    private final SignallingCallback signallingCallback;

    /**
     * Creates a signalling instance over the specified socket.
     *
     * @param socket the socket that this instance should use for signalling
     */
    public Signalling(Socket socket, SignallingCallback signallingCallback)
    {
//        this.signallingSocket = socket;
//        this.signallingCallback = signallingCallback;
    }

    /**
     * Creates a server signalling object. The method will block until a
     * connection is actually received on
     *
     * @param socketAddress our bind address
     * @param signallingCallback the callback that we will deliver signalling
     * to.
     *
     * @return the newly created Signalling object
     *
     * @throws Throwable if anything goes wrong (which could happen with the
     * socket stuff).
     */
    public static Signalling createServerSignalling(
            InetSocketAddress socketAddress,
            SignallingCallback signallingCallback)
        throws Throwable
    {
//        ServerSocket serverSocket = new ServerSocket(socketAddress);
//        Signalling signalling = new Signalling(socketAddress, signallingCallback);
        return null;
    }
}
