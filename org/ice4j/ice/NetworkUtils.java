/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.lang.reflect.*;
import java.net.*;
import java.util.*;
import java.util.logging.*;

/**
 * Utility methods and fields to use when working with network addresses.
 *
 * @author Emil Ivov
 * @author Damian Minkov
 * @author Vincent Lucas
 * @author Alan Kelly
 */
public class NetworkUtils
{
    /**
     * Our class logger
     */
    private static final Logger logger
        = Logger.getLogger(NetworkUtils.class.getName());

    /**
     * A string containing the "any" local address for IPv6.
     */
    public static final String IN6_ADDR_ANY = "::0";

    /**
     * The length of IPv6 addresses.
     */
    private final static int IN6_ADDR_SIZE = 16;

    /**
     * The size of the tokens in a <tt>String</tt> representation of IPv6
     * addresses.
     */
    //private final static int IN6_ADDR_TOKEN_SIZE = 16;
    private final static int IN6_ADDR_TOKEN_SIZE = 2;

    /**
     * A string containing the "any" local address for IPv4.
     */
    public static final String IN4_ADDR_ANY = "0.0.0.0";

    /**
     * The length of IPv4 addresses.
     */
    private final static int IN4_ADDR_SIZE = 4;

    /**
     * A string containing the "any" local address.
     */
    public static final String IN_ADDR_ANY = determineAnyAddress();

    /**
     * The maximum int value that could correspond to a port number.
     */
    public static final int    MAX_PORT_NUMBER = 65535;

    /**
     * The minimum int value that could correspond to a port number bindable
     * by ice4j.
     */
    public static final int    MIN_PORT_NUMBER = 1024;

    /**
     * The random port number generator that we use in getRandomPortNumer()
     */
    private static Random portNumberGenerator = new Random();

    /**
     * Determines whether the address is the result of windows auto configuration.
     * (i.e. One that is in the 169.254.0.0 network)
     * @param add the address to inspect
     * @return true if the address is autoconfigured by windows, false otherwise.
     */
    public static boolean isWindowsAutoConfiguredIPv4Address(InetAddress add)
    {
        return (add.getAddress()[0] & 0xFF) == 169
            && (add.getAddress()[1] & 0xFF) == 254;
    }

    /**
     * Determines whether the address is an IPv4 link local address. IPv4 link
     * local addresses are those in the following networks:
     *
     * 10.0.0.0    to 10.255.255.255
     * 172.16.0.0  to 172.31.255.255
     * 192.168.0.0 to 192.168.255.255
     *
     * @param add the address to inspect
     * @return true if add is a link local ipv4 address and false if not.
     */
    public static boolean isLinkLocalIPv4Address(InetAddress add)
    {
        if (add instanceof Inet4Address)
        {
            byte address[] = add.getAddress();
            if ( (address[0] & 0xFF) == 10)
                return true;
            if ( (address[0] & 0xFF) == 172
                && (address[1] & 0xFF) >= 16 && address[1] <= 31)
                return true;
            if ( (address[0] & 0xFF) == 192
                && (address[1] & 0xFF) == 168)
                return true;
            return false;
        }
        return false;
    }

    /**
     * Returns a random local port number that user applications could bind to.
     * (i.e. above 1024).
     * @return a random int located between 1024 and 65 535.
     */
    public static int getRandomPortNumber()
    {
        return getRandomPortNumber(MIN_PORT_NUMBER, MAX_PORT_NUMBER);
    }

    /**
     * Returns a random local port number, greater than min and lower than max.
     *
     * @param min the minimum allowed value for the returned port number.
     * @param max the maximum allowed value for the returned port number.
     *
     * @return a random int located between greater than min and lower than max.
     */
    public static int getRandomPortNumber(int min, int max)
    {
        return portNumberGenerator.nextInt(max - min) + min;
    }

