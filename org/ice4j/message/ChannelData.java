/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.message;

import org.ice4j.*;

/**
 * The ChannelData message are used in TURN protocol
 * after a client has bound a channel to a peer.
 *
 *    0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |         Channel Number        |            Length             |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                                                               |
 *  /                       Application Data                        /
 *  /                                                               /
 *  |                                                               |
 *  |                               +-------------------------------+
 *  |                               |
 *  +-------------------------------+
 *
 * @author Sebastien Vincent
 * @author Aakash Garg
 */
public class ChannelData
{
    /**
     * The channel number.
     */
    private char channelNumber = 0;

    /**
     * The data.
     */
    private byte data[] = null;

    /**
     * Size of the header.
     */
    public static char HEADER_LENGTH = 4;

    /**
     * Constructor.
     */
    public ChannelData()
    {
    }

    /**
     * Set the channel number
     * @param channelNumber the channel number
     */
    public void setChannelNumber(char channelNumber)
    {
        this.channelNumber = channelNumber;
    }

    /**
     * Get the channel number.
     * @return channel number
     */
    public char getChannelNumber()
    {
        return this.channelNumber;
    }

    /**
     * Set the data.
     * @param data the data
     */
    public void setData(byte data[])
    {
        this.data = data;
    }

    /**
     * Get the data.
     * @return data
     */
    public byte[] getData()
    {
        return this.data;
    }

    /**
     * Get the data length.
     *
     * @return data length
     */
    public char getDataLength()
    {
        char length = 0;
        if(data == null)
        {
            return length;
        }

        /* pad to a multiple of four */
        if((length % 4) > 0)
        {
            length += (4 - (length % 4));
        }

        return (char)data.length;
    }

    /**
     * Determines whether a specific channel number is in the valid channel
     * number range defined by the TURN RFC.
     *
     * @param channelNumber the channel number to be checked for being in the
     * valid channel number range defined by the TURN RFC
     * @return <tt>true</tt> if the specified <tt>channelNumber</tt> is in the
     * valid channel number range defined by the TURN RFC
     */
    private static boolean validateChannelNumber(char channelNumber)
    {
        return (channelNumber > 0x3FFF);
    }

    /**
     * Returns a binary representation of this message.
     * @return a binary representation of this message.
     * @throws StunException if the channel number is invalid
     */
    public byte[] encode() throws StunException
    {
        char dataLength = getDataLength();
        byte binMsg[] = new byte[HEADER_LENGTH + dataLength];
        int offset = 0;

        if(!validateChannelNumber(channelNumber))
        {
            throw new StunException(StunException.ILLEGAL_ARGUMENT, "Channel number invalid");
        }

        /* channel number */
        binMsg[offset++] = (byte)(channelNumber >> 8);
        binMsg[offset++] = (byte)(channelNumber & 0xff);

        /* length */
        binMsg[offset++] = (byte)((data != null) ? data.length >> 8 : 0);
        binMsg[offset++] = (byte)((data != null) ? data.length & 0xff : 0);

        if(data != null)
        {
            System.arraycopy(data, 0, binMsg, offset, data.length);
        }

        return binMsg;
    }

    /**
     * Constructs a message from its binary representation.
     * @param binMessage the binary array that contains the encoded message
     * @param offset the index where the message starts.
     * @param arrayLen the length of the message
     * @return a Message object constructed from the binMessage array
     * @throws StunException ILLEGAL_ARGUMENT if one or more of the arguments
     * have invalid values.
     */
    public static ChannelData decode(byte binMessage[], char offset, char arrayLen) throws StunException
    {
        char msgLen = 0;
        char channelNumber = 0;
        ChannelData channelData = null;
        byte data[] = null;

        if(arrayLen < 4)
        {
            throw new StunException(StunException.ILLEGAL_ARGUMENT, "Size too short");
        }

        channelNumber = (char)((binMessage[offset++]<<8) | (binMessage[offset++]&0xFF));

        if(!validateChannelNumber(channelNumber))
        {
            throw new StunException(StunException.ILLEGAL_ARGUMENT, "Channel number invalid");
        }

        msgLen = (char)((binMessage[offset++]<<8) | (binMessage[offset++]&0xFF));
        if(msgLen != (binMessage.length - 4))
        {
            throw new StunException(StunException.ILLEGAL_ARGUMENT, "Size mismatch");
        }

        data = new byte[msgLen];
        System.arraycopy(binMessage, offset, data, 0, msgLen);

        channelData = new ChannelData();
        channelData.setData(data);
        channelData.setChannelNumber(channelNumber);

        return channelData;
    }
    
    /**
     * Checks if the given binary message is a ChannelData Message. Every
     * ChannelData message has first two bits as 01.
     * 
     * @param binMessage binary message to check.
     * @return true is given binary message is a ChannelData Message.
     */
    public static boolean isChannelDataMessage(byte[] binMessage)
    {
        return (binMessage[0] >> 6 == 0x1);
    }
}

