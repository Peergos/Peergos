/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j;

/**
 * A <tt>StunException</tt> is thrown when a general STUN exception is
 * encountered.
 *
 * @author Emil Ivov
 */
public class StunException extends Exception
{
    /**
     * Serial version UID for this <tt>Serializable</tt> class.
     */
    private static final long serialVersionUID = 35367793L;

    /**
     * Means that the the reason that caused the exception was unclear.
     */
    public static final int UNKNOWN_ERROR = 0;

    /**
     * Indicates that the attempted operation is not possible in the current
     * state of the object.
     */
    public static final int ILLEGAL_STATE = 1;

    /**
     * Indicates that one or more of the passed arguments had invalid values.
     */
    public static final int ILLEGAL_ARGUMENT = 2;

    /**
     * Indicates that an unexpected error has occurred..
     */
    public static final int INTERNAL_ERROR = 3;

    /**
     * Thrown when trying to send responses through a non-existent transaction
     * That may happen when a corresponding request has already been responded
     * to or when no such request has been received.
     */
    public static final int TRANSACTION_DOES_NOT_EXIST = 3;


    /**
     * Indicates that an unexpected error has occurred..
     */
    public static final int NETWORK_ERROR = 4;

    /**
     * Thrown when trying to send responses through a transaction that have
     * already sent a response.
     */
    public static final int TRANSACTION_ALREADY_ANSWERED = 5;

    /**
     * Identifies the exception.
     */
    private int id = 0;

    /**
     * Creates a StunException.
     */
    public StunException()
    {

    }

    /**
     * Creates a StunException setting id as its identifier.
     * @param id an error ID
     */
    public StunException(int id)
    {
        setID(id);
    }

    /**
     * Creates a StunException, setting an error message.
     * @param message an error message.
     */
    public StunException(String message)
    {
        super(message);
    }

    /**
     * Creates a StunException, setting an error message and an error id.
     * @param message an error message.
     * @param id an error id.
     */
    public StunException(int id, String message)
    {
        super(message);
        setID(id);
    }

    /**
     * Creates a StunException, setting an error message an error id and a
     * cause.
     *
     * @param id an error id.
     * @param message an error message.
     * @param cause the error that caused this exception.
     */
    public StunException(int id, String message, Throwable cause)
    {
        super(message, cause);
        setID(id);
    }

    /**
     * Creates a StunException, setting an error message and a cause object.
     * @param message an error message.
     * @param cause the error object that caused this exception.
     */
    public StunException(String message, Throwable cause)
    {
        super(message, cause);
    }

    /**
     * Creates an exception, setting the Throwable object, that caused it.
     * @param cause the error that caused this exception.
     */
    public StunException(Throwable cause)
    {
        super(cause);
    }

    /**
     * Sets the identifier of the error that caused the exception.
     * @param id the identifier of the error that caused the exception.
     */
    public void setID(int id)
    {
        this.id = id;
    }

    /**
     * Returns this exception's identifier.
     * @return this exception's identifier;
     */
    public int getID()
    {
        return id;
    }

}
