/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.message;

import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.stack.*;

/**
 * This class represents a STUN message. Messages are TLV (type-length-value)
 * encoded using big endian (network ordered) binary.  All STUN messages start
 * with a STUN header, followed by a STUN payload.  The payload is a series of
 * STUN attributes, the set of which depends on the message type.  The STUN
 * header contains a STUN message type, transaction ID, and length.
 *
 * @author Emil Ivov
 * @author Sebastien Vincent
 * @author Lyubomir Marinov
 * @author Aakash Garg
 */
public abstract class Message
{
    /**
     * The <tt>Logger</tt> used by the <tt>Message</tt> class and its instances
     * for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(Message.class.getName());

    /* general declaration */
    /**
     * STUN request code.
     */
    public static final char STUN_REQUEST         = 0x0000;

    /**
     * STUN indication code.
     */
    public static final char STUN_INDICATION      = 0x0010;

    /**
     * STUN success response code.
     */
    public static final char STUN_SUCCESS_RESP    = 0x0100;

    /**
     * STUN error response code.
     */
    public static final char STUN_ERROR_RESP      = 0x0110;

    /* STUN methods */
    /**
     * STUN binding method.
     */
    public static final char STUN_METHOD_BINDING = 0x0001;

    /**
     * STUN binding request code.
     */
    public static final char BINDING_REQUEST               =
        (STUN_METHOD_BINDING | STUN_REQUEST);

    /**
     * STUN binding success response code.
     */
    public static final char BINDING_SUCCESS_RESPONSE      =
        (STUN_METHOD_BINDING | STUN_SUCCESS_RESP);

    /**
     * STUN binding error response code.
     */
    public static final char BINDING_ERROR_RESPONSE        =
        (STUN_METHOD_BINDING | STUN_ERROR_RESP);

    /**
     * STUN binding request code.
     */
    public static final char BINDING_INDICATION            =
        (STUN_METHOD_BINDING | STUN_INDICATION);

    /**
     * STUN shared secret request.
     */
    public static final char SHARED_SECRET_REQUEST         = 0x0002;

    /**
     * STUN shared secret response.
     */
    public static final char SHARED_SECRET_RESPONSE        = 0x0102;

    /**
     * STUN shared secret error response.
     */
    public static final char SHARED_SECRET_ERROR_RESPONSE  = 0x0112;

    /* TURN methods */
    /**
     * TURN allocate method code.
     */
    public static final char TURN_METHOD_ALLOCATE  = 0x0003;

    /**
     * TURN refresh method code.
     */
    public static final char TURN_METHOD_REFRESH  = 0x0004;

    /**
     * TURN send method code.
     */
    public static final char TURN_METHOD_SEND  = 0x0006;

    /**
     * TURN data method code.
     */
    public static final char TURN_METHOD_DATA  = 0x0007;

    /**
     * TURN CreatePermission method code.
     */
    public static final char TURN_METHOD_CREATEPERMISSION = 0x0008;

    /**
     * TURN ChannelBind method code.
     */
    public static final char TURN_METHOD_CHANNELBIND  = 0x0009;
    
    /**
     * TURN Connect method code.
     */
    public static final char TURN_METHOD_CONNECT = 0X000a;
    
    /**
     * TURN ConnectionBind method code.
     */
    public static final char TURN_METHOD_CONNECTION_BIND = 0X000b;
    
    /**
     * TURN ConnectionAttempt method code.
     */
    public static final char TURN_METHOD_CONNECTION_ATTEMPT = 0X000c;
    
    /**
     * TURN allocate request code.
     */
    public static final char ALLOCATE_REQUEST =
        (TURN_METHOD_ALLOCATE | STUN_REQUEST);

    /**
     * TURN allocate response code.
     */
    public static final char ALLOCATE_RESPONSE =
        (TURN_METHOD_ALLOCATE | STUN_SUCCESS_RESP);

    /**
     * TURN allocate error response code.
     */
    public static final char ALLOCATE_ERROR_RESPONSE =
        (TURN_METHOD_ALLOCATE | STUN_ERROR_RESP);

    /**
     * TURN refresh request code.
     */
    public static final char REFRESH_REQUEST =
        (TURN_METHOD_REFRESH | STUN_REQUEST);
    
    /**
     * TURN allocate refresh request code.
     */
    public static final char ALLOCATE_REFRESH_REQUEST = 
        (TURN_METHOD_ALLOCATE | REFRESH_REQUEST);
    
    /**
     * TURN refresh response code.
     */
    public static final char REFRESH_RESPONSE =
        (TURN_METHOD_REFRESH | STUN_SUCCESS_RESP);

    /**
     * TURN refresh error response code.
     */
    public static final char REFRESH_ERROR_RESPONSE =
        (TURN_METHOD_REFRESH | STUN_ERROR_RESP);

    /**
     * TURN ChannelBind request code.
     */
    public static final char CHANNELBIND_REQUEST =
        (TURN_METHOD_CHANNELBIND | STUN_REQUEST);

    /**
     * TURN ChannelBind response code.
     */
    public static final char CHANNELBIND_RESPONSE =
        (TURN_METHOD_CHANNELBIND | STUN_SUCCESS_RESP);

    /**
     * TURN ChannelBind error response code.
     */
    public static final char CHANNELBIND_ERROR_RESPONSE =
        (TURN_METHOD_CHANNELBIND | STUN_ERROR_RESP);

    /**
     * TURN CreatePermission request code.
     */
    public static final char CREATEPERMISSION_REQUEST =
        (TURN_METHOD_CREATEPERMISSION | STUN_REQUEST);

    /**
     * TURN CreatePermission response code.
     */
    public static final char CREATEPERMISSION_RESPONSE =
        (TURN_METHOD_CREATEPERMISSION | STUN_SUCCESS_RESP);

    /**
     * TURN CreatePermission error response code.
     */
    public static final char CREATEPERMISSION_ERROR_RESPONSE =
        (TURN_METHOD_CREATEPERMISSION | STUN_ERROR_RESP);

