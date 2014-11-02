/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.lang.reflect.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * Represents the harvesting of TURN <tt>Candidates</tt> for a specific
 * <tt>HostCandidate</tt> performed by a specific
 * <tt>TurnCandidateHarvester</tt>.
 *
 * @author Lyubomir Marinov
 */
public class TurnCandidateHarvest
    extends StunCandidateHarvest
{

    /**
     * The <tt>Logger</tt> used by the <tt>TurnCandidateHarvest</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(TurnCandidateHarvest.class.getName());

    /**
     * The <tt>Request</tt> created by the last call to
     * {@link #createRequestToStartResolvingCandidate()}.
     */
    private Request requestToStartResolvingCandidate;

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
     */
    public TurnCandidateHarvest(
            TurnCandidateHarvester harvester,
            HostCandidate hostCandidate)
    {
        super(harvester, hostCandidate);
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
    public void close(RelayedCandidateDatagramSocket relayedCandidateSocket)
    {
        /*
         * FIXME As far as logic goes, it seems that it is possible to send a
         * TURN Refresh, cancel the STUN keep-alive functionality here and only
         * then receive the response to the TURN Refresh which will enable the
         * STUN keep-alive functionality again.
         */
        setSendKeepAliveMessageInterval(
                SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED);

        /*
         * TURN Refresh with a LIFETIME value equal to zero deletes the TURN
         * Allocation.
         */
        try
        {
            sendRequest(MessageFactory.createRefreshRequest(0), false, null);
        }
        catch (StunException sex)
        {
            logger.log(
                    Level.INFO,
                    "Failed to send TURN Refresh request to delete Allocation",
                    sex);
        }
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
        /*
         * TODO If the Allocate request is rejected because the server lacks
         * resources to fulfill it, the agent SHOULD instead send a Binding
         * request to obtain a server reflexive candidate.
         */
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

        // Let the super create the ServerReflexiveCandidate.
        super.createCandidates(response);
    }

    /**
     * Creates a new STUN <tt>Message</tt> to be sent to the STUN server
     * associated with the <tt>StunCandidateHarvester</tt> of this instance in
     * order to keep a specific <tt>LocalCandidate</tt> (harvested by this
     * instance) alive.
     *
     * @param candidate the <tt>LocalCandidate</tt> (harvested by this instance)
     * to create a new keep-alive STUN message for
     * @return a new keep-alive STUN <tt>Message</tt> for the specified
     * <tt>candidate</tt> or <tt>null</tt> if no keep-alive sending is to occur
     * @throws StunException if anything goes wrong while creating the new
     * keep-alive STUN <tt>Message</tt> for the specified <tt>candidate</tt>
     * or the candidate is of an unsupported <tt>CandidateType</tt>
     * @see StunCandidateHarvest#createKeepAliveMessage(LocalCandidate)
     */
    @Override
    protected Message createKeepAliveMessage(LocalCandidate candidate)
        throws StunException
    {
        switch (candidate.getType())
        {
        case RELAYED_CANDIDATE:
            return MessageFactory.createRefreshRequest();
        case SERVER_REFLEXIVE_CANDIDATE:
            /*
             * RFC 5245: The Refresh requests will also refresh the server
             * reflexive candidate.
             */
            boolean existsRelayedCandidate = false;

            for (Candidate<?> aCandidate : getCandidates())
            {
                if (CandidateType.RELAYED_CANDIDATE.equals(
                        aCandidate.getType()))
                {
                    existsRelayedCandidate = true;
                    break;
                }
            }
            return
                existsRelayedCandidate
                    ? null
                    : super.createKeepAliveMessage(candidate);
        default:
            return super.createKeepAliveMessage(candidate);
        }
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
            = response.getAttribute(Attribute.XOR_RELAYED_ADDRESS);

        if (attribute instanceof XorRelayedAddressAttribute)
        {
            TransportAddress relayedAddress
                = ((XorRelayedAddressAttribute) attribute).getAddress(
                        response.getTransactionID());
            RelayedCandidate relayedCandidate
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
    protected RelayedCandidate createRelayedCandidate(
            TransportAddress transportAddress,
            TransportAddress mappedAddress)
    {
        return
            new RelayedCandidate(
                    transportAddress,
                    this,
                    mappedAddress);
    }

    /**
     * Creates a new <tt>Request</tt> instance which is to be sent by this
     * <tt>StunCandidateHarvest</tt> in order to retry a specific
     * <tt>Request</tt>. For example, the long-term credential mechanism
     * dictates that a <tt>Request</tt> is first sent by the client without any
     * credential-related attributes, then it gets challenged by the server and
     * the client retries the original <tt>Request</tt> with the appropriate
     * credential-related attributes in response.
     *
     * @param request the <tt>Request</tt> which is to be retried by this
     * <tt>StunCandidateHarvest</tt>
     * @return the new <tt>Request</tt> instance which is to be sent by this
     * <tt>StunCandidateHarvest</tt> in order to retry the specified
     * <tt>request</tt>
     * @see StunCandidateHarvest#createRequestToRetry(Request)
     */
    @Override
    protected Request createRequestToRetry(Request request)
    {
        switch (request.getMessageType())
        {
        case Message.ALLOCATE_REQUEST:
        {
            RequestedTransportAttribute requestedTransportAttribute
                = (RequestedTransportAttribute)
                    request.getAttribute(Attribute.REQUESTED_TRANSPORT);
            int requestedTransport
                = (requestedTransportAttribute == null)
                    ? 17 /* User Datagram Protocol */
                    : requestedTransportAttribute.getRequestedTransport();
            EvenPortAttribute evenPortAttribute
                = (EvenPortAttribute) request.getAttribute(Attribute.EVEN_PORT);
            boolean rFlag
                = (evenPortAttribute == null)
                    ? false
                    : evenPortAttribute.isRFlag();

            return
                MessageFactory.createAllocateRequest(
                        (byte) requestedTransport,
                        rFlag);
        }

        case Message.CHANNELBIND_REQUEST:
        {
            ChannelNumberAttribute channelNumberAttribute
                = (ChannelNumberAttribute)
                    request.getAttribute(Attribute.CHANNEL_NUMBER);
            char channelNumber = channelNumberAttribute.getChannelNumber();
            XorPeerAddressAttribute peerAddressAttribute
                = (XorPeerAddressAttribute)
                    request.getAttribute(Attribute.XOR_PEER_ADDRESS);
            TransportAddress peerAddress
                = peerAddressAttribute.getAddress(request.getTransactionID());
            byte[] retryTransactionID
                = TransactionID.createNewTransactionID().getBytes();
            Request retryChannelBindRequest
                = MessageFactory.createChannelBindRequest(
                        channelNumber,
                        peerAddress,
                        retryTransactionID);

            try
            {
                retryChannelBindRequest.setTransactionID(retryTransactionID);
            }
            catch (StunException sex)
            {
                throw new UndeclaredThrowableException(sex);
            }
            return retryChannelBindRequest;
        }

        case Message.CREATEPERMISSION_REQUEST:
        {
            XorPeerAddressAttribute peerAddressAttribute
                = (XorPeerAddressAttribute)
                    request.getAttribute(Attribute.XOR_PEER_ADDRESS);
            TransportAddress peerAddress
                = peerAddressAttribute.getAddress(request.getTransactionID());
            byte[] retryTransactionID
                = TransactionID.createNewTransactionID().getBytes();
            Request retryCreatePermissionRequest
                = MessageFactory.createCreatePermissionRequest(
                        peerAddress,
                        retryTransactionID);

            try
            {
                retryCreatePermissionRequest.setTransactionID(
                        retryTransactionID);
            }
            catch (StunException sex)
            {
                throw new UndeclaredThrowableException(sex);
            }
            return retryCreatePermissionRequest;
        }

        case Message.REFRESH_REQUEST:
        {
            LifetimeAttribute lifetimeAttribute
                = (LifetimeAttribute) request.getAttribute(Attribute.LIFETIME);

            if (lifetimeAttribute == null)
                return MessageFactory.createRefreshRequest();
            else
            {
                return
                    MessageFactory.createRefreshRequest(
                            lifetimeAttribute.getLifetime());
            }
        }

        default:
            return super.createRequestToRetry(request);
        }
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
                = MessageFactory.createAllocateRequest(
                        (byte) 17 /* User Datagram Protocol */,
                        false);
            return requestToStartResolvingCandidate;
        }
        else if (requestToStartResolvingCandidate.getMessageType()
                == Message.ALLOCATE_REQUEST)
        {
            requestToStartResolvingCandidate
                = super.createRequestToStartResolvingCandidate();
            return requestToStartResolvingCandidate;
        }
        else
            return null;
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

        /*
         * TurnCandidateHarvest uses the applicationData of TransactionID to
         * deliver the results of Requests sent by
         * RelayedCandidateDatagramSocket back to it.
         */
        Object applicationData = transactionID.getApplicationData();

        if ((applicationData instanceof RelayedCandidateDatagramSocket)
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
            lifetimeAttribute
                = (LifetimeAttribute) response.getAttribute(Attribute.LIFETIME);
            lifetime
                = (lifetimeAttribute == null)
                    ? (10 * 60)
                    : lifetimeAttribute.getLifetime();
            break;
        case Message.REFRESH_RESPONSE:
            lifetimeAttribute
                = (LifetimeAttribute) response.getAttribute(Attribute.LIFETIME);
            if (lifetimeAttribute != null)
                lifetime = lifetimeAttribute.getLifetime();
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

        if (applicationData instanceof RelayedCandidateDatagramSocket)
        {
            ((RelayedCandidateDatagramSocket) applicationData)
                .processSuccess(response, request);
        }
    }

    /**
     * Sends a specific <tt>Request</tt> on behalf of a specific
     * <tt>RelayedCandidateDatagramSocket</tt> to the TURN server associated
     * with this <tt>TurnCandidateHarvest</tt>.
     *
     * @param relayedCandidateDatagramSocket the
     * <tt>RelayedCandidateDatagramSocket</tt> which sends the specified
     * <tt>Request</tt> and which is to be notified of the result
     * @param request the <tt>Request</tt> to be sent to the TURN server
     * associated with this <tt>TurnCandidateHarvest</tt>
     * @return an array of <tt>byte</tt>s which represents the ID of the
     * transaction with which the specified <tt>Request</tt> has been sent to
     * the TURN server
     * @throws StunException if anything goes wrong while sending the specified
     * <tt>Request</tt>
     */
    public byte[] sendRequest(
            RelayedCandidateDatagramSocket relayedCandidateDatagramSocket,
            Request request)
        throws StunException
    {
        TransactionID transactionID = TransactionID.createNewTransactionID();

        transactionID.setApplicationData(relayedCandidateDatagramSocket);
        transactionID = sendRequest(request, false, transactionID);
        return (transactionID == null) ? null : transactionID.getBytes();
    }
}