    /**
     * Returns a random local port number, greater than min and lower than max.
     * If the pair flag is set to true, then the returned port number is
     * guaranteed to be pair. This is useful for protocols that require this
     * such as RTP
     *
     * @param min the minimum allowed value for the returned port number.
     * @param max the maximum allowed value for the returned port number.
     * @param pair specifies whether the caller would like the returned port to
     * be pair.
     *
     * @return a random int located between greater than min and lower than max.
     */
    public static int getRandomPortNumber(int min, int max, boolean pair)
    {
        if(pair)
        {
            int delta = max - min;
            delta /= 2;
            int port = getRandomPortNumber(min, min + delta);
            return port * 2;
        }
        else
        {
            return getRandomPortNumber(min, max);
        }
    }

    /**
     * Verifies whether <tt>address</tt> could be an IPv4 address string.
     *
     * @param address the String that we'd like to determine as an IPv4 address.
     *
     * @return true if the address contained by <tt>address</tt> is an IPv4
     * address and false otherwise.
     */
    public static boolean isIPv4Address(String address)
    {
        return strToIPv4(address) != null;
    }

    /**
     * Verifies whether <tt>address</tt> could be an IPv6 address string.
     *
     * @param address the String that we'd like to determine as an IPv6 address.
     *
     * @return true if the address contained by <tt>address</tt> is an IPv6
     * address and false otherwise.
     */
    public static boolean isIPv6Address(String address)
    {
        return strToIPv6(address) != null;
    }

    /**
     * Checks whether <tt>address</tt> is a valid IP address string.
     *
     * @param address the address that we'd like to check
     * @return true if address is an IPv4 or IPv6 address and false otherwise.
     */
    public static boolean isValidIPAddress(String address)
    {
        // empty string
        if (address == null || address.length() == 0)
        {
            return false;
        }

        // look for IPv6 brackets and remove brackets for parsing
        boolean ipv6Expected = false;
        if (address.charAt(0) == '[')
        {
            // This is supposed to be an IPv6 literal
            if (address.length() > 2
                            && address.charAt(address.length() - 1) == ']')
            {
                // remove brackets from IPv6
                address = address.substring(1, address.length() - 1);
                ipv6Expected = true;
            }
            else
            {
                return false;
            }
        }

        // look for IP addresses
        if (Character.digit(address.charAt(0), 16) != -1
                        || (address.charAt(0) == ':'))
        {
            byte[] addr = null;

            // see if it is IPv4 address
            addr = strToIPv4(address);
            // if not, see if it is IPv6 address
            if (addr == null)
            {
                addr = strToIPv6(address);
            }
            // if IPv4 is found when IPv6 is expected
            else if (ipv6Expected)
            {
                // invalid address: IPv4 address surrounded with brackets!
                return false;
            }
            // if an IPv4 or IPv6 address is found
            if (addr != null)
            {
                // is an IP address
                return true;
            }
        }
        // no matches found
        return false;
    }

