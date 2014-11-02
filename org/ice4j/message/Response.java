/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.message;

/**
 * A response descendant of the message class. The primary purpose of the
 * Response class is to allow better functional definition of the classes in the
 * stack package.
 *
 * @author Emil Ivov
 * @author Lubomir Marinov
 */
public class Response
    extends Message
{

    /**
     * Constructor.
     */
    Response()
    {
    }

    /**
     * Determines whether this instance represents a STUN error response.
     *
     * @return <tt>true</tt> if this instance represents a STUN error response;
     * otherwise, <tt>false</tt>
     */
    public boolean isErrorResponse()
    {
        return isErrorResponseType(getMessageType());
    }

    /**
     * Determines whether this instance represents a STUN success response.
     *
     * @return <tt>true</tt> if this instance represents a STUN success
     * response; otherwise, <tt>false</tt>
     */
    public boolean isSuccessResponse()
    {
        return isSuccessResponseType(getMessageType());
    }

    /**
     * Checks whether responseType is a valid response type and if yes sets it
     * as the type of the current instance.
     * @param responseType the type to set
     * @throws IllegalArgumentException if responseType is not a valid
     * response type
     */
    public void setMessageType(char responseType)
        throws IllegalArgumentException
    {
        if(!isResponseType(responseType))
            throw new IllegalArgumentException(
                                    Integer.toString(responseType)
                                        + " is not a valid response type.");


        super.setMessageType(responseType);
    }
}