    /**
     * TURN send indication code.
     */
    public static final char SEND_INDICATION =
        (TURN_METHOD_SEND | STUN_INDICATION);

    /**
     * TURN data indication code.
     */
    public static final char DATA_INDICATION =
        (TURN_METHOD_DATA | STUN_INDICATION);

    /**
     * TURN Connect Request code.
     */
    public static final char CONNECT_REQUEST = 
        (TURN_METHOD_CONNECT | STUN_REQUEST);

    /**
     * TURN Connect Success Response code.
     */
    public static final char CONNECT_RESPONSE = 
        (TURN_METHOD_CONNECT | STUN_SUCCESS_RESP);

    /**
     * TURN Connect Error Response code.
     */
    public static final char CONNECT_ERROR_RESPONSE = 
        (TURN_METHOD_CONNECT | STUN_ERROR_RESP);

    /**
     * TURN Connection Bind Request code.
     */
    public static final char CONNECTION_BIND_REQUEST = 
        (TURN_METHOD_CONNECTION_BIND | STUN_REQUEST);

    /**
     * TURN Connection Bind Success Response code.
     */
    public static final char CONNECTION_BIND_SUCCESS_RESPONSE = 
        (TURN_METHOD_CONNECTION_BIND | STUN_SUCCESS_RESP);

    /**
     * TURN Connection Bind error code. 
     */
    public static final char CONNECTION_BIND_ERROR_RESPONSE = 
        (TURN_METHOD_CONNECTION_BIND | STUN_ERROR_RESP);

    /**
     * TURN Connection Attempt Indication code.
     */
    public static final char CONNECTION_ATTEMPT_INDICATION = 
        (TURN_METHOD_CONNECTION_ATTEMPT | STUN_INDICATION);

    /* Old TURN method */
    /**
     * TURN Send request.
     */
    public static final char SEND_REQUEST = 0x0004;

    /**
     * TURN Send request.
     */
    public static final char OLD_DATA_INDICATION = 0x0115;

    //Message fields
    /**
     * The length of Stun Message Headers in bytes
     * = len(Type) + len(DataLength) + len(Transaction ID).
     */
    public static final byte HEADER_LENGTH = 20;

    /**
     * Indicates the type of the message. The message type can be Binding Request,
     * Binding Response, Binding Error Response, Shared Secret Request, Shared
     * Secret Response, or Shared Secret Error Response.
     */
    protected char messageType = 0x0000;

    /**
     * The transaction ID is used to correlate requests and responses.
     */
    protected byte[] transactionID = null;

    /**
     * The magic cookie (0x2112A442).
     */
    public static final byte[] MAGIC_COOKIE = { 0x21, 0x12, (byte)0xA4, 0x42 };

    /**
     * The length of the transaction id (in bytes).
     */
    public static final byte TRANSACTION_ID_LENGTH = 12;

    /**
     * The length of the RFC3489 transaction id (in bytes).
     */
    public static final byte RFC3489_TRANSACTION_ID_LENGTH = 16;

    /**
     * The list of attributes contained by the message. We are using a Map
     * rather than a uni-dimensional list, in order to facilitate attribute
     * search (even though it introduces some redundancies). Order is important
     * so we'll be using a <tt>LinkedHashMap</tt>
     */
    //not sure this is the best solution but I'm trying to keep entry order
    protected LinkedHashMap<Character, Attribute> attributes
                                = new LinkedHashMap<Character, Attribute>();

    /**
     * Attribute presentity is a thing of RFC 3489 and no longer exists in
     * 5389. we are not using it any longer and if at some point we decide we
     * need it in certain situations, then make extend use of the following
     * field.
     */
    private static boolean rfc3489CompatibilityMode = false;

    /**
     * Describes which attributes are present in which messages.  An
     * M indicates that inclusion of the attribute in the message is
     * mandatory, O means its optional, C means it's conditional based on
     * some other aspect of the message, and N/A means that the attribute is
     * not applicable to that message type.
     *
     * For classic STUN :
     *
     *
     *                                         Binding  Shared  Shared  Shared        <br/>
     *                       Binding  Binding  Error    Secret  Secret  Secret        <br/>
     *   Att.                Req.     Resp.    Resp.    Req.    Resp.   Error         <br/>
     *                                                                  Resp.         <br/>
     *   _____________________________________________________________________        <br/>
     *   MAPPED-ADDRESS      N/A      M        N/A      N/A     N/A     N/A           <br/>
     *   RESPONSE-ADDRESS    O        N/A      N/A      N/A     N/A     N/A           <br/>
     *   CHANGE-REQUEST      O        N/A      N/A      N/A     N/A     N/A           <br/>
     *   SOURCE-ADDRESS      N/A      M        N/A      N/A     N/A     N/A           <br/>
     *   CHANGED-ADDRESS     N/A      M        N/A      N/A     N/A     N/A           <br/>
     *   USERNAME            O        N/A      N/A      N/A     M       N/A           <br/>
     *   PASSWORD            N/A      N/A      N/A      N/A     M       N/A           <br/>
     *   MESSAGE-INTEGRITY   O        O        N/A      N/A     N/A     N/A           <br/>
     *   ERROR-CODE          N/A      N/A      M        N/A     N/A     M             <br/>
     *   UNKNOWN-ATTRIBUTES  N/A      N/A      C        N/A     N/A     C             <br/>
     *   REFLECTED-FROM      N/A      C        N/A      N/A     N/A     N/A           <br/>
     *   XOR-MAPPED-ADDRESS  N/A      M        N/A      N/A     N/A     N/A           <br/>
     *   XOR-ONLY            O        N/A      N/A      N/A     N/A     N/A           <br/>
     *   SOFTWARE            N/A      O        O        N/A     O       O             <br/>
     *
     */
    public static final byte N_A = 0;

    /**
     * C means it's conditional based on some other aspect of the message.
     */
    public static final byte C   = 1;

    /**
     * O means the parameter is optional.
     *
     * @see Message#N_A
     */
    public static final byte O   = 2;