    /**
     * Creates a byte array containing the specified <tt>ipv4AddStr</tt>.
     *
     * @param ipv4AddrStr a <tt>String</tt> containing an IPv4 address.
     *
     * @return a byte array containing the four bytes of the address represented
     * by ipv4AddrStr or <tt>null</tt> if <tt>ipv4AddrStr</tt> does not contain
     * a valid IPv4 address string.
     */
    public static byte[] strToIPv4(String ipv4AddrStr)
    {
        if (ipv4AddrStr.length() == 0)
            return null;

        byte[] address = new byte[IN4_ADDR_SIZE];
        String[] tokens = ipv4AddrStr.split("\\.", -1);
        long currentTkn;
        try
        {
            switch(tokens.length)
            {
                case 1:
                    //If the address was specified as a single String we can
                    //directly copy it into the byte array.
                   currentTkn = Long.parseLong(tokens[0]);
                   if (currentTkn < 0 || currentTkn > 0xffffffffL)
                       return null;
                   address[0] = (byte) ((currentTkn >> 24) & 0xff);
                   address[1] = (byte) (((currentTkn & 0xffffff) >> 16) & 0xff);
                   address[2] = (byte) (((currentTkn & 0xffff) >> 8) & 0xff);
                   address[3] = (byte) (currentTkn & 0xff);
                   break;
                case 2:
                    // If the address was passed in two parts (e.g. when dealing
                    // with a Class A address representation), we place the
                    // first one in the leftmost byte and the rest in the three
                    // remaining bytes of the address array.
                    currentTkn = Integer.parseInt(tokens[0]);

                    if (currentTkn < 0 || currentTkn > 0xff)
                        return null;

                    address[0] = (byte) (currentTkn & 0xff);
                    currentTkn = Integer.parseInt(tokens[1]);

                    if (currentTkn < 0 || currentTkn > 0xffffff)
                        return null;

                    address[1] = (byte) ((currentTkn >> 16) & 0xff);
                    address[2] = (byte) (((currentTkn & 0xffff) >> 8) &0xff);
                    address[3] = (byte) (currentTkn & 0xff);
                    break;
                case 3:
                    // If the address was passed in three parts (e.g. when
                    // dealing with a Class B address representation), we place
                    // the first two parts in the two leftmost bytes and the
                    // rest in the two remaining bytes of the address array.
                    for (int i = 0; i < 2; i++)
                    {
                        currentTkn = Integer.parseInt(tokens[i]);

                        if (currentTkn < 0 || currentTkn > 0xff)
                            return null;

                        address[i] = (byte) (currentTkn & 0xff);
                    }

                    currentTkn = Integer.parseInt(tokens[2]);

                    if (currentTkn < 0 || currentTkn > 0xffff)
                        return null;

                    address[2] = (byte) ((currentTkn >> 8) & 0xff);
                    address[3] = (byte) (currentTkn & 0xff);
                    break;
                case 4:
                    // And now for the most common - four part case. This time
                    // there's a byte for every part :). Yuppiee! :)
                    for (int i = 0; i < 4; i++)
                    {
                        currentTkn = Integer.parseInt(tokens[i]);

                        if (currentTkn < 0 || currentTkn > 0xff)
                            return null;

                        address[i] = (byte) (currentTkn & 0xff);
                    }
                    break;
                default:
                    return null;
            }
        }
        catch(NumberFormatException e)
        {
            return null;
        }

        return address;
    }

