/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.util.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * This is a utility class used for dispatching incoming request events. We use
 * this class mainly (and probably solely) for its ability to handle listener
 * proxies (i.e. listeners interested in requests received on a particular
 * NetAccessPoint only).
 *
 * @author Emil Ivov
 * @author Lubomir Marinov
 */
public class EventDispatcher
{

    /**
     * The STUN request and indication listeners registered with this
     * <tt>EventDispatcher</tt>.
     */
    private final List<MessageTypeEventHandler<?>> messageListeners
        = new Vector<MessageTypeEventHandler<?>>();

    /**
     * The <tt>Map</tt> of <tt>EventDispatcher</tt>s which keep the
     * registrations of STUN request and indication listeners registered for
     * STUN requests and indications from specific local
     * <tt>TransportAddress<tt>es.
     */
    private final Map<TransportAddress, EventDispatcher> children
        = new Hashtable<TransportAddress, EventDispatcher>();

    /**
     * Initializes a new <tt>EventDispatcher</tt> instance.
     */
    public EventDispatcher()
    {
    }

    /**
     * Registers a specific <tt>MessageEventHandler</tt> for notifications about
     * STUN indications received at a specific local <tt>TransportAddress</tt>.
     *
     * @param localAddr the local <tt>TransportAddress</tt> STUN indications
     * received at which are to be reported to the specified
     * <tt>indicationListener</tt>
     * @param indicationListener the <tt>MessageEventHandler</tt> which is to be
     * registered for notifications about STUN indications received at the
     * specified local <tt>TransportAddress</tt>
     */
    public void addIndicationListener(
            TransportAddress localAddr,
            MessageEventHandler indicationListener)
    {
        addMessageListener(
                localAddr,
                new IndicationEventHandler(indicationListener));
    }

    /**
     * Registers a specific <tt>MessageEventHandler</tt> for notifications about
     * old indications received at a specific local <tt>TransportAddress</tt>.
     *
     * @param localAddr the local <tt>TransportAddress</tt> STUN indications
     * received at which are to be reported to the specified
     * <tt>indicationListener</tt>
     * @param indicationListener the <tt>MessageEventHandler</tt> which is to be
     * registered for notifications about old indications received at the
     * specified local <tt>TransportAddress</tt>
     */
    public void addOldIndicationListener(
            TransportAddress localAddr,
            MessageEventHandler indicationListener)
    {
        addMessageListener(
                localAddr,
                new OldIndicationEventHandler(indicationListener));
    }

    /**
     * Registers a specific <tt>MessageTypeEventHandler</tt> for notifications
     * about received STUN messages.
     *
     * @param messageListener the <tt>MessageTypeEventHandler</tt> which is to
     * be registered for notifications about received STUN messages
     */
    private synchronized void addMessageListener(
            MessageTypeEventHandler<?> messageListener)
    {
        synchronized(messageListeners)
        {
            if(!messageListeners.contains(messageListener))
                messageListeners.add(messageListener);
        }
    }

    /**
     * Registers a specific <tt>MessageTypeEventHandler</tt> for notifications
     * about STUN messages received at a specific local
     * <tt>TransportAddress</tt>.
     *
     * @param localAddr the local <tt>TransportAddress</tt> STUN messages
     * received at which are to be reported to the specified
     * <tt>messageListener</tt>
     * @param messageListener the <tt>MessageTypeEventHandler</tt> which is to
     * be registered for notifications about STUN messages received at the
     * specified local <tt>TransportAddress</tt>
     */
    private synchronized void addMessageListener(
            TransportAddress localAddr,
            MessageTypeEventHandler<?> messageListener)
    {
        synchronized(children)
        {
            EventDispatcher child = children.get(localAddr);

            if (child == null)
            {
                child = new EventDispatcher();
                children.put(localAddr, child);
            }
            child.addMessageListener(messageListener);
        }
    }

    /**
     * Add a RequestListener to the listener list. The listener is registered
     * for requests coming from no matter which NetAccessPoint.
     *
     * @param listener  The ReuqestListener to be added
     */
    public void addRequestListener(RequestListener listener)
    {
        addMessageListener(new RequestListenerMessageEventHandler(listener));
    }

    /**
     * Add a RequestListener for a specific NetAccessPoint. The listener
     * will be invoked only when a call on fireRequestReceived is issued for
     * that specific NetAccessPoint.
     *
     * @param localAddr  The NETAP descriptor that we're interested in.
     * @param listener  The ConfigurationChangeListener to be added
     */
    public void addRequestListener( TransportAddress localAddr,
                                    RequestListener  listener)
    {
        addMessageListener(
                localAddr,
                new RequestListenerMessageEventHandler(listener));
    }

