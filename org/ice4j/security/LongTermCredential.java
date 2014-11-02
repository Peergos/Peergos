/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.security;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;

/**
 * Represents a STUN long-term credential.
 *
 * @author Lubomir Marinov
 * @author Aakash Garg
 */
public class LongTermCredential
{

    /**
     * Encodes a specific <tt>String</tt> into a sequence of <tt>byte</tt>s
     * using the UTF-8 charset, storing the result into a new <tt>byte</tt>
     * array.
     *
     * @param s the <tt>String</tt> to encode
     * @return a new array of <tt>byte</tt>s which represents the encoding of
     * the specified <tt>String</tt> using the UTF-8 charset
     */
    public static byte[] getBytes(String s)
    {
        if (s == null)
            return null;
        else
        {
            try
            {
                return s.getBytes("UTF-8");
            }
            catch (UnsupportedEncodingException ueex)
            {
                throw new UndeclaredThrowableException(ueex);
            }
        }
    }

    /**
     * Constructs a new <tt>String</tt> by decoding a specific array of
     * <tt>byte</tt>s using the UTF-8 charset. The length of the new
     * <tt>String</tt> is a function of the charset, and hence may not be equal
     * to the length of the <tt>byte</tt> array.
     * 
     * @param bytes the <tt>byte</tt>s to be decoded into characters
     * @return a new <tt>String</tt> which has been decoded from the specified
     * array of <tt>byte</tt>s using the UTF-8 charset
     */
    public static String toString(byte[] bytes)
    {
        if (bytes == null)
            return null;
        else
        {
            try
            {
                return new String(bytes, "UTF-8");
            }
            catch (UnsupportedEncodingException ueex)
            {
                throw new UndeclaredThrowableException(ueex);
            }
        }
    }

    /**
     * The password of this <tt>LongTermCredential</tt>.
     */
    private final byte[] password;

    /**
     * The username of this <tt>LongTermCredential</tt>.
     */
    private final byte[] username;

    /**
     * Initializes a new <tt>LongTermCredential</tt> instance with no username
     * and no password. Extenders should override {@link #getUsername()} and
     * {@link #getPassword()} to provide the username and the password,
     * respectively, when requested.
     */
    protected LongTermCredential()
    {
        this((byte[]) null, (byte[]) null);
    }

    /**
     * Initializes a new <tt>LongTermCredential</tt> instance with a specific
     * username and a specific password.
     *
     * @param username the username to initialize the new instance with
     * @param password the password to initialize the new instance with
     */
    public LongTermCredential(byte[] username, byte[] password)
    {
        this.username = (username == null) ? null : username.clone();
        this.password = (password == null) ? null : password.clone();
    }

    /**
     * Initializes a new <tt>LongTermCredential</tt> instance with a specific
     * username and a specific password.
     *
     * @param username the username to initialize the new instance with
     * @param password the password to initialize the new instance with
     */
    public LongTermCredential(String username, String password)
    {
        this(getBytes(username), getBytes(password));
    }

    /**
     * Gets the password of this <tt>LongTermCredential</tt>.
     *
     * @return an array of <tt>byte</tt>s which represents the password of this
     * <tt>LongTermCredential</tt>
     */
    public byte[] getPassword()
    {
        return (password == null) ? null : password.clone();
    }

    /**
     * Gets the username of this <tt>LongTermCredential</tt>.
     *
     * @return an array of <tt>byte</tt>s which represents the username of this
     * <tt>LongTermCredential</tt>
     */
    public byte[] getUsername()
    {
        return (username == null) ? null : username.clone();
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(password);
        result = prime * result + Arrays.hashCode(username);
        return result;
    }

    @Override
    public boolean equals(Object o)
    {
        if (o instanceof LongTermCredential)
        {
            LongTermCredential ltc = (LongTermCredential) o;
            if (Arrays.equals(
                this.username, ltc.username) && Arrays.equals(
                this.password, ltc.password))
            {
                return true;
            }
        }
        return false;
    }
    
}