    /**
     * Creates a byte array containing the specified <tt>ipv6AddStr</tt>.
     *
     * @param ipv6AddrStr a <tt>String</tt> containing an IPv6 address.
     *
     * @return a byte array containing the four bytes of the address represented
     * by <tt>ipv6AddrStr</tt> or <tt>null</tt> if <tt>ipv6AddrStr</tt> does
     * not contain a valid IPv6 address string.
     */
    public static byte[] strToIPv6(String ipv6AddrStr)
    {
        // Bail out if the string is shorter than "::"
        if (ipv6AddrStr.length() < 2)
            return null;

        int colonIndex;
        char currentChar;
        boolean sawtDigit;
        int currentTkn;
        char[] addrBuff = ipv6AddrStr.toCharArray();
        byte[] dst = new byte[IN6_ADDR_SIZE];

        int srcb_length = addrBuff.length;
        int scopeID = ipv6AddrStr.indexOf ("%");

        if (scopeID == srcb_length -1)
            return null;

        if (scopeID != -1)
            srcb_length = scopeID;

        colonIndex = -1;
        int i = 0, j = 0;
        // Starting : mean we need to have at least one more.
        if (addrBuff[i] == ':')
            if (addrBuff[++i] != ':')
                return null;

        int curtok = i;
        sawtDigit = false;
        currentTkn = 0;
        while (i < srcb_length)
        {
            currentChar = addrBuff[i++];
            int chval = Character.digit(currentChar, 16);
            if (chval != -1)
            {
                currentTkn <<= 4;
                currentTkn |= chval;
                if (currentTkn > 0xffff)
                    return null;
                sawtDigit = true;
                continue;
            }

            if (currentChar == ':')
            {
                curtok = i;

                if (!sawtDigit)
                {
                    if (colonIndex != -1)
                        return null;
                    colonIndex = j;
                    continue;
                }
                else if (i == srcb_length)
                {
                    return null;
                }

                if (j + IN6_ADDR_TOKEN_SIZE > IN6_ADDR_SIZE)
                    return null;

                dst[j++] = (byte) ((currentTkn >> 8) & 0xff);
                dst[j++] = (byte) (currentTkn & 0xff);
                sawtDigit = false;
                currentTkn = 0;
                continue;
            }

            if (currentChar == '.' && ((j + IN4_ADDR_SIZE) <= IN6_ADDR_SIZE))
            {
                String ia4 = ipv6AddrStr.substring(curtok, srcb_length);
                // check this IPv4 address has 3 dots, ie. A.B.C.D
                int dot_count = 0, index=0;
                while ((index = ia4.indexOf ('.', index)) != -1)
                {
                    dot_count ++;
                    index ++;
                }

                if (dot_count != 3)
                    return null;

                byte[] v4addr = strToIPv4(ia4);
                if (v4addr == null)
                    return null;

                for (int k = 0; k < IN4_ADDR_SIZE; k++)
                {
                    dst[j++] = v4addr[k];
                }

                sawtDigit = false;
                break;  /* '\0' was seen by inet_pton4(). */
            }
            return null;
        }

        if (sawtDigit)
        {
            if (j + IN6_ADDR_TOKEN_SIZE > IN6_ADDR_SIZE)
                return null;

            dst[j++] = (byte) ((currentTkn >> 8) & 0xff);
            dst[j++] = (byte) (currentTkn & 0xff);
        }

        if (colonIndex != -1)
        {
            int n = j - colonIndex;

            if (j == IN6_ADDR_SIZE)
                return null;

            for (i = 1; i <= n; i++)
            {
                dst[IN6_ADDR_SIZE - i] = dst[colonIndex + n - i];
                dst[colonIndex + n - i] = 0;
            }

            j = IN6_ADDR_SIZE;
        }

        if (j != IN6_ADDR_SIZE)
            return null;

        byte[] newdst = mappedIPv4ToRealIPv4(dst);

        if (newdst != null)
        {
            return newdst;
        }
        else
        {
            return dst;
        }
    }

    /**
     * Returns an IPv4 address matching the one mapped in the IPv6
     * <tt>addr</tt>. Both input and returned value are in network order.
     *
     * @param addr a String representing an IPv4-Mapped address in textual
     * format
     *
     * @return a byte array numerically representing the IPv4 address
     */
    public static byte[] mappedIPv4ToRealIPv4(byte[] addr)
    {
        if (isMappedIPv4Addr(addr))
        {
            byte[] newAddr = new byte[IN4_ADDR_SIZE];
            System.arraycopy(addr, 12, newAddr, 0, IN6_ADDR_SIZE);
            return newAddr;
        }

        return null;
    }

    /**
     * Utility method to check if the specified <tt>address</tt> is an IPv4
     * mapped IPv6 address.
     *
     * @param address the address that we'd like to determine as an IPv4 mapped
     * one or not.
     *
     * @return <tt>true</tt> if address is an IPv4 mapped IPv6 address and
     * <tt>false</tt> otherwise.
     */
    private static boolean isMappedIPv4Addr(byte[] address)
    {
        if (address.length < IN6_ADDR_SIZE)
        {
            return false;
        }

        if ((address[0] == 0x00) && (address[1] == 0x00)
            && (address[2] == 0x00) && (address[3] == 0x00)
            && (address[4] == 0x00) && (address[5] == 0x00)
            && (address[6] == 0x00) && (address[7] == 0x00)
            && (address[8] == 0x00) && (address[9] == 0x00)
            && (address[10] == (byte)0xff)
            && (address[11] == (byte)0xff))
        {
            return true;
        }

        return false;
    }