    /**
     * Unregisters a specific <tt>MessageTypeEventHandler</tt> from
     * notifications about received STUN messages.
     *
     * @param messageListener the <tt>MessageTypeEventHandler</tt> to be
     * unregistered for notifications about received STUN messages
     */
    private synchronized void removeMessageListener(
            MessageTypeEventHandler<?> messageListener)
    {
        synchronized(messageListeners)
        {
            messageListeners.remove(messageListener);
        }
    }

    /**
     * Unregisters a specific <tt>MessageTypeEventHandler</tt> from
     * notifications about STUN messages received at a specific local
     * <tt>TransportAddress</tt>.
     *
     * @param localAddr the local <tt>TransportAddress</tt> STUN messages
     * received at which to no longer be reported to the specified
     * <tt>messageListener</tt>
     * @param messageListener the <tt>MessageTypeEventHandler</tt> to be
     * unregistered for notifications about STUN messages received at the
     * specified local <tt>TransportAddress</tt>
     */
    private synchronized void removeMessageListener(
            TransportAddress localAddr,
            MessageTypeEventHandler<?> messageListener)
    {
        synchronized(children)
        {
            EventDispatcher child = children.get( localAddr );

            if (child == null)
                return;
            child.removeMessageListener(messageListener);
        }
    }

    /**
     * Remove a RquestListener from the listener list.
     * This removes a RequestListener that was registered
     * for all NetAccessPoints and would not remove listeners registered for
     * specific NetAccessPointDescriptors.
     *
     * @param listener The RequestListener to be removed
     */
    public void removeRequestListener(RequestListener listener)
    {
        removeMessageListener(new RequestListenerMessageEventHandler(listener));
    }

    /**
     * Remove a RequestListener for a specific NetAccessPointDescriptor. This
     * would only remove the listener for the specified NetAccessPointDescriptor
     * and would not remove it if it was also registered as a wildcard listener.
     *
     * @param localAddr  The NetAPDescriptor that was listened on.
     * @param listener  The RequestListener to be removed
     */
    public void removeRequestListener(TransportAddress localAddr,
                                      RequestListener  listener)
    {
        removeMessageListener(
                localAddr,
                new RequestListenerMessageEventHandler(listener));
    }


    /**
     * Dispatch a StunMessageEvent to any registered listeners.
     *
     * @param evt  The request event to be delivered.
     */
    public void fireMessageEvent(StunMessageEvent evt)
    {
        TransportAddress localAddr = evt.getLocalAddress();
        MessageTypeEventHandler<?>[] messageListenersCopy;

        synchronized(messageListeners)
        {
            messageListenersCopy
                = messageListeners.toArray(
                        new MessageTypeEventHandler<?>[
                                messageListeners.size()]);
        }

        char messageType = (char) (evt.getMessage().getMessageType() & 0x0110);

        for (MessageTypeEventHandler<?> messageListener : messageListenersCopy)
        {
            if (messageType == messageListener.messageType)
                messageListener.handleMessageEvent(evt);
        }

        synchronized(children)
        {
            EventDispatcher child = children.get(localAddr);

            if (child != null)
                child.fireMessageEvent(evt);
        }
    }

    /**
     * Check if there are any listeners for a specific address.
     * (Generic listeners count as well)
     *
     * @param localAddr the NetAccessPointDescriptor.
     * @return true if there are one or more listeners for the specified
     * NetAccessPointDescriptor
     */
    public boolean hasRequestListeners(TransportAddress localAddr)
    {
        synchronized(messageListeners)
        {
            if(!messageListeners.isEmpty())
            {
                // there is a generic listener
                return true;
            }
        }

        synchronized(children)
        {
            if (!children.isEmpty())
            {
                EventDispatcher child = children.get(localAddr);

                if (child != null)
                    return !child.messageListeners.isEmpty();
            }
        }

        return false;
    }

    /**
     * Removes (absolutely all listeners for this event dispatcher).
     */
    public void removeAllListeners()
    {
        messageListeners.clear();
        children.clear();
    }

    /**
     * Implements <tt>MessageEventHandler</tt> for a
     * <tt>MessageEventHandler</tt> which handles STUN indications.
     *
     * @author Lubomir Marinov
     */
    private static class IndicationEventHandler
        extends MessageTypeEventHandler<MessageEventHandler>
    {

        /**
         * Initializes a new <tt>IndicationEventHandler</tt> which is to
         * implement <tt>MessageEventHandler</tt> for a specific
         * <tt>MessageEventHandler</tt> which handles STUN indications.
         *
         * @param indicationListener the <tt>RequestListener</tt> for which the
         * new instance is to implement <tt>MessageEventHandler</tt>
         */
        public IndicationEventHandler(MessageEventHandler indicationListener)
        {
            super(Message.STUN_INDICATION, indicationListener);
        }

        /**
         * Notifies this <tt>MessageEventHandler</tt> that a STUN message has
         * been received, parsed and is ready for delivery.
         *
         * @param e a <tt>StunMessageEvent</tt> which encapsulates the STUN
         * message to be handled
         * @see MessageEventHandler#handleMessageEvent(StunMessageEvent)
         */
        public void handleMessageEvent(StunMessageEvent e)
        {
            delegate.handleMessageEvent(e);
        }
    }

