/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.socket;

import java.net.*;
import java.util.*;

/**
 * Implements a list of <tt>DatagramPacket<tt>s received by a
 * <tt>DatagramSocket</tt> or a <tt>Socket</tt>. The list enforces the
 * <tt>SO_RCVBUF</tt> option for the associated <tt>DatagramSocket</tt> or
 * <tt>Socket</tt>.
 *
 * @author Lyubomir Marinov
 */
abstract class SocketReceiveBuffer
    extends LinkedList<DatagramPacket>
{
    private static final int DEFAULT_RECEIVE_BUFFER_SIZE = 1024 * 1024;

    private static final long serialVersionUID = 2804762379509257652L;

    /**
     * The value of the <tt>SO_RCVBUF</tt> option for the associated
     * <tt>DatagramSocket</tt> or <tt>Socket</tt>. Cached for the sake of
     * performance.
     */
    private int receiveBufferSize;

    /**
     * The (total) size in bytes of this receive buffer.
     */
    private int size;

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean add(DatagramPacket p)
    {
        boolean added = super.add(p);

        // Keep track of the (total) size in bytes of this receive buffer in
        // order to be able to enforce SO_RCVBUF.
        if (added && (p != null))
        {
            int pSize = p.getLength();

            if (pSize > 0)
            {
                size += pSize;

                // If the added packet is the only element of this list, do not
                // drop it because of the enforcement of SO_RCVBUF.
                if (size() > 1)
                {
                    // For the sake of performance, do not invoke the method
                    // getReceiveBufferSize() of DatagramSocket or Socket on
                    // every packet added to this buffer.
                    int receiveBufferSize = this.receiveBufferSize;

                    if ((receiveBufferSize <= 0) || (modCount % 1000 == 0))
                    {
                        try
                        {
                            receiveBufferSize = getReceiveBufferSize();
                        }
                        catch (SocketException sex)
                        {
                        }
                        if (receiveBufferSize <= 0)
                        {
                            receiveBufferSize = DEFAULT_RECEIVE_BUFFER_SIZE;
                        }
                        else if (receiveBufferSize
                                < DEFAULT_RECEIVE_BUFFER_SIZE)
                        {
                            // Well, a manual page on SO_RCVBUF talks about
                            // doubling. In order to stay on the safe side and
                            // given that there was no limit on the size of the
                            // buffer before, double the receive buffer size.
                            receiveBufferSize *= 2;
                            if (receiveBufferSize <= 0)
                            {
                                receiveBufferSize
                                    = DEFAULT_RECEIVE_BUFFER_SIZE;
                            }
                        }
                        this.receiveBufferSize = receiveBufferSize;
                    }
                    if (size > receiveBufferSize)
                        remove(0);
                }
            }
        }
        return added;
    }

    /**
     * Gets the value of the <tt>SO_RCVBUF</tt> option for the associated
     * <tt>DatagramSocket</tt> or <tt>Socket</tt> which is the buffer size used
     * by the platform for input on the <tt>DatagramSocket</tt> or
     * <tt>Socket</tt>.
     *
     * @return the value of the <tt>SO_RCVBUF</tt> option for the associated
     * <tt>DatagramSocket</tt> or <tt>Socket</tt>
     * @throws SocketException if there is an error in the underlying protocol
     */
    public abstract int getReceiveBufferSize()
        throws SocketException;

    /**
     * {@inheritDoc}
     */
    @Override
    public DatagramPacket remove(int index)
    {
        DatagramPacket p = super.remove(index);

        // Keep track of the (total) size in bytes of this receive buffer in
        // order to be able to enforce SO_RCVBUF.
        if (p != null)
        {
            int pSize = p.getLength();

            if (pSize > 0)
            {
                size -= pSize;
                if (size < 0)
                    size = 0;
            }
        }
        return p;
    }
}