    /**
     * Creates an InetAddress from the specified <tt>hostAddress</tt>. The point
     * of using the method rather than creating the address by yourself is that
     * it would first check whether the specified <tt>hostAddress</tt> is indeed
     * a valid ip address. It this is the case, the method would create the
     * <tt>InetAddress</tt> using the <tt>InetAddress.getByAddress()</tt>
     * method so that no DNS resolution is attempted by the JRE. Otherwise
     * it would simply use <tt>InetAddress.getByName()</tt> so that we would an
     * <tt>InetAddress</tt> instance even at the cost of a potential DNS
     * resolution.
     *
     * @param hostAddress the <tt>String</tt> representation of the address
     * that we would like to create an <tt>InetAddress</tt> instance for.
     *
     * @return an <tt>InetAddress</tt> instance corresponding to the specified
     * <tt>hostAddress</tt>.
     *
     * @throws UnknownHostException if any of the <tt>InetAddress</tt> methods
     * we are using throw an exception.
     */
    public static InetAddress getInetAddress(String hostAddress)
        throws UnknownHostException
    {
        //is null
        if (hostAddress == null || hostAddress.length() == 0)
        {
            throw new UnknownHostException(
                            hostAddress + " is not a valid host address");
        }

        //transform IPv6 literals into normal addresses
        if (hostAddress.charAt(0) == '[')
        {
            // This is supposed to be an IPv6 literal
            if (hostAddress.length() > 2
                && hostAddress.charAt(hostAddress.length()-1) == ']')
            {
                hostAddress = hostAddress.substring(1, hostAddress.length() -1);
            }
            else
            {
                // This was supposed to be a IPv6 address, but it's not!
                throw new UnknownHostException(hostAddress);
            }
        }


        if (NetworkUtils.isValidIPAddress(hostAddress))
        {
            byte[] addr = null;

            // attempt parse as IPv4 address
            addr = strToIPv4(hostAddress);

            // if not IPv4, parse as IPv6 address
            if (addr == null)
            {
                addr = strToIPv6(hostAddress);
            }
            return InetAddress.getByAddress(hostAddress, addr);
        }
        else
        {
            return InetAddress.getByName(hostAddress);
        }
    }

    /**
     * Tries to determine if this host supports IPv6 addresses (i.e. has at
     * least one IPv6 address) and returns IN6_ADDR_ANY or IN4_ADDR_ANY
     * accordingly. This method is only used to initialize IN_ADDR_ANY so that
     * it could be used when binding sockets. The reason we need it is because
     * on mac (contrary to lin or win) binding a socket on 0.0.0.0 would make
     * it deaf to IPv6 traffic. Binding on ::0 does the trick but that would
     * fail on hosts that have no IPv6 support. Using the result of this method
     * provides an easy way to bind sockets in cases where we simply want any
     * IP packets coming on the port we are listening on (regardless of IP
     * version).
     *
     * @return IN6_ADDR_ANY or IN4_ADDR_ANY if this host supports or not IPv6.
     */
    private static String determineAnyAddress()
    {
        Enumeration<NetworkInterface> ifaces;
        try
        {
            ifaces = NetworkInterface.getNetworkInterfaces();
        }
        catch (SocketException e)
        {
            logger.log(Level.FINE, "Couldn't retrieve local interfaces.", e);
            return IN4_ADDR_ANY;
        }

        while(ifaces.hasMoreElements())
        {
            Enumeration<InetAddress> addrs
                                = ifaces.nextElement().getInetAddresses();
            while (addrs.hasMoreElements())
            {
                if(addrs.nextElement() instanceof Inet6Address)
                    return IN6_ADDR_ANY;
            }
        }

        return IN4_ADDR_ANY;
    }

    /**
     * Determines whether <tt>port</tt> is a valid port number bindable by an
     * application (i.e. an integer between 1024 and 65535).
     *
     * @param port the port number that we'd like verified.
     *
     * @return <tt>true</tt> if port is a valid and bindable port number and
     * <tt>alse</tt> otherwise.
     */
    public static boolean isValidPortNumber(int port)
    {
        return MIN_PORT_NUMBER < port && port < MAX_PORT_NUMBER;
    }