    /**
     * M indicates that inclusion of the attribute in the message is
     * mandatory.
     *
     * @see Message#N_A
     */
    public static final byte M   = 3;

    //Message indices
    protected static final byte BINDING_REQUEST_PRESENTITY_INDEX           = 0;
    protected static final byte BINDING_RESPONSE_PRESENTITY_INDEX          = 1;
    protected static final byte BINDING_ERROR_RESPONSE_PRESENTITY_INDEX    = 2;
    protected static final byte SHARED_SECRET_REQUEST_PRESENTITY_INDEX     = 3;
    protected static final byte SHARED_SECRET_RESPONSE_PRESENTITY_INDEX    = 4;
    protected static final byte SHARED_SECRET_ERROR_RESPONSE_PRESENTITY_INDEX =
        5;
    protected static final byte ALLOCATE_REQUEST_PRESENTITY_INDEX          = 6;
    protected static final byte ALLOCATE_RESPONSE_PRESENTITY_INDEX         = 7;
    protected static final byte REFRESH_REQUEST_PRESENTITY_INDEX           = 8;
    protected static final byte REFRESH_RESPONSE_PRESENTITY_INDEX          = 9;
    protected static final byte CHANNELBIND_REQUEST_PRESENTITY_INDEX       = 10;
    protected static final byte CHANNELBIND_RESPONSE_PRESENTITY_INDEX      = 11;
    protected static final byte SEND_INDICATION_PRESENTITY_INDEX           = 12;
    protected static final byte DATA_INDICATION_PRESENTITY_INDEX           = 13;

    //Attribute indices
    protected static final byte MAPPED_ADDRESS_PRESENTITY_INDEX            =  0;
    protected static final byte RESPONSE_ADDRESS_PRESENTITY_INDEX          =  1;
    protected static final byte CHANGE_REQUEST_PRESENTITY_INDEX            =  2;
    protected static final byte SOURCE_ADDRESS_PRESENTITY_INDEX            =  3;
    protected static final byte CHANGED_ADDRESS_PRESENTITY_INDEX           =  4;
    protected static final byte USERNAME_PRESENTITY_INDEX                  =  5;
    protected static final byte PASSWORD_PRESENTITY_INDEX                  =  6;
    protected static final byte MESSAGE_INTEGRITY_PRESENTITY_INDEX         =  7;
    protected static final byte ERROR_CODE_PRESENTITY_INDEX                =  8;
    protected static final byte UNKNOWN_ATTRIBUTES_PRESENTITY_INDEX        =  9;
    protected static final byte REFLECTED_FROM_PRESENTITY_INDEX            = 10;
    protected static final byte XOR_MAPPED_ADDRESS_PRESENTITY_INDEX        = 11;
    protected static final byte XOR_ONLY_PRESENTITY_INDEX                  = 12;
    protected static final byte SOFTWARE_PRESENTITY_INDEX                  = 13;
    protected static final byte UNKNOWN_OPTIONAL_ATTRIBUTES_PRESENTITY_INDEX  =
        14;
    protected static final byte ALTERNATE_SERVER_PRESENTITY_INDEX          = 15;
    protected static final byte REALM_PRESENTITY_INDEX                     = 16;
    protected static final byte NONCE_PRESENTITY_INDEX                     = 17;
    protected static final byte FINGERPRINT_PRESENTITY_INDEX               = 18;

    /* TURN attributes */
    protected static final byte CHANNEL_NUMBER_PRESENTITY_INDEX            = 19;
    protected static final byte LIFETIME_PRESENTITY_INDEX                  = 20;
    protected static final byte XOR_PEER_ADDRESS_PRESENTITY_INDEX          = 21;
    protected static final byte DATA_PRESENTITY_INDEX                      = 22;
    protected static final byte XOR_RELAYED_ADDRESS_PRESENTITY_INDEX       = 23;
    protected static final byte EVEN_PORT_PRESENTITY_INDEX                 = 24;
    protected static final byte REQUESTED_TRANSPORT_PRESENTITY_INDEX       = 25;
    protected static final byte DONT_FRAGMENT_PRESENTITY_INDEX             = 26;
    protected static final byte RESERVATION_TOKEN_PRESENTITY_INDEX         = 27;

    /* ICE attributes */
    protected static final byte PRIORITY_PRESENTITY_INDEX                  = 28;
    protected static final byte ICE_CONTROLLING_PRESENTITY_INDEX           = 29;
    protected static final byte ICE_CONTROLLED_PRESENTITY_INDEX            = 30;
    protected static final byte USE_CANDIDATE_PRESENTITY_INDEX             = 31;

    /* Old TURN attributes */
    protected static final byte DESTINATION_ADDRESS_PRESENTITY_INDEX       = 29;

