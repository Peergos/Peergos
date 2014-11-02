/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.stunclient;

import java.io.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * <p>
 * This class implements the STUN Discovery Process as described by section 10.1
 * of rfc 3489.
 * </p><p>
 * The flow makes use of three tests.  In test I, the client sends a
 * STUN Binding Request to a server, without any flags set in the
 * CHANGE-REQUEST attribute, and without the RESPONSE-ADDRESS attribute.
 * This causes the server to send the response back to the address and
 * port that the request came from.  In test II, the client sends a
 * Binding Request with both the "change IP" and "change port" flags
 * from the CHANGE-REQUEST attribute set.  In test III, the client sends
 * a Binding Request with only the "change port" flag set.
 * </p><p>
 * The client begins by initiating test I.  If this test yields no
 * response, the client knows right away that it is not capable of UDP
 * connectivity.  If the test produces a response, the client examines
 * the MAPPED-ADDRESS attribute.  If this address and port are the same
 * as the local IP address and port of the socket used to send the
 * request, the client knows that it is not natted.  It executes test
 * II.
 * </p><p>
 * If a response is received, the client knows that it has open access
 * to the Internet (or, at least, its behind a firewall that behaves
 * like a full-cone NAT, but without the translation).  If no response
 * is received, the client knows its behind a symmetric UDP firewall.
 * </p><p>
 * In the event that the IP address and port of the socket did not match
 * the MAPPED-ADDRESS attribute in the response to test I, the client
 * knows that it is behind a NAT.  It performs test II.  If a response
 * is received, the client knows that it is behind a full-cone NAT.  If
 * no response is received, it performs test I again, but this time,
 * does so to the address and port from the CHANGED-ADDRESS attribute
 * from the response to test I.  If the IP address and port returned in
 * the MAPPED-ADDRESS attribute are not the same as the ones from the
 * first test I, the client knows its behind a symmetric NAT.  If the
 * address and port are the same, the client is either behind a
 * restricted or port restricted NAT.  To make a determination about
 * which one it is behind, the client initiates test III.  If a response
 * is received, its behind a restricted NAT, and if no response is
 * received, its behind a port restricted NAT.
 * </p><p>
 * This procedure yields substantial information about the operating
 * condition of the client application.  In the event of multiple NATs
 * between the client and the Internet, the type that is discovered will
 * be the type of the most restrictive NAT between the client and the
 * Internet.  The types of NAT, in order of restrictiveness, from most
 * to least, are symmetric, port restricted cone, restricted cone, and
 * full cone.
 * </p><p>
 * Typically, a client will re-do this discovery process periodically to
 * detect changes, or look for inconsistent results.  It is important to
 * note that when the discovery process is redone, it should not
 * generally be done from the same local address and port used in the
 * previous discovery process.  If the same local address and port are
 * reused, bindings from the previous test may still be in existence,
 * and these will invalidate the results of the test.  Using a different
 * local address and port for subsequent tests resolves this problem.
 * An alternative is to wait sufficiently long to be confident that the
 * old bindings have expired (half an hour should more than suffice).
 * </p>
 *
 * @author Emil Ivov
 */
public class NetworkConfigurationDiscoveryProcess
{
    /**
     * Our class logger.
     */
    private static final Logger logger =
        Logger.getLogger(NetworkConfigurationDiscoveryProcess.class.getName());
    /**
     * Indicates whether the underlying stack has been initialized and started
     * and that the discoverer is operational.
     */
    private boolean started = false;

    /**
     * The point where we'll be listening.
     */
    private TransportAddress localAddress  = null;

    /**
     * The address of the stun server
     */
    private TransportAddress serverAddress = null;

    /**
     * A utility used to flatten the multi thread architecture of the Stack
     * and execute the discovery process in a synchronized manner
     */
    private BlockingRequestSender requestSender = null;

    /**
     * The <tt>DatagramSocket</tt> that we are going to be running the
     * discovery process through.
     */
    private IceSocketWrapper sock = null;

    /**
     * The <tt>StunStack</tt> used by this instance for the purposes of STUN
     * communication.
     */
    private final StunStack stunStack;

    /**
     * Initializes a <tt>StunAddressDiscoverer</tt> with a specific
     * <tt>StunStack</tt>. In order to use it one must first start it.
     *
     * @param stunStack the <tt>StunStack</tt> to be used by the new instance
     * @param localAddress  the address where the stack should bind.
     * @param serverAddress the address of the server to interrogate.
     */
    public NetworkConfigurationDiscoveryProcess(
            StunStack stunStack,
            TransportAddress localAddress, TransportAddress serverAddress)
    {
        if (stunStack == null)
            throw new NullPointerException("stunStack");

        this.stunStack = stunStack;
        this.localAddress  = localAddress;
        this.serverAddress = serverAddress;
    }

    /**
     * Shuts down the underlying stack and prepares the object for garbage
     * collection.
     */
    public void shutDown()
    {
        stunStack.removeSocket(localAddress);
        sock.close();
        sock = null;

        localAddress  = null;
        requestSender = null;

        this.started = false;
    }

