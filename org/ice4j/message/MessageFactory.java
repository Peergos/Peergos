/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.message;

import java.io.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;

/**
 * This class provides factory methods to allow an application to create STUN
 * and TURN messages from a particular implementation.
 *
 * @author Emil Ivov
 * @author Sebastien Vincent
 * @author Lubomir Marinov
 * @author Aakash Garg
 */
public class MessageFactory
{

    /**
     * The <tt>Logger</tt> used by the <tt>MessageFactory</tt> class and its
     * instances.
     */
    private static final Logger logger
        = Logger.getLogger(MessageFactory.class.getName());

    /**
     * Creates a default binding request. The request DOES NOT contains a
     * ChangeRequest attribute with zero change ip and change port flags.
     *
     * @return a default binding request.
     */
    public static Request createBindingRequest()
    {
        Request bindingRequest = new Request();
        try
        {
            bindingRequest.setMessageType(Message.BINDING_REQUEST);
        } catch (IllegalArgumentException ex)
        {
            // there should be no exc here since we're the creators.
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        /* do not add this by default */
        /*
         * //add a change request attribute ChangeRequestAttribute attribute =
         * AttributeFactory.createChangeRequestAttribute();
         *
         * try { bindingRequest.putAttribute(attribute); } catch (StunException
         * ex) { //shouldn't happen throw new
         * RuntimeException("Failed to add a change request "
         * +"attribute to a binding request!"); }
         */
        return bindingRequest;
    }

    /**
     * Creates a default binding request. The request contains a ChangeReqeust
     * attribute with zero change ip and change port flags. It also contains the
     * PRIORITY attribute used for ICE processing
     *
     * @param priority the value for the priority attribute
     * @return a BindingRequest header with ICE PRIORITY attribute
     * @throws StunException if we have a problem creating the request
     */
    public static Request createBindingRequest(long priority)
                    throws StunException
    {
        Request bindingRequest = createBindingRequest();

        PriorityAttribute attribute = AttributeFactory
                        .createPriorityAttribute(priority);
        bindingRequest.putAttribute(attribute);

        return bindingRequest;
    }

    /**
     * Creates a default binding request. The request contains a ChangeReqeust
     * attribute with zero change ip and change port flags. It contains the
     * PRIORITY, ICE-CONTROLLED or ICE-CONTROLLING attributes used for ICE
     * processing
     *
     * @param priority the value of the ICE priority attributes
     * @param controlling the value of the controlling attribute
     * @param tieBreaker the value of the ICE tie breaker attribute
     * @return a BindingRequest header with some ICE attributes (PRIORITY,
     * ICE-CONTROLLING / ICE-CONTROLLED)
     * @throws StunException if we have a problem creating the request
     */
    public static Request createBindingRequest(long priority,
                    boolean controlling, long tieBreaker)
                    throws StunException
    {
        Request bindingRequest = createBindingRequest();

        PriorityAttribute attribute = AttributeFactory
                        .createPriorityAttribute(priority);
        bindingRequest.putAttribute(attribute);

        if (controlling)
        {
            IceControllingAttribute iceControllingAttribute = AttributeFactory
                            .createIceControllingAttribute(tieBreaker);
            bindingRequest.putAttribute(iceControllingAttribute);
        } else
        {
            IceControlledAttribute iceControlledAttribute = AttributeFactory
                            .createIceControlledAttribute(tieBreaker);
            bindingRequest.putAttribute(iceControlledAttribute);
        }

        return bindingRequest;
    }

    /**
     * Creates a BindingResponse in a 3489 compliant manner, assigning the
     * specified values to mandatory headers.
     *
     * @param mappedAddress the address to assign the mappedAddressAttribute
     * @param sourceAddress the address to assign the sourceAddressAttribute
     * @param changedAddress the address to assign the changedAddressAttribute
     * @return a BindingResponse assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
    public static Response create3489BindingResponse(
                    TransportAddress mappedAddress,
                    TransportAddress sourceAddress,
                    TransportAddress changedAddress)
                    throws IllegalArgumentException
    {
        Response bindingResponse = new Response();
        bindingResponse.setMessageType(Message.BINDING_SUCCESS_RESPONSE);

        // mapped address
        MappedAddressAttribute mappedAddressAttribute = AttributeFactory
                        .createMappedAddressAttribute(mappedAddress);

        // the changed address and source address attribute were removed in
        // RFC 5389 so we should be prepared to go without them.

        // source address
        SourceAddressAttribute sourceAddressAttribute = null;

        if (sourceAddress != null)
            sourceAddressAttribute = AttributeFactory
                            .createSourceAddressAttribute(sourceAddress);

        // changed address
        ChangedAddressAttribute changedAddressAttribute = null;

        if (changedAddress != null)
            changedAddressAttribute = AttributeFactory
                            .createChangedAddressAttribute(changedAddress);

        bindingResponse.putAttribute(mappedAddressAttribute);

        // the changed address and source address attribute were removed in
        // RFC 5389 so we should be prepared to go without them.

        if (sourceAddressAttribute != null)
            bindingResponse.putAttribute(sourceAddressAttribute);

        if (changedAddressAttribute != null)
            bindingResponse.putAttribute(changedAddressAttribute);

        return bindingResponse;
    }

    /**
     * Creates a BindingResponse in a 5389 compliant manner containing a single
     * <tt>XOR-MAPPED-ADDRESS</tt> attribute
     *
     * @param request the request that created the transaction that this
     * response will belong to.
     * @param mappedAddress the address to assign the mappedAddressAttribute
     * @return a BindingResponse assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
    public static Response createBindingResponse(Request request,
                    TransportAddress mappedAddress)
                    throws IllegalArgumentException
    {
        Response bindingResponse = new Response();
        bindingResponse.setMessageType(Message.BINDING_SUCCESS_RESPONSE);

        // xor mapped address
        XorMappedAddressAttribute xorMappedAddressAttribute = AttributeFactory
                        .createXorMappedAddressAttribute(mappedAddress,
                                        request.getTransactionID());

        bindingResponse.putAttribute(xorMappedAddressAttribute);

        return bindingResponse;
    }

    /**
     * Creates a binding error response according to the specified error code
     * and unknown attributes.
     *
     * @param errorCode the error code to encapsulate in this message
     * @param reasonPhrase a human readable description of the error
     * @param unknownAttributes a char[] array containing the ids of one or more
     * attributes that had not been recognized.
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     *
     * @return a binding error response message containing an error code and a
     * UNKNOWN-ATTRIBUTES header
     */
    public static Response createBindingErrorResponse(char errorCode,
                    String reasonPhrase, char[] unknownAttributes)
        throws IllegalArgumentException
    {
        Response bindingErrorResponse = new Response();
        bindingErrorResponse.setMessageType(Message.BINDING_ERROR_RESPONSE);

        // init attributes
        UnknownAttributesAttribute unknownAttributesAttribute = null;
        ErrorCodeAttribute errorCodeAttribute = AttributeFactory
                        .createErrorCodeAttribute(errorCode,
                                        reasonPhrase);

        bindingErrorResponse.putAttribute(errorCodeAttribute);

        if (unknownAttributes != null)
        {
            unknownAttributesAttribute = AttributeFactory
                            .createUnknownAttributesAttribute();
            for (int i = 0; i < unknownAttributes.length; i++)
            {
                unknownAttributesAttribute
                                .addAttributeID(unknownAttributes[i]);
            }
            bindingErrorResponse
                            .putAttribute(unknownAttributesAttribute);
        }

        return bindingErrorResponse;
    }

    /**
     * Creates a binding error response with UNKNOWN_ATTRIBUTES error code and
     * the specified unknown attributes.
     *
     * @param unknownAttributes a char[] array containing the ids of one or more
     * attributes that had not been recognized.
     * @throws StunException INVALID_ARGUMENTS if one or more of the given
     * parameters had an invalid value.
     * @return a binding error response message containing an error code and a
     * UNKNOWN-ATTRIBUTES header
     */
    public static Response createBindingErrorResponseUnknownAttributes(
                    char[] unknownAttributes) throws StunException
    {
        return createBindingErrorResponse(
                        ErrorCodeAttribute.UNKNOWN_ATTRIBUTE, null,
                        unknownAttributes);
    }

    /**
     * Creates a binding error response with UNKNOWN_ATTRIBUTES error code and
     * the specified unknown attributes and reason phrase.
     *
     * @param reasonPhrase a short description of the error.
     * @param unknownAttributes a char[] array containing the ids of one or more
     * attributes that had not been recognized.
     * @throws StunException INVALID_ARGUMENTS if one or more of the given
     * parameters had an invalid value.
     * @return a binding error response message containing an error code and a
     * UNKNOWN-ATTRIBUTES header
     */
    public static Response createBindingErrorResponseUnknownAttributes(
                    String reasonPhrase, char[] unknownAttributes)
                    throws StunException
    {
        return createBindingErrorResponse(
                        ErrorCodeAttribute.UNKNOWN_ATTRIBUTE,
                        reasonPhrase, unknownAttributes);
    }

    /**
     * Creates a binding error response with an ERROR-CODE attribute.
     *
     * @param errorCode the error code to encapsulate in this message
     * @param reasonPhrase a human readable description of the error.
     *
     * @return a binding error response message containing an error code and a
     * UNKNOWN-ATTRIBUTES header
     */
    public static Response createBindingErrorResponse(char errorCode,
                    String reasonPhrase)
    {
        return createBindingErrorResponse(errorCode, reasonPhrase, null);
    }

    /**
     * Creates a binding error response according to the specified error code.
     *
     * @param errorCode the error code to encapsulate in this message attributes
     * that had not been recognized.
     *
     * @return a binding error response message containing an error code and a
     * UNKNOWN-ATTRIBUTES header
     */
    public static Response createBindingErrorResponse(char errorCode)
    {
        return createBindingErrorResponse(errorCode, null, null);
    }

    /**
     * Creates a default binding indication.
     *
     * @return a default binding indication.
     */
    public static Indication createBindingIndication()
    {
        Indication bindingIndication = new Indication();

        bindingIndication.setMessageType(Message.BINDING_INDICATION);
        return bindingIndication;
    }

    /**
     * Create an allocate request without attribute.
     *
     * @return an allocate request
     */
    public static Request createAllocateRequest()
    {
        Request allocateRequest = new Request();

        try
        {
            allocateRequest.setMessageType(Message.ALLOCATE_REQUEST);
        } catch (IllegalArgumentException ex)
        {
            // there should be no exc here since we're the creators.
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }
        return allocateRequest;
    }

    /**
     * Create an allocate request to allocate an even port. Attention this does
     * not have attributes for long-term authentication.
     *
     * @param protocol requested protocol number
     * @param rFlag R flag for the EVEN-PORT
     * @return an allocation request
     */
    public static Request createAllocateRequest(byte protocol,
                    boolean rFlag)
    {
        Request allocateRequest = new Request();

        try
        {
            allocateRequest.setMessageType(Message.ALLOCATE_REQUEST);

            /* XXX add enum somewhere for transport number */
            if (protocol != 6 && protocol != 17)
                throw new StunException("Protocol not valid!");

            // REQUESTED-TRANSPORT
            allocateRequest.putAttribute(
                    AttributeFactory.createRequestedTransportAttribute(
                            protocol));

            // EVEN-PORT
            if (rFlag)
            {
                allocateRequest.putAttribute(
                        AttributeFactory.createEvenPortAttribute(rFlag));
            }
        }
        catch (StunException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }
        return allocateRequest;
    }

    /**
     * Creates a AllocationResponse in a 5389 compliant manner containing at
     * most 4 attributes
     *<br><tt>XOR-RELAYED-ADDRESS</tt> attribute
     *<br><tt>LIFETIME</tt> attribute
     *<br><tt>XOR-MAPPED-ADDRESS</tt> attribute
     *
     * @param request the request that created the transaction that this
     * response will belong to.
     * @param mappedAddress the address to assign the mappedAddressAttribute
     * @param relayedAddress the address to assign the relayedAddressAttribute
     * @param lifetime the address to assign the lifetimeAttribute
     * @return a AllocationResponse assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
    public static Response createAllocationResponse(
            Request request,
            TransportAddress mappedAddress,
            TransportAddress relayedAddress,
            int lifetime )
        throws IllegalArgumentException
    {
	    return createAllocationResponse(
            request, mappedAddress, relayedAddress, null ,lifetime);
    }
    
    /**
     * Creates a AllocationResponse in a 5389 compliant manner containing at most 4 attributes
     *<br><tt>XOR-RELAYED-ADDRESS</tt> attribute
     *<br><tt>LIFETIME</tt> attribute
     *<br><tt>RESERVATION-TOKEN</tt> attribute
     *<br><tt>XOR-MAPPED-ADDRESS</tt> attribute
     *
     * @param request the request that created the transaction that this
     * response will belong to.
     * @param mappedAddress the address to assign the mappedAddressAttribute
     * @param relayedAddress the address to assign the relayedAddressAttribute
     * @param token the address to assign the reservationTokenAttribute
     * @param lifetime the address to assign the lifetimeAttribute
     * @return a AllocationResponse assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
    public static Response createAllocationResponse(
            Request request,
            TransportAddress mappedAddress,
            TransportAddress relayedAddress,
            byte[] token,
            int lifetime)
        throws IllegalArgumentException
    {
        Response allocationSuccessResponse = new Response();

        allocationSuccessResponse.setMessageType(Message.ALLOCATE_RESPONSE);

        // xor mapped address
        XorMappedAddressAttribute xorMappedAddressAttribute
            = AttributeFactory
                .createXorMappedAddressAttribute(
                        mappedAddress, request.getTransactionID());

        allocationSuccessResponse.putAttribute(xorMappedAddressAttribute);

        //xor relayed address
        XorRelayedAddressAttribute xorRelayedAddressAttribute
            = AttributeFactory
        		.createXorRelayedAddressAttribute(relayedAddress,
        				request.getTransactionID());

        allocationSuccessResponse.putAttribute(xorRelayedAddressAttribute);

        //lifetime
        LifetimeAttribute lifetimeAttribute
            = AttributeFactory.createLifetimeAttribute(lifetime);

        allocationSuccessResponse.putAttribute(lifetimeAttribute);
        
        if(token != null)
        {
            //reservation token
            ReservationTokenAttribute reservationTokenAttribute
                = AttributeFactory
            		.createReservationTokenAttribute(token);

            allocationSuccessResponse.putAttribute(reservationTokenAttribute);
        }
        
        return allocationSuccessResponse;
    }
    
    /**
     * Creates a allocation error response according to the specified error
     * code.
     *
     * @param errorCode the error code to encapsulate in this message attributes
     * that had not been recognised.
     *
     * @return a allocation error response message containing an error code.
     */
    public static Response createAllocationErrorResponse(char errorCode)
    {
	    return createAllocationErrorResponse(errorCode,null);
    }
       
    /**
     * Creates a allocation error response according to the specified error
     * code.
     *
     * @param errorCode the error code to encapsulate in this message
     * @param reasonPhrase a human readable description of the error
     * attributes that had not been recognised.
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     *
     * @return a allocation error response message containing an error code.
     */
    public static Response createAllocationErrorResponse(char errorCode,
	    				String reasonPhrase)
    {
        Response allocationErrorResponse = new Response();

        allocationErrorResponse.setMessageType(Message.ALLOCATE_ERROR_RESPONSE);

        //error code attribute
        ErrorCodeAttribute errorCodeAttribute
            = AttributeFactory
                .createErrorCodeAttribute(errorCode,
                                          reasonPhrase);

        allocationErrorResponse.putAttribute(errorCodeAttribute);

        return allocationErrorResponse;
    }
    
    /**
     * Create an allocate request for a Google TURN relay (old TURN protocol
     * modified).
     *
     * @param username short-term username
     * @return an allocation request
     */
    public static Request createGoogleAllocateRequest(String username)
    {
        Request allocateRequest = new Request();
        Attribute usernameAttr = AttributeFactory.createUsernameAttribute(
                username);
        Attribute magicCookieAttr =
            AttributeFactory.createMagicCookieAttribute();

        allocateRequest.setMessageType(Message.ALLOCATE_REQUEST);
        // first attribute is MAGIC-COOKIE
        allocateRequest.putAttribute(magicCookieAttr);
        allocateRequest.putAttribute(usernameAttr);

        return allocateRequest;
    }

    /**
     * Adds the <tt>Attribute</tt>s to a specific <tt>Request</tt> which support
     * the STUN long-term credential mechanism.
     * <p>
     * <b>Warning</b>: The MESSAGE-INTEGRITY <tt>Attribute</tt> will also be
     * added so <tt>Attribute</tt>s added afterwards will not be taken into
     * account for the calculation of the MESSAGE-INTEGRITY value. For example,
     * the FINGERPRINT <tt>Attribute</tt> may still safely be added afterwards,
     * because it is known to appear after the MESSAGE-INTEGRITY.
     * </p>
     *
     * @param request the <tt>Request</tt> in which the <tt>Attribute</tt>s of
     * the STUN long-term credential mechanism are to be added
     * @param username the value for the USERNAME <tt>Attribute</tt> to be added
     * to <tt>request</tt>
     * @param realm the value for the REALM <tt>Attribute</tt> to be added to
     * <tt>request</tt>
     * @param nonce the value for the NONCE <tt>Attribute</tt> to be added to
     * <tt>request</tt>
     *
     * @throws StunException if anything goes wrong while adding the
     * <tt>Attribute</tt>s to <tt>request</tt> which support the STUN long-term
     * credential mechanism
     */
    public static void addLongTermCredentialAttributes(
            Request request,
            byte username[], byte realm[], byte nonce[])
        throws StunException
    {
        UsernameAttribute usernameAttribute
            = AttributeFactory.createUsernameAttribute(username);
        RealmAttribute realmAttribute
            = AttributeFactory.createRealmAttribute(realm);
        NonceAttribute nonceAttribute
            = AttributeFactory.createNonceAttribute(nonce);

        request.putAttribute(usernameAttribute);
        request.putAttribute(realmAttribute);
        request.putAttribute(nonceAttribute);

        // MESSAGE-INTEGRITY
        MessageIntegrityAttribute messageIntegrityAttribute;

        try
        {
            /*
             * The value of USERNAME is a variable-length value. It MUST contain
             * a UTF-8 [RFC3629] encoded sequence of less than 513 bytes, and
             * MUST have been processed using SASLprep [RFC4013].
             */
            messageIntegrityAttribute
                = AttributeFactory.createMessageIntegrityAttribute(
                        new String(username, "UTF-8"));
        }
        catch (UnsupportedEncodingException ueex)
        {
            throw new StunException("username", ueex);
        }
        request.putAttribute(messageIntegrityAttribute);
    }

    /**
     * Creates a new TURN Refresh <tt>Request</tt> without any optional
     * attributes such as LIFETIME.
     *
     * @return a new TURN Refresh <tt>Request</tt> without any optional
     * attributes such as LIFETIME
     */
    public static Request createRefreshRequest()
    {
        Request refreshRequest = new Request();

        try
        {
            refreshRequest.setMessageType(Message.REFRESH_REQUEST);
        }
        catch (IllegalArgumentException iaex)
        {
            /*
             * We don't actually expect the exception to happen so we're
             * ignoring it.
             */
            logger.log(Level.FINE, "Failed to set message type.", iaex);
        }
        return refreshRequest;
    }

    /**
     * Create a refresh request.
     *
     * @param lifetime lifetime value
     * @return refresh request
     */
    public static Request createRefreshRequest(int lifetime)
    {
        Request refreshRequest = new Request();

        try
        {
            refreshRequest.setMessageType(Message.REFRESH_REQUEST);

            /* add a LIFETIME attribute */
            LifetimeAttribute lifetimeReq = AttributeFactory
                            .createLifetimeAttribute(lifetime);
            refreshRequest.putAttribute(lifetimeReq);
        } catch (IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        return refreshRequest;
    }
    
    /**
     * Creates a refresh success response with given lifetime.
     * 
     * @param lifetime the lifetime value to be used.
     * @return refresh error response including the error code attribute.
     */
    public static Response createRefreshResponse(int lifetime)
    {
        Response refreshSuccessResponse = new Response();

        try
        {
            refreshSuccessResponse.setMessageType(Message.REFRESH_RESPONSE);

            //lifetime attribute
            LifetimeAttribute lifetimeAttribute
                = AttributeFactory
                        .createLifetimeAttribute(lifetime);

            refreshSuccessResponse.putAttribute(lifetimeAttribute);
        }
        catch(IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }
        return refreshSuccessResponse;
    }
    
    /**
     * Creates a refresh error response
     * 
     * @param errorCode the error code to encapsulate in this message.
     * @return refresh error response including the error code attribute.
     */
    public static Response createRefreshErrorResponse(char errorCode)
    {
        return createRefreshErrorResponse(errorCode, null);
    }
    
    /**
     * Creates a refresh error response.
     * @param errorCode the error code to encapsulate in this message.
     * @param reasonPhrase a human readable description of the error.
     * @return refresh error response including the error code attribute.
     */
    public static Response createRefreshErrorResponse(
            char errorCode, String reasonPhrase)
    {
        Response refreshErrorResponse = new Response();

        try
        {
            refreshErrorResponse.setMessageType(
                Message.REFRESH_ERROR_RESPONSE);

            ErrorCodeAttribute errorCodeAttribute
                = AttributeFactory
                        .createErrorCodeAttribute(
                            errorCode, reasonPhrase);

            refreshErrorResponse.putAttribute(errorCodeAttribute);
        }
        catch(IllegalArgumentException ex)
        {
             logger.log(Level.FINE, "Failed to set message type.", ex);
        }
        return refreshErrorResponse;
    }

    /**
     * Create a ChannelBind request.
     *
     * @param channelNumber the channel number
     * @param peerAddress the peer address
     * @param tranID the ID of the transaction that we should be using
     *
     * @return channel bind request
     */
    public static Request createChannelBindRequest(char channelNumber,
                    TransportAddress peerAddress, byte[] tranID)
    {
        Request channelBindRequest = new Request();

        try
        {
            channelBindRequest
                            .setMessageType(Message.CHANNELBIND_REQUEST);

            // add a CHANNEL-NUMBER attribute
            ChannelNumberAttribute channelNumberAttribute = AttributeFactory
                            .createChannelNumberAttribute(channelNumber);
            channelBindRequest.putAttribute(channelNumberAttribute);

            // add a XOR-PEER-ADDRESS
            XorPeerAddressAttribute peerAddressAttribute
                = AttributeFactory
                        .createXorPeerAddressAttribute(peerAddress, tranID);
            channelBindRequest.putAttribute(peerAddressAttribute);
        }
        catch (IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        return channelBindRequest;
    }
    
    /**
     * Creates a Channel Bind Success Response.
     * @return Channel Bind Success Response.
     */
    public static Response createChannelBindResponse()
    {
        Response channelBindSuccessResponse = new Response();

        channelBindSuccessResponse.setMessageType(
            Message.CHANNELBIND_RESPONSE);

        return channelBindSuccessResponse;
    }
    
    /**
     * Creates a Channel Bind Error Response with given error code.
     * 
     * @param errorCode the error code to encapsulate in this message.
     * @return Channel Bind Error Response including the error code attribute.
     */
    public static Response createChannelBindErrorResponse(char errorCode)
    {
	    return createChannelBindErrorResponse(errorCode, null);
    }
    
    /**
     * Creates a Channel Bind Error Response with given error code
     * and reasonPhrase.
     * @param errorCode the error code to encapsulate in this message.
     * @param reasonPhrase a human readable description of the error.
     * @return Channel Bind Error Response including the error code attribute.
     */
    public static Response createChannelBindErrorResponse(
            char errorCode, String reasonPhrase)
    {
        Response channelBindErrorResponse = new Response();

        channelBindErrorResponse
            .setMessageType(Message.CHANNELBIND_ERROR_RESPONSE);

        ErrorCodeAttribute errorCodeAttribute
            = AttributeFactory
                .createErrorCodeAttribute(errorCode,reasonPhrase);

        channelBindErrorResponse.putAttribute(errorCodeAttribute);

        return channelBindErrorResponse;
    }
    
    /**
     * Creates a new TURN CreatePermission <tt>Request</tt> with a specific
     * value for its XOR-PEER-ADDRESS attribute.
     *
     * @param peerAddress the value to assigned to the XOR-PEER-ADDRESS
     * attribute
     * @param transactionID the ID of the transaction which is to be used for
     * the assignment of <tt>peerAddress</tt> to the XOR-PEER-ADDRESS attribute
     * @return a new TURN CreatePermission <tt>Request</tt> with the specified
     * value for its XOR-PEER-ADDRESS attribute
     */
    public static Request createCreatePermissionRequest(
            TransportAddress peerAddress,
            byte[] transactionID)
    {
        Request createPermissionRequest = new Request();

        try
        {
            createPermissionRequest.setMessageType(
                    Message.CREATEPERMISSION_REQUEST);
        }
        catch (IllegalArgumentException iaex)
        {
            // Expected to not happen because we are the creators.
            logger.log(Level.FINE, "Failed to set message type.", iaex);
        }
        createPermissionRequest.putAttribute(
                AttributeFactory.createXorPeerAddressAttribute(
                        peerAddress,
                        transactionID));
        return createPermissionRequest;
    }

    /**
     * Creates a create permission success response.
     * 
     * @return CreatePermission Response 
     */
    public static Response createCreatePermissionResponse()
    {
        Response permissionSuccessResponse = new Response();

        permissionSuccessResponse.setMessageType(
            Message.CREATEPERMISSION_RESPONSE);

        return permissionSuccessResponse;
    }
    
    /**
     * Creates a create permission error response.
     * 
     * @param errorCode the error code to encapsulate in this message.
     * @return CreatePermission Error Response with error code attribute.
     */
    public static Response createCreatePermissionErrorResponse(char errorCode)
    {
        return createPermissionErrorResponse(
            errorCode, null);
    }
    
    /**
     * Creates a create permission error response.
     * 
     * @param errorCode the error code to encapsulate in this message.
     * @param reasonPhrase a human readable description of the error.
     * @return CreatePermission Error Response with error code attribute.
     */
    public static Response createPermissionErrorResponse(
            char errorCode, String reasonPhrase)
    {
        Response createPermissionErrorResponse = new Response();

        createPermissionErrorResponse.setMessageType(
            Message.CREATEPERMISSION_ERROR_RESPONSE);

        ErrorCodeAttribute errorCodeAttribute
            = AttributeFactory
                .createErrorCodeAttribute(
                        errorCode,reasonPhrase);

        createPermissionErrorResponse.putAttribute(errorCodeAttribute);

        return createPermissionErrorResponse;
    }
    
    /**
     * Create a Send Indication.
     *
     * @param peerAddress peer address
     * @param data data (could be 0 byte)
     * @param tranID the ID of the transaction that we should be using
     *
     * @return send indication message
     */
    public static Indication createSendIndication(
                    TransportAddress peerAddress, byte[] data, byte[] tranID)
    {
        Indication sendIndication = new Indication();

        try
        {
            sendIndication.setMessageType(Message.SEND_INDICATION);

            /* add XOR-PEER-ADDRESS attribute */
            XorPeerAddressAttribute peerAddressAttribute = AttributeFactory
                            .createXorPeerAddressAttribute(peerAddress, tranID);
            sendIndication.putAttribute(peerAddressAttribute);

            /* add DATA if data */
            if (data != null && data.length > 0)
            {
                DataAttribute dataAttribute = AttributeFactory
                                .createDataAttribute(data);
                sendIndication.putAttribute(dataAttribute);
            }
        } catch (IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        return sendIndication;
    }

    /**
     * Create a Data Indication.
     *
     * @param peerAddress peer address
     * @param data data (could be 0 byte)
     * @param tranID the ID of the transaction that we should be using
     *
     * @return data indication message
     */
    public static Indication createDataIndication(
            TransportAddress peerAddress, byte[] data, byte[] tranID)
    {
        Indication dataIndication = new Indication();

        try
        {
            dataIndication.setMessageType(Message.DATA_INDICATION);

            /* add XOR-PEER-ADDRESS attribute */
            XorPeerAddressAttribute peerAddressAttribute
                = AttributeFactory
                        .createXorPeerAddressAttribute(peerAddress, tranID);
            dataIndication.putAttribute(peerAddressAttribute);

            /* add DATA if data */
            if (data != null && data.length > 0)
            {
                DataAttribute dataAttribute
                    = AttributeFactory
                            .createDataAttribute(data);
                dataIndication.putAttribute(dataAttribute);
            }
        }
        catch (IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        return dataIndication;
    }
    
    /**
     * Create a old Send Request.
     * @param username the username
     * @param peerAddress peer address
     * @param data data (could be 0 byte)
     * @return send indication message
     */
    public static Request createSendRequest(
                    String username, TransportAddress peerAddress, byte[] data)
    {
        Request sendRequest = new Request();

        try
        {
            sendRequest.setMessageType(Message.SEND_REQUEST);

            /* add MAGIC-COOKIE attribute */
            sendRequest.putAttribute(
                    AttributeFactory.createMagicCookieAttribute());

            /* add USERNAME attribute */
            sendRequest.putAttribute(
                    AttributeFactory.createUsernameAttribute(username));

            /* add DESTINATION-ADDRESS attribute */
            DestinationAddressAttribute peerAddressAttribute = AttributeFactory
                            .createDestinationAddressAttribute(peerAddress);
            sendRequest.putAttribute(peerAddressAttribute);

            /* add DATA if data */
            if (data != null && data.length > 0)
            {
                DataAttribute dataAttribute = AttributeFactory
                                .createDataAttributeWithoutPadding(data);
                sendRequest.putAttribute(dataAttribute);
            }
        }
        catch (IllegalArgumentException ex)
        {
            logger.log(Level.FINE, "Failed to set message type.", ex);
        }

        return sendRequest;
    }

    // ======================== NOT CURRENTLY SUPPORTED
    /**
     * Create a shared secret request.
     * WARNING: This is not currently supported.
     *
     * @return request
     */
    public static Request createSharedSecretRequest()
    {
        throw new UnsupportedOperationException(
                        "Shared Secret Support is not currently implemented");
    }

    /**
     * Create a shared secret response.
     * WARNING: This is not currently supported.
     *
     * @return response
     */
    public static Response createSharedSecretResponse()
    {
        throw new UnsupportedOperationException(
                        "Shared Secret Support is not currently implemented");
    }

    /**
     * Create a shared secret error response.
     * WARNING: This is not currently supported.
     *
     * @return error response
     */
    public static Response createSharedSecretErrorResponse()
    {
        throw new UnsupportedOperationException(
                        "Shared Secret Support is not currently implemented");
    }
    
    /**
     * Creates a ConnectRequest in a 6062 compliant manner containing only
     *<br><tt>XOR-PEER-ADDRESS</tt> attribute
     *
     * @param request the request that created the transaction that this
     * response will belong to.
     * @param peerAddress the address to assign the xorPeerAddressAttribute
     * @return a ConnectRequest assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
    public static Request createConnectRequest(
            TransportAddress peerAddress, Request request)
        throws IllegalArgumentException
    {
        Request connectRequest = new Request();

        connectRequest.setMessageType(Message.CONNECT_REQUEST);

        //xor peer address
        XorPeerAddressAttribute xorPeerAddressAttribute
            = AttributeFactory
                .createXorPeerAddressAttribute(
                    peerAddress, request.getTransactionID());

        connectRequest.putAttribute(xorPeerAddressAttribute);

        return connectRequest;
    }
    
    /**
     * Creates a Connect Response in a 6062 compliant manner containing a single
     * <tt>CONNECTION-ID-ATTRIBUTE</tt> attribute
     * @param connectionIdValue the address to assign the connectionIdAttribute
     * @return a ConnectResponse assigning the specified values to mandatory
     * headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */
        
    public static Response createConnectResponse(
            int connectionIdValue)
        throws IllegalArgumentException
    {
        Response connectSuccessResponse = new Response();

        connectSuccessResponse.setMessageType(Message.CONNECT_RESPONSE);

        //connection id
        ConnectionIdAttribute connectionIdAttribute
            = AttributeFactory
                .createConnectionIdAttribute(connectionIdValue);

        connectSuccessResponse.putAttribute(connectionIdAttribute);

        return connectSuccessResponse;
    }
    
    /**
     * Creates a Connect error response according to the specified error code.
     *
     * @param errorCode the error code to encapsulate in this message
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     * @return a Connect error response message containing an error code.
     */
    
    public static Response createConnectErrorResponse(char errorCode)
        throws IllegalArgumentException
    {
	    return createConnectErrorResponse(errorCode, null);
    }
    
    /**
     * Creates a Connect error response according to the specified error code.
     *
     * @param errorCode the error code to encapsulate in this message
     * @param reasonPhrase a human readable description of the error
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     * @return a Connect error response message containing an error code.
     */
    public static Response createConnectErrorResponse(
            char  errorCode, String reasonPhrase )
        throws IllegalArgumentException
    {
        Response connectionErrorResponse = new Response();

        connectionErrorResponse.setMessageType(Message.CONNECT_ERROR_RESPONSE);

        //error code attribute
        ErrorCodeAttribute errorCodeAttribute
            = AttributeFactory
                .createErrorCodeAttribute(errorCode, reasonPhrase);

        connectionErrorResponse.putAttribute(errorCodeAttribute);

        return connectionErrorResponse;
    }

    
    /**
     * Creates a ConnectionBindRequest in a 6062 compliant manner containing
     * only <tt>CONECTION-ID-ATTRIBUTE</tt> attribute.
     *
     * @param connectionIdValue the value to assign the connectionIdAtribute
     * @return a ConnectionBind Request assigning the specified values
     *         to mandatory headers.
     * @throws IllegalArgumentException if there was something wrong with the
     *         way we are trying to create the Request.
     */
    public static Request createConnectionBindRequest(int connectionIdValue)
        throws IllegalArgumentException
    {
        Request connectionBindRequest = new Request();

        connectionBindRequest.setMessageType(Message.CONNECTION_BIND_REQUEST);

        //connection id
        ConnectionIdAttribute connectionIdAttribute
            = AttributeFactory
                .createConnectionIdAttribute(connectionIdValue);

        connectionBindRequest.putAttribute(connectionIdAttribute);

        return connectionBindRequest;
    }
    
    /**
     * Creates a ConnectionBind Response in a 6062 compliant manner.
     *
     * @return a ConnectionBind Response assigning the specified values to
     *         mandatory headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the response.
     */ 
    public static Response createConnectionBindResponse()
        throws IllegalArgumentException
    {
        Response connectSuccessResponse = new Response();

        connectSuccessResponse.setMessageType(
            Message.CONNECTION_BIND_SUCCESS_RESPONSE);

        return connectSuccessResponse;
    }
    
    /**
     * Creates a ConnectionBind error response according to the specified error
     * code.
     *
     * @param errorCode the error code to encapsulate in this message
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     * @return a ConnectionBind error response message containing an error code.
     */
    
    public static Response createConnectionBindErrorResponse(char errorCode)
        throws IllegalArgumentException
    {
	    return createConnectionBindErrorResponse(errorCode,null);
    }
    
    /**
     * Creates a ConnectionBind error response according to the specified error
     * code.
     *
     * @param errorCode the error code to encapsulate in this message
     * @param reasonPhrase a human readable description of the error
     * @throws IllegalArgumentException INVALID_ARGUMENTS if one or more of the
     * given parameters had an invalid value.
     * @return a ConnectionBind error response message containing an error code.
     */
    public static Response createConnectionBindErrorResponse(
            char  errorCode, String reasonPhrase)
        throws IllegalArgumentException
    {
        Response connectionBindErrorResponse = new Response();

        connectionBindErrorResponse.setMessageType(
            Message.CONNECTION_BIND_ERROR_RESPONSE);

        //error code attribute
        ErrorCodeAttribute errorCodeAttribute
            = AttributeFactory
                .createErrorCodeAttribute(errorCode, reasonPhrase);

        connectionBindErrorResponse.putAttribute(errorCodeAttribute);

        return connectionBindErrorResponse;
    }

    /**
     * Creates a ConnectionAttempt Indication in a 6062 compliant manner
     * containing only <tt>CONECTION-ID-ATTRIBUTE</tt> attribute and
     * <tt>XOR-PPER-ADDRESS</tt> attribute.
     *
     * @param connectionIdValue the value to assign the connectionidAtribute
     * @param peerAddress the value to assign the xorPeerAddress
     * @return a ConnectionAttempt Indication assigning the specified values to
     *         mandatory headers.
     * @throws IllegalArgumentException if there was something wrong with the
     * way we are trying to create the Request.
     */
    public static Indication createConnectionAttemptIndication(
            int connectionIdValue, TransportAddress peerAddress)
        throws IllegalArgumentException
    {
        Indication connectionAttemptIndication = new Indication();

        connectionAttemptIndication.setMessageType(
            Message.CONNECTION_ATTEMPT_INDICATION);

        //connection id attribute
        ConnectionIdAttribute connectionIdAttribute
            = AttributeFactory
                .createConnectionIdAttribute(connectionIdValue);

        connectionAttemptIndication.putAttribute(connectionIdAttribute);

        //xor peer address attribute
        XorPeerAddressAttribute xorPeerAddressAttribute
            = AttributeFactory
                .createXorPeerAddressAttribute(peerAddress,
                    connectionAttemptIndication.getTransactionID());

        connectionAttemptIndication.putAttribute(xorPeerAddressAttribute);

        return connectionAttemptIndication;
   }
    
}