    /**
     * Determines whether or not the <tt>iface</tt> interface is a loopback
     * interface. We use this method as a replacement to the
     * <tt>NetworkInterface.isLoopback()</tt> method that only comes with
     * java 1.6.
     *
     * @param iface the inteface that we'd like to determine as loopback or not.
     *
     * @return true if <tt>iface</tt> contains at least one loopback address
     * and <tt>false</tt> otherwise.
     */
    public static boolean isInterfaceLoopback(NetworkInterface iface)
    {
        try
        {
            Method method = iface.getClass().getMethod("isLoopback");

            return ((Boolean)method.invoke(iface, new Object[]{}))
                        .booleanValue();
        }
        catch(Throwable t)
        {
            //apparently we are not running in a JVM that supports the
            //is Loopback method. we'll try another approach.
        }
        Enumeration<InetAddress> addresses = iface.getInetAddresses();

        return addresses.hasMoreElements()
            && addresses.nextElement().isLoopbackAddress();
    }

    /**
     * Determines, if possible, whether or not the <tt>iface</tt> interface is
     * up. We use this method so that we could use {@link
     * java.net.NetworkInterface}'s <tt>isUp()</tt> when running a JVM that
     * supports it and return a default value otherwise.
     *
     * @param iface the interface that we'd like to determine as Up or Down.
     *
     * @return <tt>false</tt> if <tt>iface</tt> is known to be down and
     * <tt>true</tt> if the <tt>iface</tt> is Up or in case we couldn't
     * determine.
     */
    public static boolean isInterfaceUp(NetworkInterface iface)
    {
        try
        {
            Method method = iface.getClass().getMethod("isUp");

            return ((Boolean)method.invoke(iface)).booleanValue();
        }
        catch(Throwable t)
        {
            //apparently we are not running in a JVM that supports the
            //isUp method. returning default value.
        }

        return true;
    }

    /**
     * Determines, if possible, whether or not the <tt>iface</tt> interface is
     * a virtual interface (e.g. VPN, MIPv6 tunnel, etc.) or not. We use this
     * method so that we could use {@link java.net.NetworkInterface}'s
     * <tt>isVirtual()</tt> when running a JVM that supports it and return a
     * default value otherwise.
     *
     * @param iface the interface that we'd like to determine as virtual or not.
     *
     * @return <tt>true</tt> if <tt>iface</tt> is known to be a virtual
     * interface and <tt>false</tt> if the <tt>iface</tt> is not virtual or in
     * case we couldn't determine.
     */
    public static boolean isInterfaceVirtual(NetworkInterface iface)
    {
        try
        {
            Method method = iface.getClass().getMethod("isVirtual");

            return ((Boolean)method.invoke(iface)).booleanValue();
        }
        catch(Throwable t)
        {
            //apparently we are not running in a JVM that supports the
            //isVirtual method. returning default value.
        }

        return false;
    }

    /**
     * Returns a <tt>String</tt> that is guaranteed not to contain an address
     * scope specified (i.e. removes the %scopeID at the end of IPv6 addresses
     * returned by Java. Takes into account the presence or absence of square
     * brackets encompassing the address.
     *
     * @param ipv6Address the address whose scope ID we'd like to get rid of.
     *
     * @return the newly form address containing no scope ID.
     */
    public static String stripScopeID(String ipv6Address)
    {
        int scopeStart = ipv6Address.indexOf('%');

        if (scopeStart == -1)
            return ipv6Address;

        ipv6Address = ipv6Address.substring(0, scopeStart);

        //in case this was an IPv6 literal and we remove the closing bracket,
        //put it back in now.
        if(ipv6Address.charAt(0) == '['
            && ipv6Address.charAt(ipv6Address.length()-1) != ']')
        {
            ipv6Address += ']';
        }

        return ipv6Address;
    }
}
