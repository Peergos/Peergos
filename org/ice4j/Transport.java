/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j;

/**
 * The <tt>Transport</tt> enumeration contains all currently known transports
 * that ICE may be interacting with (but not necessarily support).
 *
 * @author Emil Ivov
 */
public enum Transport
{
    /**
     * Represents a TCP transport.
     */
    TCP("tcp"),

    /**
     * Represents a UDP transport.
     */
    UDP("udp"),

    /**
     * Represents a TLS transport.
     */
    TLS("tls"),

    /**
     * Represents a datagram TLS (DTLS) transport.
     */
    DTLS("dtls"),

    /**
     * Represents an SCTP transport.
     */
    SCTP("sctp"),

    /**
     * Represents an Google's SSL TCP transport.
     */
    SSLTCP("ssltcp");

    /**
     * The name of this <tt>Transport</tt>.
     */
    private final String transportName;

    /**
     * Creates a <tt>Transport</tt> instance with the specified name.
     *
     * @param transportName the name of the <tt>Transport</tt> instance we'd
     * like to create.
     */
    private Transport(String transportName)
    {
        this.transportName = transportName;
    }

    /**
     * Returns the name of this <tt>Transport</tt> (e.g. "udp" or
     * "tcp").
     *
     * @return the name of this <tt>Transport</tt> (e.g. "udp" or
     * "tcp").
     */
    @Override
    public String toString()
    {
        return transportName;
    }

    /**
     * Returns a <tt>Transport</tt> instance corresponding to the specified
     * <tt>transportName</tt>. For example, for name "udp", this method
     * would return {@link #UDP}.
     *
     * @param transportName the name that we'd like to parse.
     * @return a <tt>Transport</tt> instance corresponding to the specified
     * <tt>transportName</tt>.
     *
     * @throws IllegalArgumentException in case <tt>transportName</tt> is
     * not a valid or currently supported transport.
     */
    public static Transport parse(String transportName)
        throws IllegalArgumentException
    {
        if(UDP.toString().equals(transportName))
            return UDP;

        if(TCP.toString().equals(transportName))
            return TCP;

        if(TLS.toString().equals(transportName))
            return TLS;

        if(SCTP.toString().equals(transportName))
            return SCTP;

        if(DTLS.toString().equals(transportName))
            return DTLS;

        if(SSLTCP.toString().equals(transportName))
            return SSLTCP;

        throw new IllegalArgumentException(
            transportName + " is not a currently supported Transport");
    }
}
