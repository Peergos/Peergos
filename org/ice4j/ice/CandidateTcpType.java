/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice;

/**
 * Represents the TCP types for ICE TCP candidates.
 * See http://tools.ietf.org/html/rfc6544
 *
 * @author Boris Grozev
 */
public enum CandidateTcpType
{
    /**
     * The "active" TCP candidate type.
     */
    ACTIVE("active"),

    /**
     * The "passive" TCP candidate type.
     */
    PASSIVE("passive"),

    /**
     * The "so" (simultaneous-open) TCP candidate type.
     */
    SO("so");

    /**
     * The name of this <tt>CandidateTcpType</tt> instance.
     */
    private final String name;

    /**
     * Creates a <tt>CandidateTcpType</tt> instance with the specified name.
     *
     * @param name the name of the <tt>CandidateTcpType</tt> instance we'd
     * like to create.
     */
    private CandidateTcpType(String name)
    {
        this.name = name;
    }

    /**
     * Returns the name of this <tt>CandidateTcpType</tt> (e.g. "active",
     * "passive", or "so").
     *
     * @return the name of this <tt>CandidateTcpType</tt> (e.g. "active",
     * "passive", or "so").
     */
    @Override
    public String toString()
    {
        return name;
    }

    /**
     *
     * Parses the string <tt>candidateTcpTypeName</tt> and return the
     * corresponding <tt>CandidateTcpType</tt> instance.
     *
     * @throws IllegalArgumentException in case <tt>candidateTcpTypeName</tt> is
     * not a valid or currently supported candidate TCP type.
     */
    public static CandidateTcpType parse(String candidateTcpTypeName)
            throws IllegalArgumentException
    {
        for (CandidateTcpType type : values())
            if (type.toString().equals(candidateTcpTypeName))
                return type;

        throw new IllegalArgumentException(candidateTcpTypeName
                    + " is not a currently supported CandidateTcpType");
    }
}

