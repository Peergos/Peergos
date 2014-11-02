/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

import java.util.*;

import org.ice4j.message.*;
import org.ice4j.stack.*;

/**
 * Represents an <tt>EventObject</tt> which notifies of an event associated with
 * a specific STUN <tt>Message</tt>.
 *
 * @author Lyubomir Marinov
 */
public class BaseStunMessageEvent
    extends EventObject
{
    /**
     * A dummy version UID to suppress warnings.
     */
    private static final long serialVersionUID = 1L;

    /**
     * The STUN <tt>Message</tt> associated with this event.
     */
    private final Message message;

    /**
     * The <tt>StunStack</tt> associated with this instance.
     */
    private final StunStack stunStack;

    /**
     * The ID of the transaction related to {@link #message}.
     */
    private TransactionID transactionID = null;

    /**
     * Initializes a new <tt>BaseStunMessageEvent</tt> associated with a
     * specific STUN <tt>Message</tt>.
     *
     * @param stunStack the <tt>StunStack</tt> to be associated with the new
     * instance
     * @param sourceAddress the <tt>TransportAddress</tt> which is to be
     * reported as the source of the new event
     * @param message the STUN <tt>Message</tt> associated with the new event
     */
    public BaseStunMessageEvent(
            StunStack stunStack,
            TransportAddress sourceAddress,
            Message message)
    {
        super(sourceAddress);

        this.stunStack = stunStack;
        this.message = message;
    }

    /**
     * Gets the STUN <tt>Message</tt> associated with this event.
     *
     * @return the STUN <tt>Message</tt> associated with this event
     */
    public Message getMessage()
    {
        return message;
    }

    /**
     * Gets the <tt>TransportAddress</tt> which is the source of this event.
     *
     * @return the <tt>TransportAddress</tt> which is the source of this event
     */
    protected TransportAddress getSourceAddress()
    {
        return (TransportAddress) getSource();
    }

    /**
     * Gets the <tt>StunStack</tt> associated with this instance.
     *
     * @return the <tt>StunStack</tt> associated with this instance
     */
    public StunStack getStunStack()
    {
        return stunStack;
    }

    /**
     * Gets the ID of the transaction related to the STUN <tt>Message</tt>
     * associated with this event.
     *
     * @return the ID of the transaction related to the STUN <tt>Message</tt>
     * associated with this event
     */
    public TransactionID getTransactionID()
    {
        if (transactionID == null)
        {
            transactionID
                = TransactionID.createTransactionID(
                        getStunStack(),
                        getMessage().getTransactionID());
        }
        return transactionID;
    }

    /**
     * Allows descendants of this class to set the transaction ID so that we
     * don't need to look it up later. This is not mandatory.
     *
     * @param tranID the ID of the transaction associated with this event.
     */
    protected void setTransactionID(TransactionID tranID)
    {
        this.transactionID = tranID;
    }
}
