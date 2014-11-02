/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.io.*;
import java.net.*;
import java.util.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.socket.*;

/**
 * Implements a <tt>CandidateHarvester</tt> which gathers Google TURN SSLTCP
 * dialect <tt>Candidate</tt>s for a specified {@link Component}.
 *
 * @author Sebastien Vincent
 */
public class GoogleTurnSSLCandidateHarvester
    extends GoogleTurnCandidateHarvester
{
    /**
     * Data for the SSL message sent by the server.
     */
    static final byte SSL_SERVER_HANDSHAKE[] =
    {
        0x16, 0x03, 0x01, 0x00, 0x4a, 0x02, 0x00, 0x00,
        0x46, 0x03, 0x01, 0x42, (byte)0x85, 0x45, (byte)0xa7, 0x27,
        (byte)0xa9, 0x5d, (byte)0xa0, (byte)0xb3, (byte)0xc5, (byte)0xe7, 0x53,
            (byte)0xda,
        0x48, 0x2b, 0x3f, (byte)0xc6, 0x5a, (byte)0xca, (byte)0x89, (byte)0xc1,
        0x58, 0x52, (byte)0xa1, 0x78, 0x3c, 0x5b, 0x17, 0x46,
        0x00, (byte)0x85, 0x3f, 0x20, 0x0e, (byte)0xd3, 0x06, 0x72,
        0x5b, 0x5b, 0x1b, 0x5f, 0x15, (byte)0xac, 0x13, (byte)0xf9,
        (byte)0x88, 0x53, (byte)0x9d, (byte)0x9b, (byte)0xe8, 0x3d, 0x7b, 0x0c,
        0x30, 0x32, 0x6e, 0x38, 0x4d, (byte)0xa2, 0x75, 0x57,
        0x41, 0x6c, 0x34, 0x5c, 0x00, 0x04, 0x00
    };

    /**
     * Data for the SSL message sent by the client.
     */
    static final byte SSL_CLIENT_HANDSHAKE[] =
    {
        (byte)0x80, 0x46, 0x01, 0x03, 0x01, 0x00, 0x2d, 0x00,
        0x00, 0x00, 0x10, 0x01, 0x00, (byte)0x80, 0x03, 0x00,
        (byte)0x80, 0x07, 0x00, (byte)0xc0, 0x06, 0x00, 0x40, 0x02,
        0x00, (byte)0x80, 0x04, 0x00, (byte)0x80, 0x00, 0x00, 0x04,
        0x00, (byte)0xfe, (byte)0xff, 0x00, 0x00, 0x0a, 0x00, (byte)0xfe,
        (byte)0xfe, 0x00, 0x00, 0x09, 0x00, 0x00, 0x64, 0x00,
        0x00, 0x62, 0x00, 0x00, 0x03, 0x00, 0x00, 0x06,
        0x1f, 0x17, 0x0c, (byte)0xa6, 0x2f, 0x00, 0x78, (byte)0xfc,
        0x46, 0x55, 0x2e, (byte)0xb1, (byte)0x83, 0x39, (byte)0xf1, (byte)0xea
    };

    /**
     * Initializes a new <tt>GoogleTurnSSLCandidateHarvester</tt> instance which
     * is to work with a specific Google TURN server.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server the
     * new instance is to work with
     */
    public GoogleTurnSSLCandidateHarvester(TransportAddress turnServer)
    {
        this(turnServer, null, null);
    }

    /**
     * Initializes a new <tt>GoogleTurnSSLCandidateHarvester</tt> instance which is
     * to work with a specific TURN server using a specific username for the
     * purposes of the STUN short-term credential mechanism.
     *
     * @param turnServer the <tt>TransportAddress</tt> of the TURN server the
     * new instance is to work with
     * @param shortTermCredentialUsername the username to be used by the new
     * instance for the purposes of the STUN short-term credential mechanism or
     * <tt>null</tt> if the use of the STUN short-term credential mechanism is
     * not determined at the time of the construction of the new instance
     * @param password The gingle candidates password necessary to use this TURN
     * server.
     */
    public GoogleTurnSSLCandidateHarvester(TransportAddress turnServer,
            String shortTermCredentialUsername,
            String password)
    {
        super(turnServer, shortTermCredentialUsername, password);
    }

    /**
     * Creates a new <tt>GoogleTurnSSLCandidateHarvest</tt> instance which is to
     * perform TURN harvesting of a specific <tt>HostCandidate</tt>.
     *
     * @param hostCandidate the <tt>HostCandidate</tt> for which harvesting is
     * to be performed by the new <tt>TurnCandidateHarvest</tt> instance
     * @return a new <tt>GoogleTurnSSLCandidateHarvest</tt> instance which is to
     * perform TURN harvesting of the specified <tt>hostCandidate</tt>
     * @see StunCandidateHarvester#createHarvest(HostCandidate)
     */
    @Override
    protected GoogleTurnCandidateHarvest createHarvest(
            HostCandidate hostCandidate)
    {
        return
            new GoogleTurnCandidateHarvest(this, hostCandidate, getPassword());
    }

    /**
     * Returns the host candidate.
     * For UDP it simply returns the candidate passed as paramter
     *
     * However for TCP, we cannot return the same hostCandidate because in Java
     * a  "server" socket cannot connect to a destination with the same local
     * address/port (i.e. a Java Socket cannot act as both server/client).
     *
     * @param hostCand HostCandidate
     * @return HostCandidate
     */
    @Override
    protected HostCandidate getHostCandidate(HostCandidate hostCand)
    {
        HostCandidate cand = null;
        Socket sock = null;

        try
        {
            sock = new Socket(stunServer.getAddress(), stunServer.getPort());

            OutputStream outputStream = sock.getOutputStream();
            InputStream inputStream = sock.getInputStream();

            if(sslHandshake(inputStream, outputStream))
            {
                Component parentComponent = hostCand.getParentComponent();

                cand
                    = new HostCandidate(
                            new IceTcpSocketWrapper(
                                    new MultiplexingSocket(sock)),
                            parentComponent,
                            Transport.TCP);
                parentComponent
                    .getParentStream()
                        .getParentAgent()
                            .getStunStack()
                                .addSocket(cand.getStunSocket(null));
            }
        }
        catch (Exception e)
        {
            cand = null;
        }
        finally
        {
            if ((cand == null) && (sock != null))
            {
                try
                {
                    sock.close();
                }
                catch (IOException ioe)
                {
                    /*
                     * We failed to close sock but that should not be much of a
                     * problem because we were not closing it in earlier
                     * revisions.
                     */
                }
            }
        }
        return cand;
    }

    /**
     * Do the SSL handshake (send client certificate and wait for receive server
     * certificate). We explicitely need <tt>InputStream</tt> and
     * <tt>OutputStream</tt> because some <tt>Socket</tt> may redefine
     * getInputStream()/getOutputStream() and we need the original stream.
     *
     * @param inputStream <tt>InputStream</tt> of the socket
     * @param outputStream <tt>OuputStream</tt> of the socket
     * @return true if the SSL handshake is done
     * @throws IOException if something goes wrong
     */
    public static boolean sslHandshake(InputStream inputStream, OutputStream
        outputStream) throws IOException
    {
        byte data[] = new byte[SSL_SERVER_HANDSHAKE.length];

        outputStream.write(SSL_CLIENT_HANDSHAKE);
        inputStream.read(data);

        outputStream = null;
        inputStream = null;

        if(Arrays.equals(data, SSL_SERVER_HANDSHAKE))
        {
            return true;
        }

        return false;
    }
}
