/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice;

import org.ice4j.*;
import org.ice4j.socket.*;

import org.bitlet.weupnp.*;

/**
 * Represents a <tt>Candidate</tt> obtained via UPnP.
 *
 * @author Sebastien Vincent
 */
public class UPNPCandidate
    extends LocalCandidate
{
    /**
     * The UPnP gateway device.
     */
    private GatewayDevice device = null;

    /**
     * Creates a <tt>UPNPCandidate</tt> for the specified transport, address,
     * and base.
     *
     * @param transportAddress  the transport address that this candidate is
     * encapsulating.
     * @param base the base candidate
     * @param parentComponent the <tt>Component</tt> that this candidate
     * belongs to.
     * @param device the UPnP gateway device
     */
    public UPNPCandidate(TransportAddress transportAddress,
            LocalCandidate base, Component parentComponent,
            GatewayDevice device)
    {
        super(  transportAddress,
                parentComponent,
                CandidateType.SERVER_REFLEXIVE_CANDIDATE,
                CandidateExtendedType.UPNP_CANDIDATE,
                base);

        this.setBase(base);
        this.device = device;
        setStunServerAddress(transportAddress);
    }

    /**
     * Frees resources allocated by this candidate such as its
     * <tt>DatagramSocket</tt>, for example. The <tt>socket</tt> of this
     * <tt>LocalCandidate</tt> is closed only if it is not the <tt>socket</tt>
     * of the <tt>base</tt> of this <tt>LocalCandidate</tt>.
     */
    @Override
    protected void free()
    {
        // delete the port mapping
        try
        {
            device.deletePortMapping(getTransportAddress().getPort(), "UDP");
        }
        catch(Exception e)
        {
        }

        IceSocketWrapper socket = getIceSocketWrapper();
        if(socket != null)
        {
            socket.close();
        }

        device = null;
    }

    /**
     * Gets the <tt>DatagramSocket</tt> associated with this <tt>Candidate</tt>.
     *
     * @return the <tt>DatagramSocket</tt> associated with this
     * <tt>Candidate</tt>
     */
    @Override
    public IceSocketWrapper getIceSocketWrapper()
    {
        return getBase().getIceSocketWrapper();
    }
}