    /**
     * Puts the discoverer into an operational state.
     * @throws IOException if we fail to bind.
     * @throws StunException if the stun4j stack fails start for some reason.
     */
    public void start()
        throws IOException, StunException
    {
        sock = new IceUdpSocketWrapper(
            new SafeCloseDatagramSocket(localAddress));

        stunStack.addSocket(sock);

        requestSender = new BlockingRequestSender(stunStack, localAddress);

        started = true;
    }

    /**
     * Implements the discovery process itself (see class description).
     * @return a StunDiscoveryReport containing details about the network
     * configuration of the host where the class is executed.
     *
     * @throws StunException ILLEGAL_STATE if the discoverer has not been started
     * @throws IOException if a failure occurs while executing the discovery
     * algorithm.
     */
    public StunDiscoveryReport determineAddress()
        throws StunException, IOException
    {
        checkStarted();
        StunDiscoveryReport report = new StunDiscoveryReport();
        StunMessageEvent evt = doTestI(serverAddress);

        if(evt == null)
        {
            //UDP Blocked
            report.setNatType(StunDiscoveryReport.UDP_BLOCKING_FIREWALL);
            return report;
        }
        else
        {
            TransportAddress mappedAddress
                =((MappedAddressAttribute)evt.getMessage()
                  .getAttribute(Attribute.MAPPED_ADDRESS)).getAddress();

            if(mappedAddress == null)
            {
              /* maybe we contact a STUNbis server and which do not
               * understand our request.
               */
              logger.info("Failed to do the network discovery");
              return null;
            }

            logger.fine("mapped address is="+mappedAddress
                        +", name=" + mappedAddress.getHostAddress());

            TransportAddress backupServerAddress
                =((ChangedAddressAttribute) evt.getMessage()
                  .getAttribute(Attribute.CHANGED_ADDRESS)).getAddress();

            logger.fine("backup server address is="+backupServerAddress
                        + ", name=" + backupServerAddress.getHostAddress());

            report.setPublicAddress(mappedAddress);
            if (mappedAddress.equals(localAddress))
            {
                evt = doTestII(serverAddress);
                if (evt == null)
                {
                    //Sym UDP Firewall
                    report.setNatType(StunDiscoveryReport
                                        .SYMMETRIC_UDP_FIREWALL);
                    return report;
                }
                else
                {
                    //open internet
                    report.setNatType(StunDiscoveryReport.OPEN_INTERNET);
                    return report;

                }
            }
            else
            {
                evt = doTestII(serverAddress);
                if (evt == null)
                {
                    evt = doTestI(backupServerAddress);
                    if(evt == null)
                    {
                        logger.info("Failed to receive a response from "
                                    +"backup stun server!");
                        return report;
                    }
                    TransportAddress mappedAddress2 =
                        ((MappedAddressAttribute)evt.getMessage().
                            getAttribute(Attribute.MAPPED_ADDRESS))
                                .getAddress();
                    if(mappedAddress.equals(mappedAddress2))
                    {
                        evt = doTestIII(serverAddress);
                        if(evt == null)
                        {
                            //port restricted cone
                            report.setNatType(StunDiscoveryReport
                                              .PORT_RESTRICTED_CONE_NAT);
                            return report;
                        }
                        else
                        {
                            //restricted cone
                            report.setNatType(StunDiscoveryReport
                                              .RESTRICTED_CONE_NAT);
                            return report;

                        }
                    }
                    else
                    {
                        //Symmetric NAT
                        report.setNatType(StunDiscoveryReport.SYMMETRIC_NAT);
                        return report;
                    }
                }
                else
                {
                    //full cone
                    report.setNatType(StunDiscoveryReport.FULL_CONE_NAT);
                    return report;
                }
            }
        }

    }

    /**
     * Sends a binding request to the specified server address. Both change IP
     * and change port flags are set to false.
     * @param serverAddress the address where to send the bindingRequest.
     * @return The returned message encapsulating event or null if no message
     * was received.
     *
     * @throws StunException if an exception occurs while sending the messge
     * @throws IOException if an error occurs while sending bytes through
     * the socket.
     */
    private StunMessageEvent doTestI(TransportAddress serverAddress)
        throws IOException, StunException
    {
        Request request = MessageFactory.createBindingRequest();

/*
        ChangeRequestAttribute changeRequest
            = (ChangeRequestAttribute)request
                .getAttribute(Attribute.CHANGE_REQUEST);
        changeRequest.setChangeIpFlag(false);
        changeRequest.setChangePortFlag(false);
*/
        /* add a change request attribute */
        ChangeRequestAttribute changeRequest
            = AttributeFactory.createChangeRequestAttribute();
        changeRequest.setChangeIpFlag(false);
        changeRequest.setChangePortFlag(false);
        request.putAttribute(changeRequest);

        StunMessageEvent evt = null;
        try
        {
            evt = requestSender.sendRequestAndWaitForResponse(
                    request, serverAddress);
        }
        catch (StunException ex)
        {
            //this shouldn't happen since we are the ones that created the
            //request
            logger.log(Level.SEVERE,
                       "Internal Error. Failed to encode a message",
                       ex);
            return null;
        }

        if(evt != null)
            logger.fine("TEST I res="+evt.getRemoteAddress().toString()
                               +" - "+ evt.getRemoteAddress().getHostAddress());
        else
            logger.fine("NO RESPONSE received to TEST I.");
        return evt;
    }

