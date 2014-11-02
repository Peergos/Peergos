/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * Represents the harvesting of Google TURN <tt>Candidates</tt> for a specific
 * <tt>HostCandidate</tt> performed by a specific
 * <tt>GoogleTurnCandidateHarvester</tt>.
 *
 * @author Lyubomir Marinov
 * @author Sebastien Vincent
 */
public class GoogleTurnCandidateHarvest
    extends StunCandidateHarvest
{
    /**
     * The <tt>Logger</tt> used by the <tt>TurnCandidateHarvest</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(GoogleTurnCandidateHarvest.class.getName());

    /**
     * The <tt>Request</tt> created by the last call to
     * {@link #createRequestToStartResolvingCandidate()}.
     */
    private Request requestToStartResolvingCandidate;

    /**
     * The gingle candidates password necessary to use the TURN server.
     */
    private String password;

    /**
     * Initializes a new <tt>TurnCandidateHarvest</tt> which is to represent the
     * harvesting of TURN <tt>Candidate</tt>s for a specific
     * <tt>HostCandidate</tt> performed by a specific
     * <tt>TurnCandidateHarvester</tt>.
     *
     * @param harvester the <tt>TurnCandidateHarvester</tt> which is performing
     * the TURN harvesting
     * @param hostCandidate the <tt>HostCandidate</tt> for which TURN
     * <tt>Candidate</tt>s are to be harvested
     * @param password The gingle candidates password necessary to use this TURN
     * server.
     */
    public GoogleTurnCandidateHarvest(
            GoogleTurnCandidateHarvester harvester,
            HostCandidate hostCandidate,
            String password)
    {
        super(harvester, hostCandidate);
        this.password = password;
    }

    /**
     * Creates new <tt>Candidate</tt>s determined by a specific STUN
     * <tt>Response</tt>.
     *
     * @param response the received STUN <tt>Response</tt>
     * @see StunCandidateHarvest#createCandidates(Response)
     */
    @Override
    protected void createCandidates(Response response)
    {
        createRelayedCandidate(response);
    }

    /**
     * Creates a <tt>RelayedCandidate</tt> using the
     * <tt>XOR-RELAYED-ADDRESS</tt> attribute in a specific STUN
     * <tt>Response</tt> for the actual <tt>TransportAddress</tt> of the new
     * candidate. If the message is malformed and/or does not contain the
     * corresponding attribute, this method simply has no effect.
     *
     * @param response the STUN <tt>Response</tt> which is supposed to contain
     * the address we should use for the new candidate
     */
    private void createRelayedCandidate(Response response)
    {
        Attribute attribute
            = response.getAttribute(Attribute.MAPPED_ADDRESS);

        if(attribute != null)
        {
            TransportAddress relayedAddress
                = ((MappedAddressAttribute) attribute).getAddress();

            if (harvester.stunServer.getTransport() == Transport.TCP)
            {
                relayedAddress = new TransportAddress(
                    relayedAddress.getAddress(),
                    harvester.stunServer.getPort(),
                    //relayedAddress.getPort() - 1,
                    Transport.TCP);
            }
            GoogleRelayedCandidate relayedCandidate
                = createRelayedCandidate(
                        relayedAddress,
                        getMappedAddress(response));

            if (relayedCandidate != null)
            {
                /*
                 * The ICE connectivity checks will utilize STUN on the
                 * (application-purposed) socket of the RelayedCandidate and
                 * will not add it to the StunStack so we have to do it.
                 */
                harvester.getStunStack().addSocket(
                        relayedCandidate.getStunSocket(null));

                addCandidate(relayedCandidate);
            }
        }
    }

    /**
     * Creates a new <tt>RelayedCandidate</tt> instance which is to represent a
     * specific <tt>TransportAddress</tt> harvested through
     * {@link #hostCandidate} and the TURN server associated with
     * {@link #harvester}.
     *
     * @param transportAddress the <tt>TransportAddress</tt> to be represented
     * by the new <tt>RelayedCandidate</tt> instance
     * @param mappedAddress the mapped <tt>TransportAddress</tt> reported by the
     * TURN server with the delivery of the relayed <tt>transportAddress</tt> to
     * be represented by the new <tt>RelayedCandidate</tt> instance
     * @return a new <tt>RelayedCandidate</tt> instance which represents the
     * specified <tt>TransportAddress</tt> harvested through
     * {@link #hostCandidate} and the TURN server associated with
     * {@link #harvester}
     */
    protected GoogleRelayedCandidate createRelayedCandidate(
            TransportAddress transportAddress,
            TransportAddress mappedAddress)
    {
        GoogleRelayedCandidate candidate =
            new GoogleRelayedCandidate(
                    transportAddress,
                    this,
                    mappedAddress,
                    harvester.getShortTermCredentialUsername(),
                    this.password);

        candidate.setUfrag(harvester.getShortTermCredentialUsername());
        return candidate;
    }

    /**
     * Creates a new <tt>Request</tt> which is to be sent to
     * {@link TurnCandidateHarvester#stunServer} in order to start resolving
     * {@link #hostCandidate}.
     *
     * @return a new <tt>Request</tt> which is to be sent to
     * {@link TurnCandidateHarvester#stunServer} in order to start resolving
     * {@link #hostCandidate}
     * @see StunCandidateHarvest#createRequestToStartResolvingCandidate()
     */
    @Override
    protected Request createRequestToStartResolvingCandidate()
    {
        if (requestToStartResolvingCandidate == null)
        {
            requestToStartResolvingCandidate
                = MessageFactory.createGoogleAllocateRequest(
                        harvester.getShortTermCredentialUsername());

            return requestToStartResolvingCandidate;
        }
        else
            return null;
    }

    /**
     * Adds the <tt>Attribute</tt>s to a specific <tt>Request</tt> which support
     * the STUN short-term credential mechanism if the mechanism in question is
     * utilized by this <tt>StunCandidateHarvest</tt> (i.e. by the associated
     * <tt>StunCandidateHarvester</tt>).
     *
     * @param request the <tt>Request</tt> to which to add the
     * <tt>Attribute</tt>s supporting the STUN short-term credential mechanism
     * if the mechanism in question is utilized by this
     * <tt>StunCandidateHarvest</tt>
     * @return <tt>true</tt> if the STUN short-term credential mechanism is
     * actually utilized by this <tt>StunCandidateHarvest</tt> for the specified
     * <tt>request</tt>; otherwise, <tt>false</tt>
     */
    @Override
    protected boolean addShortTermCredentialAttributes(Request request)
    {
        return false;
    }

    /**
     * Completes the harvesting of <tt>Candidate</tt>s for
     * {@link #hostCandidate}. Notifies {@link #harvester} about the completion
     * of the harvesting of <tt>Candidate</tt> for <tt>hostCandidate</tt>
     * performed by this <tt>StunCandidateHarvest</tt>.
     *
     * @param request the <tt>Request</tt> sent by this
     * <tt>StunCandidateHarvest</tt> with which the harvesting of
     * <tt>Candidate</tt>s for <tt>hostCandidate</tt> has completed
     * @param response the <tt>Response</tt> received by this
     * <tt>StunCandidateHarvest</tt>, if any, with which the harvesting of
     * <tt>Candidate</tt>s for <tt>hostCandidate</tt> has completed
     * @return <tt>true</tt> if the harvesting of <tt>Candidate</tt>s for
     * <tt>hostCandidate</tt> performed by this <tt>StunCandidateHarvest</tt>
     * has completed; otherwise, <tt>false</tt>
     * @see StunCandidateHarvest#completedResolvingCandidate(Request, Response)
     */
    @Override
    protected boolean completedResolvingCandidate(
            Request request,
            Response response)
    {
        if ((response == null)
                || (!response.isSuccessResponse()
                        && (request.getMessageType()
                                == Message.ALLOCATE_REQUEST)))
        {
            try
            {
                if (startResolvingCandidate())
                    return false;
            }
            catch (Exception ex)
            {
                /*
                 * Complete the harvesting of Candidates for hostCandidate
                 * because the new attempt has just failed.
                 */
            }
        }
        return super.completedResolvingCandidate(request, response);
    }

    /**
     * Notifies this <tt>TurnCandidateHarvest</tt> that a specific
     * <tt>RelayedCandidateDatagramSocket</tt> is closing and that this instance
     * is to delete the associated TURN Allocation.
     * <p>
     * <b>Note</b>: The method is part of the internal API of
     * <tt>RelayedCandidateDatagramSocket</tt> and <tt>TurnCandidateHarvest</tt>
     * and is not intended for public use.
     * </p>
     *
     * @param relayedCandidateSocket the <tt>RelayedCandidateDatagramSocket</tt>
     * which notifies this instance and which requests that the associated TURN
     * Allocation be deleted
     */
    public void close(
            GoogleRelayedCandidateDatagramSocket relayedCandidateSocket)
    {
        /*
         * FIXME As far as logic goes, it seems that it is possible to send a
         * TURN Refresh, cancel the STUN keep-alive functionality here and only
         * then receive the response to the TURN Refresh which will enable the
         * STUN keep-alive functionality again.
         */
        setSendKeepAliveMessageInterval(
                SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED);
    }

    /**
     * Notifies this <tt>StunCandidateHarvest</tt> that a specific
     * <tt>Request</tt> has either received an error <tt>Response</tt> or has
     * failed to receive any <tt>Response</tt>.
     *
     * @param response the error <tt>Response</tt> which has been received for
     * <tt>request</tt>
     * @param request the <tt>Request</tt> to which <tt>Response</tt> responds
     * @param transactionID the <tt>TransactionID</tt> of <tt>response</tt> and
     * <tt>request</tt> because <tt>response</tt> and <tt>request</tt> only have
     * it as a <tt>byte</tt> array and <tt>TransactionID</tt> is required for
     * the <tt>applicationData</tt> property value
     * @return <tt>true</tt> if the error or failure condition has been
     * processed and this instance can continue its execution (e.g. the
     * resolution of the candidate) as if it was expected; otherwise,
     * <tt>false</tt>
     * @see StunCandidateHarvest#processErrorOrFailure(Response, Request,
     * TransactionID)
     */
    @Override
    protected boolean processErrorOrFailure(
            Response response,
            Request request,
            TransactionID transactionID)
    {
        logger.info("Google TURN processErrorOrFailure");
        /*
         * TurnCandidateHarvest uses the applicationData of TransactionID to
         * deliver the results of Requests sent by
         * RelayedCandidateDatagramSocket back to it.
         */
        Object applicationData = transactionID.getApplicationData();

        if ((applicationData instanceof GoogleRelayedCandidateDatagramSocket)
                && ((RelayedCandidateDatagramSocket) applicationData)
                        .processErrorOrFailure(response, request))
            return true;
        else if ((applicationData instanceof
            GoogleRelayedCandidateDatagramSocket)
            && ((RelayedCandidateDatagramSocket) applicationData)
                    .processErrorOrFailure(response, request))
        return true;

        return super.processErrorOrFailure(response, request, transactionID);
    }

    /**
     * Handles a specific STUN success <tt>Response</tt> to a specific STUN
     * <tt>Request</tt>.
     *
     * @param response the received STUN success <tt>Response</tt> which is to
     * be handled
     * @param request the STUN <tt>Request</tt> to which <tt>response</tt>
     * responds
     * @param transactionID the <tt>TransactionID</tt> of <tt>response</tt> and
     * <tt>request</tt> because <tt>response</tt> and <tt>request</tt> only have
     * it as a <tt>byte</tt> array and <tt>TransactionID</tt> is required for
     * the <tt>applicationData</tt> property value
     * @see StunCandidateHarvest#processSuccess(Response, Request,
     * TransactionID)
     */
    @Override
    protected void processSuccess(
            Response response,
            Request request,
            TransactionID transactionID)
    {
        super.processSuccess(response, request, transactionID);

        LifetimeAttribute lifetimeAttribute;
        int lifetime /* minutes */ = -1;

        switch (response.getMessageType())
        {
        case Message.ALLOCATE_RESPONSE:
            // The default lifetime of an allocation is 10 minutes.
            // The default lifetime of an allocation is 10 minutes.
            lifetimeAttribute
                = (LifetimeAttribute) response.getAttribute(Attribute.LIFETIME);
            lifetime
                = (lifetimeAttribute == null)
                    ? (10 * 60)
                    : lifetimeAttribute.getLifetime();
            logger.info("Successful Google TURN allocate");
            break;
        default:
            break;
        }

        if (lifetime >= 0)
        {
            setSendKeepAliveMessageInterval(
                    /* milliseconds */ 1000L * lifetime);
        }

        /*
         * TurnCandidateHarvest uses the applicationData of TransactionID to
         * deliver the results of Requests sent by
         * RelayedCandidateDatagramSocket back to it.
         */
        Object applicationData = transactionID.getApplicationData();

        if (applicationData instanceof GoogleRelayedCandidateDatagramSocket)
        {
            ((GoogleRelayedCandidateDatagramSocket) applicationData)
                .processSuccess(response, request);
        }
        else if (applicationData instanceof GoogleRelayedCandidateSocket)
        {
            ((GoogleRelayedCandidateSocket) applicationData)
                .processSuccess(response, request);
        }
    }
}