    /**
     * Implements <tt>MessageEventHandler</tt> for a
     * <tt>MessageEventHandler</tt> which handles old DATA indications (0x0115).
     *
     * @author Lubomir Marinov
     * @author Sebastien Vincent
     */
    private static class OldIndicationEventHandler
        extends MessageTypeEventHandler<MessageEventHandler>
    {

        /**
         * Initializes a new <tt>IndicationEventHandler</tt> which is to
         * implement <tt>MessageEventHandler</tt> for a specific
         * <tt>MessageEventHandler</tt> which handles old DATA indications
         * (0x0115).
         *
         * @param indicationListener the <tt>RequestListener</tt> for which the
         * new instance is to implement <tt>MessageEventHandler</tt>
         */
        public OldIndicationEventHandler(MessageEventHandler indicationListener)
        {
            super((char)0x0110, indicationListener);
        }

        /**
         * Notifies this <tt>MessageEventHandler</tt> that a STUN message has
         * been received, parsed and is ready for delivery.
         *
         * @param e a <tt>StunMessageEvent</tt> which encapsulates the STUN
         * message to be handled
         * @see MessageEventHandler#handleMessageEvent(StunMessageEvent)
         */
        public void handleMessageEvent(StunMessageEvent e)
        {
            delegate.handleMessageEvent(e);
        }
    }

    /**
     * Represents the base for providers of <tt>MessageEventHandler</tt>
     * implementations to specific <tt>Object</tt>s.
     *
     * @author Lubomir Marinov
     * @param <T> the type of the delegate to which the notifications are to be
     * forwarded
     */
    private static abstract class MessageTypeEventHandler<T>
        implements MessageEventHandler
    {

        /**
         * The <tt>Object</tt> for which this instance implements
         * <tt>MessageEventHandler</tt>.
         */
        public final T delegate;

        /**
         * The type of the STUN messages that this <tt>MessageEventHandler</tt>
         * is interested in.
         */
        public final char messageType;

        /**
         * Initializes a new <tt>MessageTypeEventHandler</tt> which is to
         * forward STUN messages with a specific type to a specific handler.
         *
         * @param messageType the type of the STUN messages that the new
         * instance is to forward to the specified handler <tt>delegate</tt>
         * @param delegate the handler to which the new instance is to forward
         * STUN messages with the specified <tt>messageType</tt>
         */
        public MessageTypeEventHandler(char messageType, T delegate)
        {
            if (delegate == null)
                throw new NullPointerException("delegate");

            this.messageType = messageType;
            this.delegate = delegate;
        }

        /**
         * Determines whether a specific <tt>Object</tt> is value equal to this
         * <tt>Object</tt>.
         *
         * @param obj the <tt>Object</tt> to be compared to this <tt>Object</tt>
         * for value equality
         * @return <tt>true</tt> if this <tt>Object</tt> is value equal to the
         * specified <tt>obj</tt>
         */
        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (!getClass().isInstance(obj))
                return false;

            MessageTypeEventHandler<?> mteh = (MessageTypeEventHandler<?>) obj;

            return
                (messageType == mteh.messageType)
                    && delegate.equals(mteh.delegate);
        }

        /**
         * Returns a hash code value for this <tt>Object</tt> for the benefit of
         * hashtables.
         *
         * @return a hash code value for this <tt>Object</tt> for the benefit of
         * hashtables
         */
        @Override
        public int hashCode()
        {
            return (messageType | delegate.hashCode());
        }
    }

    /**
     * Implements <tt>MessageEventHandler</tt> for <tt>RequestListener</tt>.
     *
     * @author Lubomir Marinov
     */
    private static class RequestListenerMessageEventHandler
        extends MessageTypeEventHandler<RequestListener>
    {

        /**
         * Initializes a new <tt>RequestListenerMessageEventHandler</tt> which
         * is to implement <tt>MessageEventHandler</tt> for a specific
         * <tt>RequestListener</tt>.
         *
         * @param requestListener the <tt>RequestListener</tt> for which the new
         * instance is to implement <tt>MessageEventHandler</tt>
         */
        public RequestListenerMessageEventHandler(
                RequestListener requestListener)
        {
            super(Message.STUN_REQUEST, requestListener);
        }

        /**
         * Notifies this <tt>MessageEventHandler</tt> that a STUN message has
         * been received, parsed and is ready for delivery.
         *
         * @param e a <tt>StunMessageEvent</tt> which encapsulates the STUN
         * message to be handled
         * @see MessageEventHandler#handleMessageEvent(StunMessageEvent)
         */
        public void handleMessageEvent(StunMessageEvent e)
        {
            delegate.processRequest(e);
        }
    }
}