    protected final static byte attributePresentities[][] = new byte[][]{
    //                                            Binding   Shared   Shared   Shared  Alloc   Alloc   Rfrsh   Rfrsh   ChnlBnd  ChnlBnd Send    Data
    //                        Binding   Binding   Error     Secret   Secret   Secret  Req.    Resp.   Req.    Resp.   Req.     Resp.   Indic.  Indic.
    //  Att.                  Req.      Resp.     Resp.     Req.     Resp.    Error
    //                                                                        Resp.
    //  ____________________________________________________________________________________________________________________________________________
      /*MAPPED-ADDRESS*/    { N_A,      M,        N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*RESPONSE-ADDRESS*/  { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*CHANGE-REQUEST*/    { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*SOURCE-ADDRESS*/    { N_A,      M,        N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   M},
      /*CHANGED-ADDRESS*/   { N_A,      M,        N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*USERNAME*/          { O,        N_A,      N_A,      N_A,     M,       N_A,    O,      N_A,    O,      N_A,    O,       N_A,    N_A,   N_A},
      /*PASSWORD*/          { N_A,      N_A,      N_A,      N_A,     M,       N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*MESSAGE-INTEGRITY*/ { O,        O,        N_A,      N_A,     N_A,     N_A,    O,      O,      O,      O,      O,       O,      N_A,   N_A},
      /*ERROR-CODE*/        { N_A,      N_A,      M,        N_A,     N_A,     M,      N_A,    M,      N_A,    M,      N_A,     M,      N_A,   N_A},
      /*UNKNOWN-ATTRIBUTES*/{ N_A,      N_A,      C,        N_A,     N_A,     C,      N_A,    C,      N_A,    C,      N_A,     C,      N_A,   N_A},
      /*REFLECTED-FROM*/    { N_A,      C,        N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*XOR-MAPPED-ADDRESS*/{ N_A,      C,        N_A,      N_A,     N_A,     N_A,    N_A,    M,      N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*XOR-ONLY*/          { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*SOFTWARE*/          { N_A,      O,        O,        N_A,     O,       O,      O,      O,      O,      O,      O,       O,      O,     N_A},
      /*UNKNOWN_OPTIONAL*/  { O,        O,        O,        O,       O,       O,      O,      O,      O,      O,      O,       O,      N_A,   N_A},
      /*ALTERNATE_SERVER*/  { O,        O,        O,        O,       O,       O,      N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*REALM*/             { O,        N_A,      N_A,      N_A,     M,       N_A,    O,      O,      O,      O,      O,       O,      N_A,   N_A},
      /*NONCE*/             { O,        N_A,      N_A,      N_A,     M,       N_A,    O,      O,      O,      O,      O,       O,      N_A,   N_A},
      /*FINGERPRINT*/       { O,        O,        O,        O,       O,       O,      O,      O,      O,      O,      O,       O,      N_A,   N_A},
      /*CHANNEL-NUMBER*/    { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    M,       N_A,    N_A,   N_A},
      /*LIFETIME*/          { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    O,      N_A,    O,      N_A,    N_A,     N_A,    N_A,   N_A},
      /*XOR-PEER-ADDRESS*/  { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    M,       N_A,    M,     M},
      /*DATA*/              { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    O,      N_A,    N_A,     N_A,    O,     M},
      /*XOR-RELAYED-ADDRESS*/{N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    M,      N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*EVEN-PORT*/         { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    O,      N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*REQUESTED-TRANSPORT*/{N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    M,      N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*DONT-FRAGMENT*/     { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    O,      N_A,    N_A,    N_A,    N_A,     N_A,    O,     N_A},
      /*RESERVATION-TOKEN*/ { N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    O,      O,      N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*PRIORITY*/          { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*ICE-CONTROLLING*/   { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*ICE-CONTROLLED*/    { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*USE-CANDIDATE*/     { O,        N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    N_A,    N_A,    N_A,     N_A,    N_A,   N_A},
      /*DESTINATION-ADDRESS*/{N_A,      N_A,      N_A,      N_A,     N_A,     N_A,    N_A,    N_A,    O,      N_A,    N_A,     N_A,    M,     N_A},
    };

    /**
     * Creates an empty STUN Message.
     */
    protected Message()
    {
    }

    /**
     * Returns the length of this message's body.
     * @return the length of the data in this message.
     */
    public char getDataLength()
    {
        char length = 0;

        List<Attribute> attrs = getAttributes();
        for (Attribute att : attrs)
        {
            int attLen = att.getDataLength() + Attribute.HEADER_LENGTH;

            //take attribute padding into account:
            attLen += (4 - (attLen % 4)) % 4;

            length += attLen;
        }
        return length;
    }

    /**
     * Returns the length of this message's body without padding.
     * Some STUN/ICE dialect does not take into account padding (GTalk).
     *
     * @return the length of the data in this message.
     */
    public char getDataLengthWithoutPadding()
    {
        char length = 0;

        List<Attribute> attrs = getAttributes();

        for (Attribute att : attrs)
        {
            int attLen = att.getDataLength() + Attribute.HEADER_LENGTH;
            length += attLen;
        }
        return length;
    }

    /**
     * Puts the specified attribute into this message. If an attribute with that
     * name was already added, it would be replaced.
     *
     * @param attribute the attribute to put into this message.
     *
     * @throws IllegalArgumentException if the message cannot contain
     * such an attribute.
     */
    public void putAttribute(Attribute attribute)
        throws IllegalArgumentException
    {
        if (getAttributePresentity(attribute.getAttributeType()) == N_A)
        {
            throw new IllegalArgumentException(
                                    "The attribute "
                                    + attribute.getName()
                                    + " is not allowed in a "
                                    + getName());
        }

        synchronized(attributes)
        {
            attributes.put(attribute.getAttributeType(), attribute);
        }
    }

    /**
     * Returns <tt>true</tt> if the this <tt>Message</tt> contains an attribute
     * with the specified type or <tt>false</tt> otherwise.
     *
     * @param attributeType the type whose presence we need to determine.
     *
     * @return <tt>true</tt> if the this <tt>Message</tt> contains an attribute
     * with the specified type or <tt>false</tt> otherwise.
     */
    public boolean containsAttribute(char attributeType)
    {
        return attributes.containsKey(attributeType);
    }

    /**
     * Returns the attribute with the specified type or null if no such
     * attribute exists.
     *
     * @param attributeType the type of the attribute
     * @return the attribute with the specified type or null if no such
     * attribute exists
     */
    public Attribute getAttribute(char attributeType)
    {
        synchronized(attributes)
        {
            return attributes.get(attributeType);
        }
    }

    /**
     * Returns a copy of all {@link Attribute}s in this {@link Message}.
     *
     * @return a copy of all {@link Attribute}s in this {@link Message}.
     */
    public List<Attribute> getAttributes()
    {
        synchronized(attributes)
        {
            return new LinkedList<Attribute>(attributes.values());
        }
    }

    /**
     * Removes the specified attribute.
     *
     * @param attributeType the attribute to remove.
     *
     * @return the <tt>Attribute</tt> we've just removed.
     */
    public Attribute removeAttribute(char attributeType)
    {
        synchronized(attributes)
        {
            return attributes.remove(attributeType);
        }
    }

    /**
     * Returns the number of attributes, currently contained by the message.
     *
     * @return the number of attributes, currently contained by the message.
     */
    public int getAttributeCount()
    {
        return  attributes.size();
    }

    /**
     * Sets this message's type to be messageType. Method is package access
     * as it should not permit changing the type of message once it has been
     * initialized (could provoke attribute discrepancies). Called by
     * messageFactory.
     *
     * @param messageType the message type.
     */
    protected void setMessageType(char messageType)
    {
        this.messageType = messageType;
    }

    /**
     * The message type of this message.
     *
     * @return the message type of the message.
     */
    public char getMessageType()
    {
        return messageType;
    }

    /**
     * Copies the specified tranID and sets it as this message's transactionID.
     *
     * @param tranID the transaction id to set in this message.
     *
     * @throws StunException ILLEGAL_ARGUMENT if the transaction id is not
     * valid.
     */
    public void setTransactionID(byte[] tranID)
        throws StunException
    {
        if(tranID == null
           || (tranID.length != TRANSACTION_ID_LENGTH &&
                   tranID.length != RFC3489_TRANSACTION_ID_LENGTH))
            throw new StunException(StunException.ILLEGAL_ARGUMENT,
                                    "Invalid transaction id length");

        int tranIDLength = tranID.length;

        this.transactionID = new byte[tranIDLength];
        System.arraycopy(tranID, 0,
                         this.transactionID, 0, tranIDLength);
    }

    /**
     * Returns a reference to this message's transaction id.
     *
     * @return a reference to this message's transaction id.
     */
    public byte[] getTransactionID()
    {
        return this.transactionID;
    }

    /**
     * Returns whether an attribute could be present in this message.
     *
     * @param attributeType the id of the attribute to check .
     *
     * @return Message.N_A - for not applicable <br/>
     *         Message.C   - for case depending <br/>
     *         Message.N_A - for not applicable <br/>
     */
    protected byte getAttributePresentity(char attributeType)
    {
        if(!rfc3489CompatibilityMode)
            return O;

        byte msgIndex = -1;
        byte attributeIndex = -1;

        switch (messageType)
        {
            case BINDING_REQUEST:
                msgIndex = BINDING_REQUEST_PRESENTITY_INDEX; break;
            case BINDING_SUCCESS_RESPONSE:
                msgIndex = BINDING_RESPONSE_PRESENTITY_INDEX; break;
            case BINDING_ERROR_RESPONSE:
                msgIndex = BINDING_ERROR_RESPONSE_PRESENTITY_INDEX; break;
            case SHARED_SECRET_REQUEST:
                msgIndex = SHARED_SECRET_REQUEST_PRESENTITY_INDEX; break;
            case SHARED_SECRET_RESPONSE:
                msgIndex = SHARED_SECRET_RESPONSE_PRESENTITY_INDEX; break;
            case SHARED_SECRET_ERROR_RESPONSE:
                msgIndex = SHARED_SECRET_ERROR_RESPONSE_PRESENTITY_INDEX; break;
            case ALLOCATE_REQUEST:
                msgIndex = ALLOCATE_REQUEST_PRESENTITY_INDEX; break;
            case REFRESH_REQUEST:
                msgIndex = REFRESH_REQUEST_PRESENTITY_INDEX; break;
            case CHANNELBIND_REQUEST:
                msgIndex = CHANNELBIND_REQUEST_PRESENTITY_INDEX; break;
            case SEND_INDICATION:
                msgIndex = SEND_INDICATION_PRESENTITY_INDEX; break;
            case DATA_INDICATION:
                msgIndex = DATA_INDICATION_PRESENTITY_INDEX; break;
            default:
                if (logger.isLoggable(Level.FINE))
                {
                    logger.log(
                            Level.FINE,
                            "Attribute presentity not defined for STUN " +
                            "message type: " + ((int) messageType)
                                + ". Will assume optional.");
                }
                return O;
        }

        switch (attributeType)
        {
            case Attribute.MAPPED_ADDRESS:
                attributeIndex = MAPPED_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.RESPONSE_ADDRESS:
                attributeIndex = RESPONSE_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.CHANGE_REQUEST:
                attributeIndex = CHANGE_REQUEST_PRESENTITY_INDEX; break;
            case Attribute.SOURCE_ADDRESS:
                attributeIndex = SOURCE_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.CHANGED_ADDRESS:
                attributeIndex = CHANGED_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.USERNAME:
                attributeIndex = USERNAME_PRESENTITY_INDEX; break;
            case Attribute.PASSWORD:
                attributeIndex = PASSWORD_PRESENTITY_INDEX; break;
            case Attribute.MESSAGE_INTEGRITY:
                attributeIndex = MESSAGE_INTEGRITY_PRESENTITY_INDEX; break;
            case Attribute.ERROR_CODE:
                attributeIndex = ERROR_CODE_PRESENTITY_INDEX; break;
            case Attribute.UNKNOWN_ATTRIBUTES:
                attributeIndex = UNKNOWN_ATTRIBUTES_PRESENTITY_INDEX; break;
            case Attribute.REFLECTED_FROM:
                attributeIndex = REFLECTED_FROM_PRESENTITY_INDEX; break;
            case Attribute.XOR_MAPPED_ADDRESS:
                attributeIndex = XOR_MAPPED_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.XOR_ONLY:
                attributeIndex = XOR_ONLY_PRESENTITY_INDEX; break;
            case Attribute.SOFTWARE:
                attributeIndex = SOFTWARE_PRESENTITY_INDEX; break;
            case Attribute.ALTERNATE_SERVER:
                attributeIndex = ALTERNATE_SERVER_PRESENTITY_INDEX; break;
            case Attribute.REALM:
                attributeIndex = REALM_PRESENTITY_INDEX; break;
            case Attribute.NONCE:
                attributeIndex = NONCE_PRESENTITY_INDEX; break;
            case Attribute.FINGERPRINT:
                attributeIndex = FINGERPRINT_PRESENTITY_INDEX; break;
            case Attribute.CHANNEL_NUMBER:
                attributeIndex = CHANNEL_NUMBER_PRESENTITY_INDEX; break;
            case Attribute.LIFETIME:
                attributeIndex = LIFETIME_PRESENTITY_INDEX; break;
            case Attribute.XOR_PEER_ADDRESS:
                attributeIndex = XOR_PEER_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.DATA:
                attributeIndex = DATA_PRESENTITY_INDEX; break;
            case Attribute.XOR_RELAYED_ADDRESS:
                attributeIndex = XOR_RELAYED_ADDRESS_PRESENTITY_INDEX; break;
            case Attribute.EVEN_PORT:
                attributeIndex = EVEN_PORT_PRESENTITY_INDEX; break;
            case Attribute.REQUESTED_TRANSPORT:
                attributeIndex = REQUESTED_TRANSPORT_PRESENTITY_INDEX; break;
            case Attribute.DONT_FRAGMENT:
                attributeIndex = DONT_FRAGMENT_PRESENTITY_INDEX; break;
            case Attribute.RESERVATION_TOKEN:
                attributeIndex = RESERVATION_TOKEN_PRESENTITY_INDEX; break;
            default:
                attributeIndex = UNKNOWN_OPTIONAL_ATTRIBUTES_PRESENTITY_INDEX;
                break;
        }

        return attributePresentities[ attributeIndex ][ msgIndex ];
    }

    /**
     * Returns the human readable name of this message. Message names do
     * not really matter from the protocol point of view. They are only used
     * for debugging and readability.
     *
     * @return this message's name.
     */
    public String getName()
    {
        switch (messageType)
        {
        case ALLOCATE_REQUEST:             return "ALLOCATE-REQUEST";
        case ALLOCATE_RESPONSE:            return "ALLOCATE-RESPONSE";
        case ALLOCATE_ERROR_RESPONSE:      return "ALLOCATE-ERROR-RESPONSE";
        case BINDING_REQUEST:              return "BINDING-REQUEST";
        case BINDING_SUCCESS_RESPONSE:     return "BINDING-RESPONSE";
        case BINDING_ERROR_RESPONSE:       return "BINDING-ERROR-RESPONSE";
        case CREATEPERMISSION_REQUEST:     return "CREATE-PERMISSION-REQUEST";
        case CREATEPERMISSION_RESPONSE:    return "CREATE-PERMISSION-RESPONSE";
        case CREATEPERMISSION_ERROR_RESPONSE:
            return "CREATE-PERMISSION-ERROR-RESPONSE";
        case DATA_INDICATION:              return "DATA-INDICATION";
        case REFRESH_REQUEST:              return "REFRESH-REQUEST";
        case REFRESH_RESPONSE:             return "REFRESH-RESPONSE";
        case REFRESH_ERROR_RESPONSE:       return "REFRESH-ERROR-RESPONSE";
        case SEND_INDICATION:              return "SEND-INDICATION";
        case SHARED_SECRET_REQUEST:        return "SHARED-SECRET-REQUEST";
        case SHARED_SECRET_RESPONSE:       return "SHARED-SECRET-RESPONSE";
        case SHARED_SECRET_ERROR_RESPONSE:
            return "SHARED-SECRET-ERROR-RESPONSE";
        default:                           return "UNKNOWN-MESSAGE";
        }
    }

    /**
     * Compares two STUN Messages. Messages are considered equal when their
     * type, length, and all their attributes are equal.
     *
     * @param obj the object to compare this message with.
     *
     * @return true if the messages are equal and false otherwise.
     */
    @Override
    public boolean equals(Object obj)
    {
        if(!(obj instanceof Message)
           || obj == null)
            return false;

        if(obj == this)
            return true;

        Message msg = (Message) obj;
        if( msg.getMessageType()   != getMessageType())
            return false;
        if(msg.getDataLength() != getDataLength())
            return false;

        //compare attributes
        for (Attribute localAtt : attributes.values())
        {
            if(!localAtt.equals(msg.getAttribute(localAtt.getAttributeType())))
                return false;
        }

        return true;
    }

    /**
     * Returns a binary representation of this message.
     *
     * @param stunStack the <tt>StunStack</tt> in the context of which the
     * request to encode this <tt>Message</tt> is being made
     * @return a binary representation of this message.
     *
     * @throws IllegalStateException if the message does not have all
     * required attributes.
     */
    public byte[] encode(StunStack stunStack)
        throws IllegalStateException
    {
        prepareForEncoding();

        //make sure we have everything necessary to encode a proper message
        validateAttributePresentity();

        final char dataLength;

        dataLength = getDataLength();

        byte binMsg[] = new byte[HEADER_LENGTH + dataLength];
        int offset    = 0;

        // STUN Message Type
        binMsg[offset++] = (byte)(getMessageType() >> 8);
        binMsg[offset++] = (byte)(getMessageType() & 0xFF);

        // Message Length
        final int messageLengthOffset = offset;

        offset += 2;

        byte tranID[] = getTransactionID();

        if(tranID.length == 12)
        {
            System.arraycopy(MAGIC_COOKIE, 0, binMsg, offset, 4);
            offset += 4;
            System.arraycopy(tranID, 0, binMsg, offset, TRANSACTION_ID_LENGTH);
            offset += TRANSACTION_ID_LENGTH;
        }
        else
        {
            /* RFC3489 behavior */
            System.arraycopy(tranID, 0, binMsg, offset,
                RFC3489_TRANSACTION_ID_LENGTH);
            offset += RFC3489_TRANSACTION_ID_LENGTH;
        }

        Vector<Map.Entry<Character, Attribute>> v =
            new Vector<Map.Entry<Character, Attribute>>();
        Iterator<Map.Entry<Character, Attribute>> iter = null;
        char dataLengthForContentDependentAttribute = 0;

        synchronized (attributes)
        {
            v.addAll(attributes.entrySet());
        }

        iter = v.iterator();

        while (iter.hasNext())
        {
            Attribute attribute = iter.next().getValue();
            int attributeLength
                = attribute.getDataLength() + Attribute.HEADER_LENGTH;

            //take attribute padding into account:
            attributeLength += (4 - attributeLength % 4) % 4;
            dataLengthForContentDependentAttribute += attributeLength;

            //special handling for message integrity and fingerprint values
            byte[] binAtt;

            if (attribute instanceof ContentDependentAttribute)
            {
                /*
                 * The "Message Length" seen by a ContentDependentAttribute is
                 * up to and including the very Attribute but without any other
                 * Attribute instances after it.
                 */
                binMsg[messageLengthOffset]
                    = (byte)(dataLengthForContentDependentAttribute >> 8);
                binMsg[messageLengthOffset + 1]
                    = (byte)(dataLengthForContentDependentAttribute & 0xFF);
                binAtt
                    = ((ContentDependentAttribute)attribute)
                            .encode(stunStack, binMsg, 0, offset);
            }
            else
            {
                binAtt = attribute.encode();
            }

            System.arraycopy(binAtt, 0, binMsg, offset, binAtt.length);
            /*
             * Offset by attributeLength and not by binAtt.length because
             * attributeLength takes the attribute padding into account and
             * binAtt.length does not.
             */
            offset += attributeLength;
        }

        // Message Length
        binMsg[messageLengthOffset]     = (byte)(dataLength >> 8);
        binMsg[messageLengthOffset + 1] = (byte)(dataLength & 0xFF);

        return binMsg;
    }

    /**
     * Adds attributes that have been requested vis configuration properties.
     * Asserts attribute order where necessary.
     */
    private void prepareForEncoding()
    {
        //remove MESSAGE-INTEGRITY and FINGERPRINT attributes so that we can
        //make sure they are added at the end.
        Attribute msgIntAttr = removeAttribute(Attribute.MESSAGE_INTEGRITY);
        Attribute fingerprint = removeAttribute(Attribute.FINGERPRINT);

        //add a SOFTWARE attribute if the user said so, and unless they did it
        //themselves.
        String software = System.getProperty(StackProperties.SOFTWARE);

        if (getAttribute(Attribute.SOFTWARE) == null
            && software != null && software.length() > 0)
        {
            putAttribute(AttributeFactory
                            .createSoftwareAttribute(software.getBytes()));
        }

        //re-add MESSAGE-INTEGRITY if there was one.
        if (msgIntAttr != null)
        {
            putAttribute(msgIntAttr);
        }

        //add FINGERPRINT if there was one or if user told us to add it
        //everywhere.
        if (fingerprint == null
            && Boolean.getBoolean(StackProperties.ALWAYS_SIGN))
        {
            fingerprint = AttributeFactory.createFingerprintAttribute();
        }

        if (fingerprint != null)
        {
            putAttribute(fingerprint);
        }
    }

    /**
     * Constructs a message from its binary representation.
     * @param binMessage the binary array that contains the encoded message
     * @param offset the index where the message starts.
     * @param arrayLen the length of the message
     * @return a Message object constructed from the binMessage array
     *
     * @throws StunException <tt>ILLEGAL_ARGUMENT</tt> if one or more of the
     * arguments have invalid values.
     */
    public static Message decode(byte binMessage[], char offset, char arrayLen)
        throws StunException
    {
        int originalOffset = offset;
        arrayLen = (char)Math.min(binMessage.length, arrayLen);

        if(binMessage == null || arrayLen - offset < Message.HEADER_LENGTH)
        {
            throw new StunException( StunException.ILLEGAL_ARGUMENT,
                         "The given binary array is not a valid StunMessage");
        }

        char messageType = (char)((binMessage[offset++] << 8)
                               | (binMessage[offset++] & 0xFF));

        Message message;
        /* 0x0115 is a old TURN DATA indication message type */
        if (Message.isResponseType(messageType) &&
                messageType != OLD_DATA_INDICATION)
            message = new Response();
        else if(Message.isRequestType(messageType))
            message = new Request();
        else /* indication */
            message = new Indication();

        message.setMessageType(messageType);

        int length = (char)((binMessage[offset++] << 8)
                          | (binMessage[offset++]  & 0xFF));

        /* copy the cookie */
        byte cookie[] = new byte[4];
        System.arraycopy(binMessage, offset, cookie, 0, 4);
        offset += 4;

        boolean rfc3489Compat = false;

        if(!Arrays.equals(MAGIC_COOKIE, cookie))
        {
            rfc3489Compat = true;
        }

        if(arrayLen - offset - TRANSACTION_ID_LENGTH < length)
        {
            throw
                new StunException(
                        StunException.ILLEGAL_ARGUMENT,
                        "The given binary array does not seem to contain"
                            + " a whole StunMessage: given "
                            + ((int) arrayLen)
                            + " bytes of "
                            + message.getName()
                            + " but expecting "
                            + (offset + TRANSACTION_ID_LENGTH + length));
        }

        byte tranID[] = new byte[TRANSACTION_ID_LENGTH];
        System.arraycopy(binMessage, offset, tranID, 0, TRANSACTION_ID_LENGTH);
        try
        {
            if(rfc3489Compat)
            {
                byte rfc3489TranID[] = new byte[TRANSACTION_ID_LENGTH + 4];
                System.arraycopy(cookie, 0, rfc3489TranID, 0, 4);
                System.arraycopy(tranID, 0, rfc3489TranID, 4,
                        TRANSACTION_ID_LENGTH);
                message.setTransactionID(rfc3489TranID);
            }
            else
            {
                message.setTransactionID(tranID);
            }
        }
        catch (StunException exc)
        {
            throw new StunException( StunException.ILLEGAL_ARGUMENT,
                            "The given binary array does not seem to "
                            + "contain a whole StunMessage", exc);
        }

        offset += TRANSACTION_ID_LENGTH;

        while(offset - Message.HEADER_LENGTH < length)
        {
            Attribute att = AttributeDecoder.decode(
                binMessage, offset, (char)(length - offset));

            performAttributeSpecificActions(att, binMessage,
                originalOffset, offset);

            message.putAttribute(att);
            offset += att.getDataLength() + Attribute.HEADER_LENGTH;

            //now also skip any potential padding that might have come with
            //this attribute.
            if((att.getDataLength() % 4) > 0)
            {
                offset += (4 - (att.getDataLength() % 4));
            }
        }

        return message;
    }

    /**
     * Executes actions related specific attributes like asserting proper
     * fingerprint checksum.
     *
     * @param attribute the <tt>Attribute</tt> we'd like to process.
     * @param binMessage the byte array that the message arrived with.
     * @param offset the index where data starts in <tt>binMessage</tt>.
     * @param msgLen the number of message bytes in <tt>binMessage</tt>.
     *
     * @throws StunException if there's something in the <tt>attribute</tt> that
     * caused us to discard the whole message (e.g. an invalid checksum or
     * username)
     */
    private static void performAttributeSpecificActions(Attribute attribute,
                                                        byte[]    binMessage,
                                                        int       offset,
                                                        int       msgLen)
        throws StunException
    {
        //check finger print CRC
        if (attribute instanceof FingerprintAttribute)
        {
            if (!validateFingerprint((FingerprintAttribute)attribute,
                            binMessage, offset, msgLen))
            {
                //RFC 5389 says that we should ignore bad CRCs rather than
                //reply with an error response.
                throw new StunException("Wrong value in FINGERPRINT");
            }
        }
    }

    /**
     * Recalculates the FINGERPRINT CRC32 checksum of the <tt>message</tt>
     * array so that we could compare it with the value brought by the
     * {@link FingerprintAttribute}.
     *
     * @param fingerprint the attribute that we need to validate.
     * @param message the message whose CRC32 checksum we'd need to recalculate.
     * @param offset the index in <tt>message</tt> where data starts.
     * @param length the number of bytes in <tt>message</tt> that the CRC32
     * would need to be calculated over.
     *
     * @return <tt>true</tt> if <tt>FINGERPRINT</tt> contains a valid CRC32
     * value and <tt>false</tt> otherwise.
     */
    private static boolean validateFingerprint(FingerprintAttribute fingerprint,
                                               byte[]               message,
                                               int                  offset,
                                               int                  length)
    {

        byte[] incomingCrcBytes = fingerprint.getChecksum();

        //now check whether the CRC really is what it's supposed to be.
        //re calculate the check sum
        byte[] realCrcBytes = FingerprintAttribute.calculateXorCRC32(
                        message, offset, length);

        //CRC validation.
        if ( ! Arrays.equals(incomingCrcBytes, realCrcBytes))
        {
            if (logger.isLoggable(Level.FINE))
            {
                logger.fine(
                        "An incoming message arrived with a wrong FINGERPRINT "
                        +"attribute value. "
                        +"CRC Was:"  + Arrays.toString(incomingCrcBytes)
                        + ". Should have been:" + Arrays.toString(realCrcBytes)
                        +". Will ignore.");
            }

            return false;
        }

        return true;
    }

    /**
     * Verify that the message has all obligatory attributes and throw an
     * exception if this is not the case.
     *
     * @throws IllegalStateException if the message does not have all
     * required attributes.
     */
    protected void validateAttributePresentity()
        throws IllegalStateException
    {
        if(! rfc3489CompatibilityMode )
            return;

        for(char i = Attribute.MAPPED_ADDRESS; i < Attribute.REFLECTED_FROM;i++)
            if(getAttributePresentity(i) == M && getAttribute(i) == null)
                throw new IllegalStateException(
                    "A mandatory attribute (type=" + (int)i + ") is missing!");
    }

    /**
     * Determines if the message type is a Error Response.
     * @param type type to test
     * @return true if the type is Error Response, false otherwise
     */
    public static boolean isErrorResponseType(char type)
    {
      return ((type & 0x0110) == STUN_ERROR_RESP);
    }

    /**
     * Determines if the message type is a Success Response.
     * @param type type to test
     * @return true if the type is Success Response, false otherwise
     */
    public static boolean isSuccessResponseType(char type)
    {
      return ((type & 0x0110) == STUN_SUCCESS_RESP);
    }

    /**
     * Determines whether type could be the type of a STUN Response (as opposed
     * to STUN Request).
     * @param type the type to test.
     * @return true if type is a valid response type.
     */
    public static boolean isResponseType(char type)
    {
      /* return (((type >> 8) & 1) != 0); */
      return (isSuccessResponseType(type) || isErrorResponseType(type));
    }

    /**
     * Determines if the message type is Indication.
     * @param type type to test
     * @return true if the type is Indictation, false otherwise
     */
    public static boolean isIndicationType(char type)
    {
      return ((type & 0x0110) == STUN_INDICATION);
    }

    /**
     * Determines whether type could be the type of a STUN Request (as opposed
     * to STUN Response).
     * @param type the type to test.
     * @return true if type is a valid request type.
     */
    public static boolean isRequestType(char type)
    {
      /* return !isResponseType(type); */
      return ((type & 0x0110) == STUN_REQUEST);
    }

    /**
     * Returns a <tt>String</tt> representation of this message.
     *
     * @return  a <tt>String</tt> representation of this message.
     */
    @Override
    public String toString()
    {
        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append(getName());
        stringBuilder.append("(0x");
        stringBuilder.append(Integer.toHexString(getMessageType()));
        stringBuilder.append(")[attrib.count=");
        stringBuilder.append(getAttributeCount());
        stringBuilder.append(" len=");
        stringBuilder.append((int) this.getDataLength());

        byte[] transactionID = getTransactionID();

        if (transactionID != null)
        {
            stringBuilder.append(" tranID=");
            stringBuilder.append(TransactionID.toString(transactionID));
        }
        stringBuilder.append("]");
        return stringBuilder.toString();
    }
}
