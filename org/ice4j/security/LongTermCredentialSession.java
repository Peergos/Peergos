/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.security;

import java.lang.reflect.*;
import java.security.*;
import java.util.*;

import org.ice4j.*;
import org.ice4j.message.*;

/**
 * Represents a use of a <tt>LongTermCredential</tt> and implements
 * <tt>CredentialsAuthority</tt> for it.
 *
 * @author Lubomir Marinov
 */
public class LongTermCredentialSession
    implements CredentialsAuthority
{

    /**
     * The <tt>LongTermCredential</tt> a use of which is represented by this
     * instance.
     */
    private final LongTermCredential longTermCredential;

    /**
     * The value of the NONCE attribute currently associated with the use of
     * {@link #longTermCredential} represented by this instance.
     */
    private byte[] nonce;

    /**
     * The realm (i.e. the value of the REALM attribute) in which a use of
     * {@link #longTermCredential} is represented by this instance.
     */
    private final byte[] realm;

    /**
     * Initializes a new <tt>LongTermCredentialSession</tt> instance which
     * is to represent a use of a specific <tt>LongTermCredential</tt> in a
     * specific realm.
     *
     * @param longTermCredential the <tt>LongTermCredential</tt> a use of
     * which is to be represented by the new instance
     * @param realm the realm in which the specified
     * <tt>LongTermCredential</tt> is to be used
     */
    public LongTermCredentialSession(
            LongTermCredential longTermCredential,
            byte[] realm)
    {
        this.longTermCredential = longTermCredential;
        this.realm = (realm == null) ? null : realm.clone();
    }

    /**
     * Adds the <tt>Attribute</tt>s to a specific <tt>Request</tt> which support
     * the long-term credential mechanism using the <tt>LongTermCredential</tt>
     * associated with this instance.
     *
     * @param request the <tt>Request</tt> in which the <tt>Attribute</tt>
     * supporting the STUN long-term credential mechanism are to be added
     * @throws StunException if anything goes wrong while adding the
     * <tt>Attribute</tt>s to <tt>request</tt> which support the STUN long-term
     * credential mechanism
     */
    public void addAttributes(Request request)
        throws StunException
    {
        MessageFactory.addLongTermCredentialAttributes(
                request,
                getUsername(), getRealm(), getNonce());
    }

    /**
     * Determines whether <tt>username</tt> is currently known to this authority
     *
     * @param username the user name whose validity we'd like to check
     * @return <tt>true</tt> if <tt>username</tt> is known to this
     * <tt>CredentialsAuthority</tt>; <tt>false</tt>, otherwise
     * @see CredentialsAuthority#checkLocalUserName(String)
     */
    public boolean checkLocalUserName(String username)
    {
        /*
         * The value of USERNAME is a variable-length value. It MUST contain
         * a UTF-8 [RFC3629] encoded sequence of less than 513 bytes, and
         * MUST have been processed using SASLprep [RFC4013].
         */
        return usernameEquals(LongTermCredential.getBytes(username));
    }

    /**
     * Returns the key (password) that corresponds to the specified local
     * username or user frag,  an empty array if there was no password for
     * that username or <tt>null</tt> if the username is not a local user
     * name recognized by this <tt>CredentialsAuthority</tt>.
     *
     * @param username the local user name or user frag whose credentials
     * we'd like to obtain
     * @return the key (password) that corresponds to the specified local
     * username or user frag,  an empty array if there was no password for
     * that username or <tt>null</tt> if the username is not a local user
     * name recognized by this <tt>CredentialsAuthority</tt>
     * @see CredentialsAuthority#getLocalKey(String)
     */
    public byte[] getLocalKey(String username)
    {
        if (!checkLocalUserName(username))
            return null;

        // MD5(username ":" realm ":" SASLprep(password))
        StringBuilder localKeyBuilder = new StringBuilder();

        if (username != null)
            localKeyBuilder.append(username);
        localKeyBuilder.append(':');

        String realm = LongTermCredential.toString(getRealm());

        if (realm != null)
            localKeyBuilder.append(realm);
        localKeyBuilder.append(':');

        String password = LongTermCredential.toString(getPassword());

        if (password != null)
        {
            // TODO SASLprep
            localKeyBuilder.append(password);
        }

        MessageDigest md5;

        try
        {
            md5 = MessageDigest.getInstance("MD5");
        }
        catch (NoSuchAlgorithmException nsaex)
        {
            throw new UndeclaredThrowableException(nsaex);
        }
        return
            md5.digest(LongTermCredential.getBytes(localKeyBuilder.toString()));
    }

    /**
     * Gets the value of the NONCE attribute currently associated with the use
     * of the <tt>LongTermCredential</tt> represented by this instance.
     *
     * @return the value of the NONCE attribute currently associated with the
     * use of the <tt>LongTermCredential</tt> represented by this instance
     */
    public byte[] getNonce()
    {
        return (nonce == null) ? null : nonce.clone();
    }

    /**
     * Gets the password of the <tt>LongTermCredential</tt> used by this
     * instance.
     *
     * @return the password of the <tt>LongTermCredential</tt> used by this
     * instance
     */
    public byte[] getPassword()
    {
        return longTermCredential.getPassword();
    }

    /**
     * Gets the realm (i.e. the value of the REALM attribute) in which this
     * instance uses the <tt>LongTermCredential</tt> associated with it.
     *
     * @return the realm (i.e. the value of the REALM attribute) in which this
     * instance uses the <tt>LongTermCredential</tt> associated with it
     */
    public byte[] getRealm()
    {
        return (realm == null) ? null : realm.clone();
    }

    /**
     * Returns the key (password) that corresponds to the specified remote
     * username or user frag,  an empty array if there was no password for
     * that username or <tt>null</tt> if the username is not a remote user
     * name recognized by this <tt>CredentialsAuthority</tt>.
     *
     * @param username the remote user name or user frag whose credentials
     * we'd like to obtain
     * @param media not used
     * @return the key (password) that corresponds to the specified remote
     * username or user frag,  an empty array if there was no password for
     * that username or <tt>null</tt> if the username is not a remote user
     * name recognized by this <tt>CredentialsAuthority</tt>
     * @see CredentialsAuthority#getRemoteKey(String, String)
     */
    public byte[] getRemoteKey(String username, String media)
    {
        // The password is the same on the local and the remote sides.
        return getLocalKey(username);
    }

    /**
     * Gets the username of the <tt>LongTermCredential</tt> used by this
     * instance.
     *
     * @return the username of the <tt>LongTermCredential</tt> used by this
     * instance
     */
    public byte[] getUsername()
    {
        return longTermCredential.getUsername();
    }

    /**
     * Determines whether the realm of this <tt>LongTermCredentialSession</tt>
     * is equal to a specific realm.
     *
     * @param realm the realm to compare for equality to the realm of this
     * <tt>LongTermCredentialSession</tt>
     * @return <tt>true</tt> if the specified <tt>realm</tt> is equal to the
     * realm of this <tt>LongTermCredentialSession</tt>; otherwise,
     * <tt>false</tt>
     */
    public boolean realmEquals(byte[] realm)
    {
        return
            (realm == null)
                ? (this.realm == null)
                : Arrays.equals(realm, this.realm);
    }

    /**
     * Sets the value of the NONCE attribute to be associated with the use of
     * the <tt>LongTermCredential</tt> represented by this instance.
     *
     * @param nonce the value of the NONCE attribute to be associated with the
     * use of the <tt>LongTermCredential</tt> represented by this instance
     */
    public void setNonce(byte[] nonce)
    {
        this.nonce = (nonce == null) ? null : nonce.clone();
    }

    /**
     * Determines whether the username of the <tt>LongTermCredential</tt> used
     * by this instance is equal to a specific username.
     *
     * @param username the username to compare for equality to the username of
     * the <tt>LongTermCredential</tt> used by this instance
     * @return <tt>true</tt> if the specified <tt>username</tt> is equal to the
     * username of the <tt>LongTermCredential</tt> used by this instance;
     * otherwise, <tt>false</tt>
     */
    public boolean usernameEquals(byte[] username)
    {
        byte[] thisUsername = getUsername();

        return
            (username == null)
                ? (thisUsername == null)
                : Arrays.equals(username, thisUsername);
    }
}
