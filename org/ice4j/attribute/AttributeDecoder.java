/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.attribute;

import org.ice4j.*;

/**
 * The class provides utilities for decoding a binary stream into an Attribute
 * class.
 *
 * @author Emil Ivov
 * @author Sebastien Vincent
 * @author Aakash Garg
 */
public class AttributeDecoder
{
    /**
     * Decodes the specified binary array and returns the corresponding
     * attribute object.
     *
     * @param bytes the binary array that should be decoded.
     * @param offset the index where the message starts.
     * @param length the number of bytes that the message is long.
     *
     * @return An object representing the attribute encoded in bytes or null if
     * the attribute was not recognized.
     *
     * @throws StunException if bytes is not a valid STUN attribute.
     */
    public static Attribute decode(byte[] bytes,
                                   char   offset,
                                   char   length)
        throws StunException
    {
        if(bytes == null || bytes.length < Attribute.HEADER_LENGTH)
        {
            throw new StunException( StunException.ILLEGAL_ARGUMENT,
                         "Could not decode the specified binary array.");
        }

        //Discover attribute type
        char attributeType   = (char)((bytes[offset] << 8) | bytes[offset + 1]);
        int len1 = bytes[offset + 2] & 0xff;
        int len2 = bytes[offset + 3] & 0xff;
        char attributeLength = (char)((len1 << 8) | len2);

        if(attributeLength > bytes.length - offset )
            throw new StunException( StunException.ILLEGAL_ARGUMENT,
                            "Could not decode the specified binary array.");

        Attribute decodedAttribute = null;

        switch(attributeType)
        {
            /* STUN attributes */
            case Attribute.CHANGE_REQUEST:
                decodedAttribute = new ChangeRequestAttribute(); break;
            case Attribute.CHANGED_ADDRESS:
                decodedAttribute = new ChangedAddressAttribute(); break;
            case Attribute.MAPPED_ADDRESS:
                decodedAttribute = new MappedAddressAttribute(); break;
            case Attribute.ERROR_CODE:
                decodedAttribute = new ErrorCodeAttribute(); break;
            case Attribute.MESSAGE_INTEGRITY:
                decodedAttribute = new MessageIntegrityAttribute(); break;
            //case Attribute.PASSWORD: //handle as an unknown attribute
            case Attribute.REFLECTED_FROM:
                decodedAttribute = new ReflectedFromAttribute(); break;
            case Attribute.RESPONSE_ADDRESS:
                decodedAttribute = new ResponseAddressAttribute(); break;
            case Attribute.SOURCE_ADDRESS:
                decodedAttribute = new SourceAddressAttribute(); break;
            case Attribute.UNKNOWN_ATTRIBUTES:
                decodedAttribute = new UnknownAttributesAttribute(); break;
            case Attribute.XOR_MAPPED_ADDRESS:
                decodedAttribute = new XorMappedAddressAttribute(); break;
            case Attribute.XOR_ONLY:
                decodedAttribute = new XorOnlyAttribute(); break;
            case Attribute.SOFTWARE:
                decodedAttribute = new SoftwareAttribute(); break;
            case Attribute.USERNAME:
                decodedAttribute = new UsernameAttribute(); break;
            case Attribute.REALM:
                decodedAttribute = new RealmAttribute(); break;
            case Attribute.NONCE:
                decodedAttribute = new NonceAttribute(); break;
            case Attribute.FINGERPRINT:
                decodedAttribute = new FingerprintAttribute(); break;
            case Attribute.ALTERNATE_SERVER:
                decodedAttribute = new AlternateServerAttribute(); break;
            case Attribute.CHANNEL_NUMBER:
                decodedAttribute = new ChannelNumberAttribute(); break;
            case Attribute.LIFETIME:
                decodedAttribute = new LifetimeAttribute(); break;
            case Attribute.XOR_PEER_ADDRESS:
                decodedAttribute = new XorPeerAddressAttribute(); break;
            case Attribute.DATA:
                decodedAttribute = new DataAttribute(); break;
            case Attribute.XOR_RELAYED_ADDRESS:
                decodedAttribute = new XorRelayedAddressAttribute(); break;
            case Attribute.EVEN_PORT:
                decodedAttribute = new EvenPortAttribute(); break;
            case Attribute.REQUESTED_TRANSPORT:
                decodedAttribute = new RequestedTransportAttribute(); break;
            case Attribute.DONT_FRAGMENT:
                decodedAttribute = new DontFragmentAttribute(); break;
            case Attribute.RESERVATION_TOKEN:
                decodedAttribute = new ReservationTokenAttribute(); break;
            case Attribute.PRIORITY:
                decodedAttribute = new PriorityAttribute(); break;
            case Attribute.ICE_CONTROLLING:
                decodedAttribute = new IceControllingAttribute(); break;
            case Attribute.ICE_CONTROLLED:
                decodedAttribute = new IceControlledAttribute(); break;
            case Attribute.USE_CANDIDATE:
                decodedAttribute = new UseCandidateAttribute(); break;
            case Attribute.REQUESTED_ADDRESS_FAMILY:
        	    decodedAttribute = new RequestedAddressFamilyAttribute(); break;
            case Attribute.CONNECTION_ID:
        	    decodedAttribute = new ConnectionIdAttribute(); break;
            //According to rfc3489 we should silently ignore unknown attributes.
            default: decodedAttribute
                = new OptionalAttribute( Attribute.UNKNOWN_OPTIONAL_ATTRIBUTE);
                break;
        }

        decodedAttribute.setAttributeType(attributeType);
        decodedAttribute.setLocationInMessage(offset);

        decodedAttribute.decodeAttributeBody(bytes,
                (char)(Attribute.HEADER_LENGTH + offset), attributeLength);

        return decodedAttribute;
    }
}
