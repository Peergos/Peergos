/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.ice.*;
import org.ice4j.socket.*;

import org.bitlet.weupnp.*;

/**
 * Implements a <tt>CandidateHarvester</tt> which gathers <tt>Candidate</tt>s
 * for a specified {@link Component} using UPnP.
 *
 * @author Sebastien Vincent
 */
public class UPNPHarvester
    extends CandidateHarvester
{
    /**
     * The logger.
     */
    private static final Logger logger =
        Logger.getLogger(UPNPHarvester.class.getName());

    /**
     * Maximum port to try to allocate.
     */
    private static final int MAX_RETRIES = 5;

    /**
     * ST search field for WANIPConnection.
     */
    private static final String stIP =
        "urn:schemas-upnp-org:service:WANIPConnection:1";

    /**
     * ST search field for WANPPPConnection.
     */
    private static final String stPPP =
        "urn:schemas-upnp-org:service:WANPPPConnection:1";

    /**
     * Synchronization object.
     */
    private final Object rootSync = new Object();

    /**
     * Gateway device.
     */
    private GatewayDevice device = null;

    /**
     * Number of UPnP discover threads that have finished.
     */
    private int finishThreads = 0;

    /**
     * Gathers UPnP candidates for all host <tt>Candidate</tt>s that are
     * already present in the specified <tt>component</tt>. This method relies
     * on the specified <tt>component</tt> to already contain all its host
     * candidates so that it would resolve them.
     *
     * @param component the {@link Component} that we'd like to gather candidate
     * UPnP <tt>Candidate</tt>s for
     * @return  the <tt>LocalCandidate</tt>s gathered by this
     * <tt>CandidateHarvester</tt>
     */
    public synchronized Collection<LocalCandidate> harvest(Component component)
    {
        Collection<LocalCandidate> candidates = new HashSet<LocalCandidate>();
        int retries = 0;

        logger.fine("Begin UPnP harvesting");
        try
        {
            if(device == null)
            {
                // do it only once
                if(finishThreads == 0)
                {
                    try
                    {
                        UPNPThread wanIPThread = new UPNPThread(stIP);
                        UPNPThread wanPPPThread = new UPNPThread(stPPP);

                        wanIPThread.start();
                        wanPPPThread.start();

                        synchronized(rootSync)
                        {
                            while(finishThreads != 2)
                            {
                                rootSync.wait();
                            }
                        }

                        if(wanIPThread.getDevice() != null)
                        {
                            device = wanIPThread.getDevice();
                        }
                        else if(wanPPPThread.getDevice() != null)
                        {
                            device = wanPPPThread.getDevice();
                        }

                    }
                    catch(Throwable e)
                    {
                        logger.info("UPnP discovery failed: " + e);
                    }
                }

                if(device == null)
                    return candidates;
            }

            InetAddress localAddress = device.getLocalAddress();
            String externalIPAddress = device.getExternalIPAddress();
            PortMappingEntry portMapping = new PortMappingEntry();

            IceSocketWrapper socket = new IceUdpSocketWrapper(
                new MultiplexingDatagramSocket(0, localAddress));
            int port = socket.getLocalPort();
            int externalPort = socket.getLocalPort();

            while(retries < MAX_RETRIES)
            {
                if(!device.getSpecificPortMappingEntry(port, "UDP",
                        portMapping))
                {
                    if(device.addPortMapping(
                            externalPort,
                            port,
                            localAddress.getHostAddress(),
                            "UDP",
                            "ice4j.org: " + port))
                    {
                        List<LocalCandidate> cands = createUPNPCandidate(socket,
                            externalIPAddress, externalPort, component, device);

                        logger.info("Add UPnP port mapping: " +
                                externalIPAddress + " " + externalPort);

                        // we have to add the UPNPCandidate and also the base.
                        // if we don't add the base, we won't be able to add
                        // peer reflexive candidate if someone contact us on the
                        // UPNPCandidate
                        for(LocalCandidate cand : cands)
                        {
                            //try to add the candidate to the component and then
                            //only add it to the harvest not redundant
                            if(component.addLocalCandidate(cand))
                            {
                                candidates.add(cand);
                            }
                        }

                        break;
                    }
                    else
                    {
                        port++;
                    }
                }
                else
                {
                    port++;
                }
                retries++;
            }
        }
        catch(Throwable e)
        {
            logger.info("Exception while gathering UPnP candidates: " + e);
        }

        return candidates;
    }

    /**
     * Create a UPnP candidate.
     *
     * @param socket local socket
     * @param externalIP external IP address
     * @param port local port
     * @param cmp parent component
     * @param device the UPnP gateway device
     * @return a new <tt>UPNPCandidate</tt> instance which
     * represents the specified <tt>TransportAddress</tt>
     * @throws Exception if something goes wrong during candidate creation
     */
    public List<LocalCandidate> createUPNPCandidate(IceSocketWrapper socket,
            String externalIP, int port, Component cmp, GatewayDevice device)
        throws Exception
    {
        List<LocalCandidate> ret = new ArrayList<LocalCandidate>();
        TransportAddress addr = new TransportAddress(externalIP, port,
                Transport.UDP);

        HostCandidate base = new HostCandidate(socket, cmp);

        UPNPCandidate candidate = new UPNPCandidate(addr, base, cmp, device);
        IceSocketWrapper stunSocket = candidate.getStunSocket(null);
        candidate.getStunStack().addSocket(stunSocket);

        ret.add(candidate);
        ret.add(base);

        return ret;
    }

    /**
     * UPnP discover thread.
     */
    private class UPNPThread
        extends Thread
    {
        /**
         * Gateway device.
         */
        private GatewayDevice device = null;

        /**
         * ST search field.
         */
        private final String st;

        /**
         * Constructor.
         *
         * @param st ST search field
         */
        public UPNPThread(String st)
        {
            this.st = st;
        }

        /**
         * Returns gateway device.
         *
         * @return gateway device
         */
        public GatewayDevice getDevice()
        {
            return device;
        }

        /**
         * Thread Entry point.
         */
        public void run()
        {
            try
            {
                GatewayDiscover gd = new GatewayDiscover(st);

                gd.discover();

                if(gd.getValidGateway() != null)
                {
                    device = gd.getValidGateway();
                }
            }
            catch(Throwable e)
            {
                logger.info("Failed to harvest UPnP: " + e);

                /*
                 * The Javadoc on ThreadDeath says: If ThreadDeath is caught by
                 * a method, it is important that it be rethrown so that the
                 * thread actually dies.
                 */
                if(e instanceof ThreadDeath)
                    throw (ThreadDeath)e;
            }
            finally
            {
                synchronized(rootSync)
                {
                    finishThreads++;
                    rootSync.notify();
                }
            }
        }
    }

    /**
     * Returns a <tt>String</tt> representation of this harvester containing its
     * name.
     *
     * @return a <tt>String</tt> representation of this harvester containing its
     * name.
     */
    @Override
    public String toString()
    {
        return getClass().getSimpleName();
    }
}