    /**
     * Sends a binding request to the specified server address with both change
     * IP and change port flags are set to true.
     * @param serverAddress the address where to send the bindingRequest.
     * @return The returned message encapsulating event or null if no message
     * was received.
     *
     * @throws StunException if an exception occurs while sending the messge
     * @throws IOException if an exception occurs while executing the algorithm.
     */
    private StunMessageEvent doTestII(TransportAddress serverAddress)
        throws StunException, IOException
    {
        Request request = MessageFactory.createBindingRequest();

        /* ChangeRequestAttribute changeRequest
         *  = (ChangeRequestAttribute)request
         *   .getAttribute(Attribute.CHANGE_REQUEST); */
        /* add a change request attribute */
        ChangeRequestAttribute changeRequest = AttributeFactory.createChangeRequestAttribute();
        changeRequest.setChangeIpFlag(true);
        changeRequest.setChangePortFlag(true);
        request.putAttribute(changeRequest);

        StunMessageEvent evt
            = requestSender.sendRequestAndWaitForResponse(request,
                                                          serverAddress);
        if(evt != null)
            logger.fine("Test II res="+evt.getRemoteAddress().toString()
                            +" - "+ evt.getRemoteAddress().getHostAddress());
        else
            logger.fine("NO RESPONSE received to Test II.");

        return evt;
    }

    /**
     * Sends a binding request to the specified server address with only change
     * port flag set to true and change IP flag - to false.
     * @param serverAddress the address where to send the bindingRequest.
     * @return The returned message encapsulating event or null if no message
     * was received.
     * @throws StunException if an exception occurs while sending the messge
     * @throws IOException if an exception occurs while sending bytes through
     * the socket.
     */
    private StunMessageEvent doTestIII(TransportAddress serverAddress)
        throws StunException, IOException
    {
        Request request = MessageFactory.createBindingRequest();

        /* ChangeRequestAttribute changeRequest = (ChangeRequestAttribute)request.getAttribute(Attribute.CHANGE_REQUEST); */
        /* add a change request attribute */
        ChangeRequestAttribute changeRequest = AttributeFactory.createChangeRequestAttribute();
        changeRequest.setChangeIpFlag(false);
        changeRequest.setChangePortFlag(true);
        request.putAttribute(changeRequest);

        StunMessageEvent evt = requestSender.sendRequestAndWaitForResponse(
            request, serverAddress);
        if(evt != null)
            logger.fine("Test III res="+evt.getRemoteAddress().toString()
                            +" - "+ evt.getRemoteAddress().getHostAddress());
        else
            logger.fine("NO RESPONSE received to Test III.");

        return evt;
    }

    /**
     * Makes shure the discoverer is operational and throws an
     * StunException.ILLEGAL_STATE if that is not the case.
     * @throws StunException ILLEGAL_STATE if the discoverer is not operational.
     */
    private void checkStarted()
        throws StunException
    {
        if(!started)
            throw new StunException(StunException.ILLEGAL_STATE,
                                    "The Discoverer must be started before "
                                    +"launching the discovery process!");
    }

    //---------- main
    /**
     * Runs the discoverer and shows a message dialog with the returned report.
     * @param args args[0] - stun server address, args[1] - port. in the case of
     * no args - defaults are provided.
     * @throws java.lang.Exception if an exception occurrs during the discovery
     * process.
     */
/*
    public static void main(String[] args)
        throws Exception
    {
        StunAddress localAddr = null;
        StunAddress serverAddr = null;
        if(args.length == 4)
        {
            localAddr = new StunAddress(args[2],
                                        Integer.valueOf(args[3]).intValue());
            serverAddr = new StunAddress(args[0],
                                         Integer.valueOf(args[1]).intValue());
        }
        else
        {
            localAddr = new StunAddress(InetAddress.getLocalHost(), 5678);
            serverAddr = new StunAddress("stun01bak.sipphone.com.", 3479);
        }
        NetworkConfigurationDiscoveryProcess addressDiscovery
            = new NetworkConfigurationDiscoveryProcess(localAddr, serverAddr);

        addressDiscovery.start();
        StunDiscoveryReport report = addressDiscovery.determineAddress();
        System.out.println(report);
    }
*/
}
/**
 * Sample run results.
 *
 * TEST I res=/69.0.209.22:3478 - stun01bak.sipphone.com
 * mapped address is=193.108.24.226./193.108.24.226:5678,  name=193.108.24.226.
 * backup server address is=69.0.208.27./69.0.208.27:3478, name=69.0.208.27.
 * NO RESPONSE received to Test II.
 * TEST I res=/69.0.208.27:3478 - stun01.sipphone.com
 * NO RESPONSE received to Test III.
 * The detected network configuration is: Port Restricted Cone NAT
 * Your mapped public address is: 193.108.24.226./193.
 */
