/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stack;

import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * The class is used to parse and dispatch incoming messages in a multi-thread
 * manner.
 *
 * @author Emil Ivov
 */
class MessageProcessor
    implements Runnable
{
    /**
     * Our class logger.
     */
    private static final Logger logger
        = Logger.getLogger(MessageProcessor.class.getName());

    /**
     * The listener that will be collecting error notifications.
     */
    private final ErrorHandler errorHandler;

    /**
     * The queue where we store incoming messages until they are collected.
     */
    private final MessageQueue messageQueue;

    /**
     * The listener that will be retrieving <tt>MessageEvent</tt>s
     */
    private final MessageEventHandler messageEventHandler;

    /**
     * The <tt>NetAccessManager</tt> which has created this instance and which
     * is its owner.
     */
    private final NetAccessManager netAccessManager;

    /**
     * The flag that indicates whether we are still running.
     */
    private boolean running = false;

    /**
     * A reference to the thread that we use to execute ourselves.
     */
    private Thread runningThread = null;

    /**
     * Creates a Message processor.
     *
     * @param netAccessManager the <tt>NetAccessManager</tt> which is creating
     * the new instance, is going to be its owner, specifies the
     * <tt>MessageQueue</tt> which is to store incoming messages, specifies the
     * <tt>MessageEventHandler</tt> and represents the <tt>ErrorHandler</tt> to
     * handle exceptions in the new instance
     * @throws IllegalArgumentException if any of the mentioned properties of
     * <tt>netAccessManager</tt> are <tt>null</tt>
     */
    MessageProcessor(NetAccessManager netAccessManager)
        throws IllegalArgumentException
    {
        if (netAccessManager == null)
            throw new NullPointerException("netAccessManager");

        MessageQueue messageQueue = netAccessManager.getMessageQueue();

        if (messageQueue == null)
        {
            throw new IllegalArgumentException(
                    "The message queue may not be null");
        }

        MessageEventHandler messageEventHandler
            = netAccessManager.getMessageEventHandler();

        if(messageEventHandler == null)
        {
            throw new IllegalArgumentException(
                    "The message event handler may not be null");
        }

        this.netAccessManager = netAccessManager;
        this.messageQueue = messageQueue;
        this.messageEventHandler = messageEventHandler;
        this.errorHandler = netAccessManager;
    }

    /**
     * Does the message parsing.
     */
    public void run()
    {
        //add an extra try/catch block that handles uncatched errors and helps
        //avoid having dead threads in our pools.
        try
        {
            StunStack stunStack = netAccessManager.getStunStack();

            while (running)
            {
                RawMessage rawMessage;

                try
                {
                    rawMessage = messageQueue.remove();
                }
                catch (InterruptedException ex)
                {
                    if(isRunning())
                        logger.log(Level.WARNING,
                                "A net access point has gone useless: ", ex);
                    //nothing to do here since we test whether we are running
                    //just beneath ...
                    rawMessage = null;
                }

                // were we asked to stop?
                if (!isRunning())
                    return;
                //anything to parse?
                if (rawMessage == null)
                    continue;

                Message stunMessage = null;
                try
                {
                    stunMessage
                        = Message.decode(rawMessage.getBytes(),
                                         (char) 0,
                                         (char) rawMessage.getMessageLength());
                }
                catch (StunException ex)
                {
                    errorHandler.handleError(
                            "Failed to decode a stun message!",
                            ex);

                    continue; //let this one go and for better luck next time.
                }

                logger.finest("Dispatching a StunMessageEvent.");

                StunMessageEvent stunMessageEvent
                    = new StunMessageEvent(stunStack, rawMessage,
                            stunMessage);

                messageEventHandler.handleMessageEvent(stunMessageEvent);
            }
        }
        catch(Throwable err)
        {
            //notify and bail
            errorHandler.handleFatalError(this, "Unexpected Error!", err);
        }
    }

    /**
     * Start the message processing thread.
     */
    void start()
    {
        this.running = true;

        runningThread = new Thread(this, "Stun4J Message Processor");
        runningThread.setDaemon(true);
        runningThread.start();
    }

    /**
     * Shut down the message processor.
     */
    void stop()
    {
        this.running = false;
        runningThread.interrupt();
    }

    /**
     * Determines whether the processor is still running;
     *
     * @return true if the processor is still authorized to run, and false
     * otherwise.
     */
    boolean isRunning()
    {
        return running;
    }
}
