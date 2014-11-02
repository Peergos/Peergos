/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.io.*;

/**
 * TCP output stream for TCP socket. It is used to multiplex sockets and keep
 * the <tt>OutputStream</tt> interface to users.
 *
 * @author Sebastien Vincent
 */
public class TCPOutputStream
    extends OutputStream
{
    /**
     * The indicator which determines whether this <tt>TCPOutputStream</tt> is
     * to frame RTP and RTCP packets in accord with RFC 4571 &quot;Framing
     * Real-time Transport Protocol (RTP) and RTP Control Protocol (RTCP)
     * Packets over Connection-Oriented Transport&quot;.
     */
    private final boolean frame;

    /**
     * Original <tt>OutputStream</tt> that this class wraps.
     */
    private final OutputStream outputStream;

    /**
     * Initializes a new <tt>TCPOutputStream</tt>.
     *
     * @param outputStream original <tt>OutputStream</tt>
     */
    public TCPOutputStream(OutputStream outputStream)
    {
        this.outputStream = outputStream;

        // GoogleRelayedCandidateSocket will encapsulate data in TURN message so
        // do not frame.
        frame
            = !(outputStream
                    instanceof GoogleRelayedCandidateSocket.TCPOutputStream);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
        throws IOException
    {
        outputStream.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void flush()
        throws IOException
    {
        outputStream.flush();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(byte[] b, int off, int len)
        throws IOException
    {
        if (frame)
        {
            int newLen = len + 2;
            byte newB[] = new byte[newLen];

            newB[0] = (byte) ((len >> 8) & 0xFF);
            newB[1] = (byte) (len & 0xFF);
            System.arraycopy(b, off, newB, 2, len);
            outputStream.write(newB, 0, newLen);
        }
        else
        {
            outputStream.write(b, off, len);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void write(int b)
        throws IOException
    {
        // TODO Auto-generated method stub
    }
}
