/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.io.*;
import java.lang.ref.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.*;
import org.ice4j.message.*;
import org.ice4j.security.*;
import org.ice4j.stack.*;

/**
 * Represents the harvesting of STUN <tt>Candidates</tt> for a specific
 * <tt>HostCandidate</tt> performed by a specific
 * <tt>StunCandidateHarvester</tt>.
 *
 * @author Lyubomir Marinov
 */
public class StunCandidateHarvest
    extends AbstractResponseCollector
{

    /**
     * The <tt>Logger</tt> used by the <tt>StunCandidateHarvest</tt> class and
     * its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(StunCandidateHarvest.class.getName());

    /**
     * The constant which defines an empty array with <tt>LocalCandidate</tt>
     * element type. Explicitly defined in order to reduce unnecessary
     * allocations.
     */
    private static final LocalCandidate[] NO_CANDIDATES = new LocalCandidate[0];

    /**
     * The value of the <tt>sendKeepAliveMessage</tt> property of
     * <tt>StunCandidateHarvest</tt> which specifies that no sending of STUN
     * keep-alive messages is to performed for the purposes of keeping the
     * <tt>Candidate</tt>s harvested by the <tt>StunCandidateHarvester</tt> in
     * question alive.
     */
    protected static final long SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED
        = 0;

    /**
     * The list of <tt>Candidate</tt>s harvested for {@link #hostCandidate} by
     * this harvest.
     */
    private final List<LocalCandidate> candidates
        = new LinkedList<LocalCandidate>();

    /**
     * The indicator which determines whether this <tt>StunCandidateHarvest</tt>
     * has completed the harvesting of <tt>Candidate</tt>s for
     * {@link #hostCandidate}.
     */
    private boolean completedResolvingCandidate = false;

    /**
     * The <tt>StunCandidateHarvester</tt> performing the harvesting of STUN
     * <tt>Candidate</tt>s for a <tt>Component</tt> which this harvest is part
     * of.
     */
    public final StunCandidateHarvester harvester;

    /**
     * The <tt>HostCandidate</tt> the STUN harvesting of which is represented by
     * this instance.
     */
    public final HostCandidate hostCandidate;

    /**
     * The <tt>LongTermCredential</tt> used by this instance.
     */
    private LongTermCredentialSession longTermCredentialSession;

    /**
     * The STUN <tt>Request</tt>s which have been sent by this instance, have
     * not received a STUN <tt>Response</tt> yet and have not timed out. Put in
     * place to avoid a limitation of the <tt>ResponseCollector</tt> and its use
     * of <tt>StunMessageEvent</tt> which do not make the STUN <tt>Request</tt>
     * to which a STUN <tt>Response</tt> responds available though it is known
     * in <tt>StunClientTransaction</tt>.
     */
    private final Map<TransactionID, Request> requests
        = new HashMap<TransactionID, Request>();

    /**
     * The interval in milliseconds at which a new STUN keep-alive message is to
     * be sent to the STUN server associated with the
     * <tt>StunCandidateHarvester</tt> of this instance in order to keep one of
     * the <tt>Candidate</tt>s harvested by this instance alive.
     */
    private long sendKeepAliveMessageInterval
        = SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED;

    /**
     * The <tt>Object</tt> used to synchronize access to the members related to
     * the sending of STUN keep-alive messages to the STUN server associated
     * with the <tt>StunCandidateHarvester</tt> of this instance.
     */
    private final Object sendKeepAliveMessageSyncRoot = new Object();

    /**
     * The <tt>Thread</tt> which sends the STUN keep-alive messages to the STUN
     * server associated with the <tt>StunCandidateHarvester</tt> of this
     * instance in order to keep the <tt>Candidate</tt>s harvested by this
     * instance alive.
     */
    private Thread sendKeepAliveMessageThread;

    /**
     * The time (stamp) in milliseconds of the last call to
     * {@link #sendKeepAliveMessage()} which completed without throwing an
     * exception. <b>Note</b>: It doesn't mean that the keep-alive message was a
     * STUN <tt>Request</tt> and it received a success STUN <tt>Response</tt>.
     */
    private long sendKeepAliveMessageTime = -1;

    /**
     * Initializes a new <tt>StunCandidateHarvest</tt> which is to represent the
     * harvesting of STUN <tt>Candidate</tt>s for a specific
     * <tt>HostCandidate</tt> performed by a specific
     * <tt>StunCandidateHarvester</tt>.
     *
     * @param harvester the <tt>StunCandidateHarvester</tt> which is performing
     * the STUN harvesting
     * @param hostCandidate the <tt>HostCandidate</tt> for which STUN
     * <tt>Candidate</tt>s are to be harvested
     */
    public StunCandidateHarvest(
            StunCandidateHarvester harvester,
            HostCandidate hostCandidate)
    {
        this.harvester = harvester;
        this.hostCandidate = hostCandidate;
    }

    /**
     * Adds a specific <tt>LocalCandidate</tt> to the list of
     * <tt>LocalCandidate</tt>s harvested for {@link #hostCandidate} by this
     * harvest.
     *
     * @param candidate the <tt>LocalCandidate</tt> to be added to the list of
     * <tt>LocalCandidate</tt>s harvested for {@link #hostCandidate} by this
     * harvest
     * @return <tt>true</tt> if the list of <tt>LocalCandidate</tt>s changed as
     * a result of the method invocation; otherwise, <tt>false</tt>
     */
    protected boolean addCandidate(LocalCandidate candidate)
    {
        boolean added;

        //try to add the candidate to the component and then only add it to the
        //harvest if it wasn't deemed redundant
        if (!candidates.contains(candidate)
                && hostCandidate.getParentComponent().addLocalCandidate(
                        candidate))
        {
            added = candidates.add(candidate);
        }
        else
        {
            added = false;
        }
        return added;
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
    protected boolean addShortTermCredentialAttributes(Request request)
    {
        String shortTermCredentialUsername
            = harvester.getShortTermCredentialUsername();

        if (shortTermCredentialUsername != null)
        {
            request.putAttribute(
                    AttributeFactory.createUsernameAttribute(
                            shortTermCredentialUsername));
            request.putAttribute(
                    AttributeFactory.createMessageIntegrityAttribute(
                            shortTermCredentialUsername));
            return true;
        }
        else
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
     */
    protected boolean completedResolvingCandidate(
            Request request,
            Response response)
    {
        if (!completedResolvingCandidate)
        {
            completedResolvingCandidate = true;
            try
            {
                if (((response == null) || !response.isSuccessResponse())
                        && (longTermCredentialSession != null))
                {
                    harvester
                        .getStunStack()
                            .getCredentialsManager()
                                .unregisterAuthority(longTermCredentialSession);
                    longTermCredentialSession = null;
                }
            }
            finally
            {
                harvester.completedResolvingCandidate(this);
            }
        }
        return completedResolvingCandidate;
    }

    /**
     * Determines whether a specific <tt>LocalCandidate</tt> is contained in the
     * list of <tt>LocalCandidate</tt>s harvested for {@link #hostCandidate} by
     * this harvest.
     *
     * @param candidate the <tt>LocalCandidate</tt> to look for in the list of
     * <tt>LocalCandidate</tt>s harvested for {@link #hostCandidate} by this
     * harvest
     * @return <tt>true</tt> if the list of <tt>LocalCandidate</tt>s contains
     * the specified <tt>candidate</tt>; otherwise, <tt>false</tt>
     */
    protected boolean containsCandidate(LocalCandidate candidate)
    {
        if (candidate != null)
        {
            LocalCandidate[] candidates = getCandidates();

            if ((candidates != null) && (candidates.length != 0))
            {
                for (LocalCandidate c : candidates)
                {
                    if (candidate.equals(c))
                        return true;
                }
            }
        }
        return false;
    }

    /**
     * Creates new <tt>Candidate</tt>s determined by a specific STUN
     * <tt>Response</tt>.
     *
     * @param response the received STUN <tt>Response</tt>
     */
    protected void createCandidates(Response response)
    {
        createServerReflexiveCandidate(response);
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
     */
    protected Message createKeepAliveMessage(LocalCandidate candidate)
        throws StunException
    {
        /*
         * We'll not be keeping a STUN Binding alive for now. If we decide to in
         * the future, we'll have to create a Binding Indication and add support
         * for sending it.
         */
        if (CandidateType.SERVER_REFLEXIVE_CANDIDATE.equals(
                candidate.getType()))
            return null;
        else
        {
            throw
                new StunException(StunException.ILLEGAL_ARGUMENT, "candidate");
        }
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
     */
    protected Request createRequestToRetry(Request request)
    {
        switch (request.getMessageType())
        {
        case Message.BINDING_REQUEST:
            return MessageFactory.createBindingRequest();
        default:
            throw new IllegalArgumentException("request.messageType");
        }
    }

    /**
     * Creates a new <tt>Request</tt> which is to be sent to
     * {@link StunCandidateHarvester#stunServer} in order to start resolving
     * {@link #hostCandidate}.
     *
     * @return a new <tt>Request</tt> which is to be sent to
     * {@link StunCandidateHarvester#stunServer} in order to start resolving
     * {@link #hostCandidate}
     */
    protected Request createRequestToStartResolvingCandidate()
    {
        return MessageFactory.createBindingRequest();
    }

    /**
     * Creates and starts the {@link #sendKeepAliveMessageThread} which is to
     * send STUN keep-alive <tt>Message</tt>s to the STUN server associated with
     * the <tt>StunCandidateHarvester</tt> of this instance in order to keep the
     * <tt>Candidate</tt>s harvested by this instance alive.
     */
    private void createSendKeepAliveMessageThread()
    {
        synchronized (sendKeepAliveMessageSyncRoot)
        {
            Thread t = new SendKeepAliveMessageThread(this);
            t.setDaemon(true);
            t.setName(
                    getClass().getName() + ".sendKeepAliveMessageThread: "
                        + hostCandidate);

            boolean started = false;

            sendKeepAliveMessageThread = t;
            try
            {
                t.start();
                started = true;
            }
            finally
            {
                if (!started && (sendKeepAliveMessageThread == t))
                    sendKeepAliveMessageThread = null;
            }
        }
    }

    /**
     * Creates a <tt>ServerReflexiveCandidate</tt> using {@link #hostCandidate}
     * as its base and the <tt>XOR-MAPPED-ADDRESS</tt> attribute in
     * <tt>response</tt> for the actual <tt>TransportAddress</tt> of the new
     * candidate. If the message is malformed and/or does not contain the
     * corresponding attribute, this method simply has no effect.
     *
     * @param response the STUN <tt>Response</tt> which is supposed to contain
     * the address we should use for the new candidate
     */
    protected void createServerReflexiveCandidate(Response response)
    {
        TransportAddress addr = getMappedAddress(response);

        if (addr != null)
        {
            ServerReflexiveCandidate srvrRflxCand
                = createServerReflexiveCandidate(addr);

            if (srvrRflxCand != null)
            {
                try
                {
                    addCandidate(srvrRflxCand);
                }
                finally
                {
                    // Free srvrRflxCand if it has not been consumed.
                    if (!containsCandidate(srvrRflxCand))
                    {
                        try
                        {
                            srvrRflxCand.free();
                        }
                        catch (Exception ex)
                        {
                            if (logger.isLoggable(Level.FINE))
                            {
                                logger.log(
                                        Level.FINE,
                                        "Failed to free"
                                            + " ServerReflexiveCandidate: "
                                            + srvrRflxCand,
                                        ex);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates a new <tt>ServerReflexiveCandidate</tt> instance which is to
     * represent a specific <tt>TransportAddress</tt> harvested through
     * {@link #hostCandidate} and the STUN server associated with
     * {@link #harvester}.
     *
     * @param transportAddress the <tt>TransportAddress</tt> to be represented
     * by the new <tt>ServerReflexiveCandidate</tt> instance
     * @return a new <tt>ServerReflexiveCandidate</tt> instance which represents
     * the specified <tt>TransportAddress</tt> harvested through
     * {@link #hostCandidate} and the STUN server associated with
     * {@link #harvester}
     */
    protected ServerReflexiveCandidate createServerReflexiveCandidate(
            TransportAddress transportAddress)
    {
        return
            new ServerReflexiveCandidate(
                    transportAddress,
                    hostCandidate,
                    harvester.stunServer,
                    CandidateExtendedType.STUN_SERVER_REFLEXIVE_CANDIDATE);
    }

    /**
     * Runs in {@link #sendKeepAliveMessageThread} to notify this instance that
     * <tt>sendKeepAliveMessageThread</tt> is about to exit.
     */
    private void exitSendKeepAliveMessageThread()
    {
        synchronized (sendKeepAliveMessageSyncRoot)
        {
            if (sendKeepAliveMessageThread == Thread.currentThread())
                sendKeepAliveMessageThread = null;

            /*
             * Well, if the currentThread is finishing and this instance is
             * still to send keep-alive messages, we'd better start another
             * Thread for the purpose to continue the work that the
             * currentThread was supposed to carry out.
             */
            if ((sendKeepAliveMessageThread == null)
                    && (sendKeepAliveMessageInterval
                         != SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED))
            {
                createSendKeepAliveMessageThread();
            }
        }
    }

    /**
     * Gets the number of <tt>Candidate</tt>s harvested for
     * {@link #hostCandidate} during this harvest.
     *
     * @return the number of <tt>Candidate</tt>s harvested for
     * {@link #hostCandidate} during this harvest
     */
    int getCandidateCount()
    {
        return candidates.size();
    }

    /**
     * Gets the <tt>Candidate</tt>s harvested for {@link #hostCandidate} during
     * this harvest.
     *
     * @return an array containing the <tt>Candidate</tt>s harvested for
     * {@link #hostCandidate} during this harvest
     */
    LocalCandidate[] getCandidates()
    {
        return candidates.toArray(NO_CANDIDATES);
    }

    /**
     * Gets the <tt>TransportAddress</tt> specified in the XOR-MAPPED-ADDRESS
     * attribute of a specific <tt>Response</tt>.
     *
     * @param response the <tt>Response</tt> from which the XOR-MAPPED-ADDRESS
     * attribute is to be retrieved and its <tt>TransportAddress</tt> value is
     * to be returned
     * @return the <tt>TransportAddress</tt> specified in the XOR-MAPPED-ADDRESS
     * attribute of <tt>response</tt>
     */
    protected TransportAddress getMappedAddress(Response response)
    {
        Attribute attribute
            = response.getAttribute(Attribute.XOR_MAPPED_ADDRESS);

        if(attribute instanceof XorMappedAddressAttribute)
        {
            return
                ((XorMappedAddressAttribute) attribute)
                    .getAddress(response.getTransactionID());
        }

        // old STUN servers (RFC3489) send MAPPED-ADDRESS address
        attribute
            = response.getAttribute(Attribute.MAPPED_ADDRESS);

        if(attribute instanceof MappedAddressAttribute)
        {
            return
                ((MappedAddressAttribute) attribute)
                    .getAddress();
        }
        else
            return null;
    }

    /**
     * Notifies this <tt>StunCandidateHarvest</tt> that a specific STUN
     * <tt>Request</tt> has been challenged for a long-term credential (as the
     * short-term credential mechanism does not utilize challenging) in a
     * specific <tt>realm</tt> and with a specific <tt>nonce</tt>.
     *
     * @param realm the realm in which the specified STUN <tt>Request</tt> has
     * been challenged for a long-term credential
     * @param nonce the nonce with which the specified STUN <tt>Request</tt> has
     * been challenged for a long-term credential
     * @param request the STUN <tt>Request</tt> which has been challenged for a
     * long-term credential
     * @param requestTransactionID the <tt>TransactionID</tt> of
     * <tt>request</tt> because <tt>request</tt> only has it as a <tt>byte</tt>
     * array and <tt>TransactionID</tt> is required for the
     * <tt>applicationData</tt> property value
     * @return <tt>true</tt> if the challenge has been processed and this
     * <tt>StunCandidateHarvest</tt> is to continue processing STUN
     * <tt>Response</tt>s; otherwise, <tt>false</tt>
     * @throws StunException if anything goes wrong while processing the
     * challenge
     */
    private boolean processChallenge(
            byte[] realm,
            byte[] nonce,
            Request request,
            TransactionID requestTransactionID)
        throws StunException
    {
        UsernameAttribute usernameAttribute
            = (UsernameAttribute) request.getAttribute(Attribute.USERNAME);

        if (usernameAttribute == null)
        {
            if (longTermCredentialSession == null)
            {
                LongTermCredential longTermCredential
                    = harvester.createLongTermCredential(this, realm);

                if (longTermCredential == null)
                {
                    // The long-term credential mechanism is not being utilized.
                    return false;
                }
                else
                {
                    longTermCredentialSession
                        = new LongTermCredentialSession(
                                longTermCredential,
                                realm);
                    harvester
                        .getStunStack()
                            .getCredentialsManager()
                                .registerAuthority(longTermCredentialSession);
                }
            }
            else
            {
                /*
                 * If we're going to use the long-term credential to retry the
                 * request, the long-term credential should be for the request
                 * in terms of realm.
                 */
                if (!longTermCredentialSession.realmEquals(realm))
                    return false;
            }
        }
        else
        {
            /*
             * If we sent a USERNAME in our request, then we had the long-term
             * credential at the time we sent the request in question.
             */
            if (longTermCredentialSession == null)
                return false;
            else
            {
                /*
                 * If we're going to use the long-term credential to retry the
                 * request, the long-term credential should be for the request
                 * in terms of username.
                 */
                if (!longTermCredentialSession.usernameEquals(
                        usernameAttribute.getUsername()))
                    return false;
                else
                {
                    // And it terms of realm, of course.
                    if (!longTermCredentialSession.realmEquals(realm))
                        return false;
                }
            }
        }

        /*
         * The nonce is either becoming known for the first time or being
         * updated after the old one has gone stale.
         */
        longTermCredentialSession.setNonce(nonce);

        Request retryRequest = createRequestToRetry(request);
        TransactionID retryRequestTransactionID = null;

        if (retryRequest != null)
        {
            if (requestTransactionID != null)
            {
                Object applicationData
                    = requestTransactionID.getApplicationData();

                if (applicationData != null)
                {
                    byte[] retryRequestTransactionIDAsBytes
                        = retryRequest.getTransactionID();

                    retryRequestTransactionID
                        = (retryRequestTransactionIDAsBytes == null)
                            ? TransactionID.createNewTransactionID()
                            : TransactionID.createTransactionID(
                                    harvester.getStunStack(),
                                    retryRequestTransactionIDAsBytes);
                    retryRequestTransactionID.setApplicationData(
                            applicationData);
                }
            }
            retryRequestTransactionID
                = sendRequest(retryRequest, false, retryRequestTransactionID);
        }
        return (retryRequestTransactionID != null);
    }

    /**
     * Notifies this <tt>StunCandidateHarvest</tt> that a specific STUN
     * <tt>Response</tt> has been received and it challenges a specific STUN
     * <tt>Request</tt> for a long-term credential (as the short-term credential
     * mechanism does not utilize challenging).
     *
     * @param response the STUN <tt>Response</tt> which has been received
     * @param request the STUN <tt>Request</tt> to which <tt>response</tt>
     * responds and which it challenges for a long-term credential
     * @return <tt>true</tt> if the challenge has been processed and this
     * <tt>StunCandidateHarvest</tt> is to continue processing STUN
     * <tt>Response</tt>s; otherwise, <tt>false</tt>
     * @param transactionID the <tt>TransactionID</tt> of <tt>response</tt> and
     * <tt>request</tt> because <tt>response</tt> and <tt>request</tt> only have
     * it as a <tt>byte</tt> array and <tt>TransactionID</tt> is required for
     * the <tt>applicationData</tt> property value
     * @throws StunException if anything goes wrong while processing the
     * challenge
     */
    private boolean processChallenge(
            Response response,
            Request request,
            TransactionID transactionID)
        throws StunException
    {
        boolean retried = false;

        if (response.getAttributeCount() > 0)
        {
            /*
             * The response SHOULD NOT contain a USERNAME or
             * MESSAGE-INTEGRITY attribute.
             */
            char[] excludedResponseAttributeTypes
                = new char[]
                        {
                            Attribute.USERNAME,
                            Attribute.MESSAGE_INTEGRITY
                        };
            boolean challenge = true;

            for (char excludedResponseAttributeType
                    : excludedResponseAttributeTypes)
            {
                if (response.containsAttribute(excludedResponseAttributeType))
                {
                    challenge = false;
                    break;
                }
            }
            if (challenge)
            {
                // This response MUST include a REALM value.
                RealmAttribute realmAttribute
                    = (RealmAttribute) response.getAttribute(Attribute.REALM);

                if (realmAttribute == null)
                    challenge = false;
                else
                {
                    // The response MUST include a NONCE.
                    NonceAttribute nonceAttribute
                        = (NonceAttribute)
                            response.getAttribute(Attribute.NONCE);

                    if (nonceAttribute == null)
                        challenge = false;
                    else
                    {
                        retried
                            = processChallenge(
                                    realmAttribute.getRealm(),
                                    nonceAttribute.getNonce(),
                                    request,
                                    transactionID);
                    }
                }
            }
        }
        return retried;
    }

    /**
     * Notifies this <tt>StunCandidateHarvest</tt> that a specific
     * <tt>Request</tt> has either received an error <tt>Response</tt> or has
     * failed to receive any <tt>Response</tt>. Allows extenders to override and
     * process unhandled error <tt>Response</tt>s or failures. The default
     * implementation does no processing.
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
     */
    protected boolean processErrorOrFailure(
            Response response,
            Request request,
            TransactionID transactionID)
    {
        return false;
    }

    /**
     * Notifies this <tt>ResponseCollector</tt> that a transaction described by
     * the specified <tt>BaseStunMessageEvent</tt> has failed. The possible
     * reasons for the failure include timeouts, unreachable destination, etc.
     *
     * @param event the <tt>BaseStunMessageEvent</tt> which describes the failed
     * transaction and the runtime type of which specifies the failure reason
     * @see AbstractResponseCollector#processFailure(BaseStunMessageEvent)
     */
    @Override
    protected void processFailure(BaseStunMessageEvent event)
    {
        TransactionID transactionID = event.getTransactionID();

        logger.finest("A transaction expired: tranid=" + transactionID);
        logger.finest("localAddr=" + hostCandidate);

        /*
         * Clean up for the purposes of the workaround which determines the STUN
         * Request to which a STUN Response responds.
         */
        Request request;

        synchronized (requests)
        {
            request = requests.remove(transactionID);
        }
        if (request == null)
        {
            Message message = event.getMessage();

            if (message instanceof Request)
                request = (Request) message;
        }

        boolean completedResolvingCandidate = true;
        try
        {
            if (processErrorOrFailure(null, request, transactionID))
                completedResolvingCandidate = false;
        }
        finally
        {
            if (completedResolvingCandidate)
                completedResolvingCandidate(request, null);
        }
    }

    /**
     * Notifies this <tt>ResponseCollector</tt> that a STUN response described
     * by the specified <tt>StunResponseEvent</tt> has been received.
     *
     * @param event the <tt>StunResponseEvent</tt> which describes the received
     * STUN response
     * @see ResponseCollector#processResponse(StunResponseEvent)
     */
    @Override
    public void processResponse(StunResponseEvent event)
    {
        TransactionID transactionID = event.getTransactionID();

        logger.finest("Received a message: tranid= " + transactionID);
        logger.finest("localCand= " + hostCandidate);

        /*
         * Clean up for the purposes of the workaround which determines the STUN
         * Request to which a STUN Response responds.
         */
        synchronized (requests)
        {
            requests.remove(transactionID);
        }

        // At long last, do start handling the received STUN Response.
        Response response = event.getResponse();
        Request request = event.getRequest();
        boolean completedResolvingCandidate = true;

        try
        {
            if (response.isSuccessResponse())
            {
                // Authentication and Message-Integrity Mechanisms
                if (request.containsAttribute(Attribute.MESSAGE_INTEGRITY))
                {
                    MessageIntegrityAttribute messageIntegrityAttribute
                        = (MessageIntegrityAttribute)
                            response.getAttribute(Attribute.MESSAGE_INTEGRITY);

                    /*
                     * RFC 5389: If MESSAGE-INTEGRITY was absent, the response
                     * MUST be discarded, as if it was never received.
                     */
                    if (messageIntegrityAttribute == null)
                        return;

                    UsernameAttribute usernameAttribute
                        = (UsernameAttribute)
                            request.getAttribute(Attribute.USERNAME);

                    /*
                     * For a request or indication message, the agent MUST
                     * include the USERNAME and MESSAGE-INTEGRITY attributes in
                     * the message.
                     */
                    if (usernameAttribute == null)
                        return;
                    if (!harvester.getStunStack().validateMessageIntegrity(
                            messageIntegrityAttribute,
                            LongTermCredential.toString(
                                    usernameAttribute.getUsername()),
                            !request.containsAttribute(Attribute.REALM)
                                && !request.containsAttribute(Attribute.NONCE),
                            event.getRawMessage()))
                        return;
                }

                processSuccess(response, request, transactionID);
            }
            else
            {
                ErrorCodeAttribute errorCodeAttr
                    = (ErrorCodeAttribute)
                        response.getAttribute(Attribute.ERROR_CODE);

                if ((errorCodeAttr != null)
                        && (errorCodeAttr.getErrorClass() == 4))
                {
                    try
                    {
                        switch (errorCodeAttr.getErrorNumber())
                        {
                        case 1: // 401 Unauthorized
                            if (processUnauthorized(
                                    response,
                                    request,
                                    transactionID))
                                completedResolvingCandidate = false;
                            break;
                        case 38: // 438 Stale Nonce
                            if (processStaleNonce(
                                    response,
                                    request,
                                    transactionID))
                                completedResolvingCandidate = false;
                            break;
                        }
                    }
                    catch (StunException sex)
                    {
                        completedResolvingCandidate = true;
                    }
                }
                if (completedResolvingCandidate
                        && processErrorOrFailure(
                                response,
                                request,
                                transactionID))
                    completedResolvingCandidate = false;
            }
        }
        finally
        {
            if (completedResolvingCandidate)
                completedResolvingCandidate(request, response);
        }
    }

    /**
     * Handles a specific STUN error <tt>Response</tt> with error code
     * "438 Stale Nonce" to a specific STUN <tt>Request</tt>.
     *
     * @param response the received STUN error <tt>Response</tt> with error code
     * "438 Stale Nonce" which is to be handled
     * @param request the STUN <tt>Request</tt> to which <tt>response</tt>
     * responds
     * @param transactionID the <tt>TransactionID</tt> of <tt>response</tt> and
     * <tt>request</tt> because <tt>response</tt> and <tt>request</tt> only have
     * it as a <tt>byte</tt> array and <tt>TransactionID</tt> is required for
     * the <tt>applicationData</tt> property value
     * @return <tt>true</tt> if the specified STUN error <tt>response</tt> was
     * successfully handled; <tt>false</tt>, otherwise
     * @throws StunException if anything goes wrong while handling the specified
     * "438 Stale Nonce" error <tt>response</tt>
     */
    private boolean processStaleNonce(
            Response response,
            Request request,
            TransactionID transactionID)
        throws StunException
    {
        /*
         * The request MUST contain USERNAME, REALM, NONCE and MESSAGE-INTEGRITY
         * attributes.
         */
        boolean challenge;

        if (request.getAttributeCount() > 0)
        {
            char[] includedRequestAttributeTypes
                = new char[]
                        {
                            Attribute.USERNAME,
                            Attribute.REALM,
                            Attribute.NONCE,
                            Attribute.MESSAGE_INTEGRITY
                        };
            challenge = true;

            for (char includedRequestAttributeType
                    : includedRequestAttributeTypes)
            {
                if (!request.containsAttribute(includedRequestAttributeType))
                {
                    challenge = false;
                    break;
                }
            }
        }
        else
            challenge = false;

        return
            (challenge && processChallenge(response, request, transactionID));
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
     */
    protected void processSuccess(
            Response response,
            Request request,
            TransactionID transactionID)
    {
        if (!completedResolvingCandidate)
            createCandidates(response);
    }

    /**
     * Handles a specific STUN error <tt>Response</tt> with error code
     * "401 Unauthorized" to a specific STUN <tt>Request</tt>.
     *
     * @param response the received STUN error <tt>Response</tt> with error code
     * "401 Unauthorized" which is to be handled
     * @param request the STUN <tt>Request</tt> to which <tt>response</tt>
     * responds
     * @param transactionID the <tt>TransactionID</tt> of <tt>response</tt> and
     * <tt>request</tt> because <tt>response</tt> and <tt>request</tt> only have
     * it as a <tt>byte</tt> array and <tt>TransactionID</tt> is required for
     * the <tt>applicationData</tt> property value
     * @return <tt>true</tt> if the specified STUN error <tt>response</tt> was
     * successfully handled; <tt>false</tt>, otherwise
     * @throws StunException if anything goes wrong while handling the specified
     * "401 Unauthorized" error <tt>response</tt>
     */
    private boolean processUnauthorized(
            Response response,
            Request request,
            TransactionID transactionID)
        throws StunException
    {
        /*
         * If the response is a challenge, retry the request with a new
         * transaction.
         */
        boolean challenge = true;

        /*
         * The client SHOULD omit the USERNAME, MESSAGE-INTEGRITY, REALM, and
         * NONCE attributes from the "First Request".
         */
        if (request.getAttributeCount() > 0)
        {
            char[] excludedRequestAttributeTypes
                = new char[]
                        {
                            Attribute.USERNAME,
                            Attribute.MESSAGE_INTEGRITY,
                            Attribute.REALM,
                            Attribute.NONCE
                        };

            for (char excludedRequestAttributeType
                    : excludedRequestAttributeTypes)
            {
                if (request.containsAttribute(excludedRequestAttributeType))
                {
                    challenge = false;
                    break;
                }
            }
        }

        return
            (challenge && processChallenge(response, request, transactionID));
    }

    /**
     * Runs in {@link #sendKeepAliveMessageThread} and sends STUN
     * keep-alive <tt>Message</tt>s to the STUN server associated with the
     * <tt>StunCandidateHarvester</tt> of this instance.
     *
     * @return <tt>true</tt> if the method is to be invoked again; otherwise,
     * <tt>false</tt>
     */
    private boolean runInSendKeepAliveMessageThread()
    {
        synchronized (sendKeepAliveMessageSyncRoot)
        {
            // Since we're going to #wait, make sure we're not canceled yet.
            if (sendKeepAliveMessageThread != Thread.currentThread())
                return false;
            if (sendKeepAliveMessageInterval
                    == SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED)
            {
                return false;
            }

            // Determine the amount of milliseconds that we'll have to #wait.
            long timeout;

            if (sendKeepAliveMessageTime == -1)
            {
                /*
                 * If we're just starting, don't just go and send a new STUN
                 * keep-alive message but rather wait for the whole interval.
                 */
                timeout = sendKeepAliveMessageInterval;
            }
            else
            {
                timeout
                    = sendKeepAliveMessageTime
                        + sendKeepAliveMessageInterval
                        - System.currentTimeMillis();
            }
            // At long last, #wait if necessary.
            if (timeout > 0)
            {
                try
                {
                    sendKeepAliveMessageSyncRoot.wait(timeout);
                }
                catch (InterruptedException iex)
                {
                }
                /*
                 * Apart from being the time to send the STUN keep-alive
                 * message, it could be that we've experienced a spurious
                 * wake-up or that we've been canceled.
                 */
                return true;
            }
        }

        sendKeepAliveMessageTime = System.currentTimeMillis();
        try
        {
            sendKeepAliveMessage();
        }
        catch (StunException sex)
        {
            logger.log(
                    Level.INFO,
                    "Failed to send STUN keep-alive message.",
                    sex);
        }
        return true;
    }

    /**
     * Sends a new STUN <tt>Message</tt> to the STUN server associated with the
     * <tt>StunCandidateHarvester</tt> of this instance in order to keep a
     * <tt>LocalCandidate</tt> harvested by this instance alive.
     *
     * @throws StunException if anything goes wrong while sending a new
     * keep-alive STUN <tt>Message</tt>
     */
    protected void sendKeepAliveMessage()
        throws StunException
    {
        for (LocalCandidate candidate : getCandidates())
            if (sendKeepAliveMessage(candidate))
                break;
    }

    /**
     * Sends a new STUN <tt>Message</tt> to the STUN server associated with the
     * <tt>StunCandidateHarvester</tt> of this instance in order to keep a
     * specific <tt>LocalCandidate</tt> alive.
     *
     * @param candidate the <tt>LocalCandidate</tt> to send a new keep-alive
     * STUN <tt>Message</tt> for
     * @return <tt>true</tt> if a new STUN <tt>Message</tt> was sent to the
     * STUN server associated with the <tt>StunCandidateHarvester</tt> of this
     * instance or <tt>false</tt> if the STUN kee-alive functionality was not
     * been used for the specified <tt>candidate</tt>
     * @throws StunException if anything goes wrong while sending the new
     * keep-alive STUN <tt>Message</tt> for the specified <tt>candidate</tt>
     */
    protected boolean sendKeepAliveMessage(LocalCandidate candidate)
        throws StunException
    {
        Message keepAliveMessage = createKeepAliveMessage(candidate);

        /*
         * The #createKeepAliveMessage method javadoc says it returns null when
         * the STUN keep-alive functionality of this StunCandidateHarvest is to
         * not be utilized.
         */
        if (keepAliveMessage == null)
        {
            return false;
        }
        else if (keepAliveMessage instanceof Request)
        {
            return
                (sendRequest((Request) keepAliveMessage, false, null) != null);
        }
        else
        {
            throw new StunException(
                    StunException.ILLEGAL_ARGUMENT,
                    "Failed to create keep-alive STUN message for candidate: "
                            + candidate);
        }
    }

    /**
     * Sends a specific <tt>Request</tt> to the STUN server associated with this
     * <tt>StunCandidateHarvest</tt>.
     *
     * @param request the <tt>Request</tt> to send to the STUN server associated
     * with this <tt>StunCandidateHarvest</tt>
     * @param firstRequest <tt>true</tt> if the specified <tt>request</tt>
     * should be sent as the first request in the terms of STUN; otherwise,
     * <tt>false</tt>
     * @return the <tt>TransactionID</tt> of the STUN client transaction through
     * which the specified <tt>Request</tt> has been sent to the STUN server
     * associated with this <tt>StunCandidateHarvest</tt>
     * @param transactionID the <tt>TransactionID</tt> of <tt>request</tt>
     * because <tt>request</tt> only has it as a <tt>byte</tt> array and
     * <tt>TransactionID</tt> is required for the <tt>applicationData</tt>
     * property value
     * @throws StunException if anything goes wrong while sending the specified
     * <tt>Request</tt> to the STUN server associated with this
     * <tt>StunCandidateHarvest</tt>
     */
    protected TransactionID sendRequest(
            Request request,
            boolean firstRequest,
            TransactionID transactionID)
        throws StunException
    {
        if (!firstRequest && (longTermCredentialSession != null))
            longTermCredentialSession.addAttributes(request);

        StunStack stunStack = harvester.getStunStack();
        TransportAddress stunServer = harvester.stunServer;
        TransportAddress hostCandidateTransportAddress
            = hostCandidate.getTransportAddress();

        if (transactionID == null)
        {
            byte[] transactionIDAsBytes = request.getTransactionID();

            transactionID
                = (transactionIDAsBytes == null)
                    ? TransactionID.createNewTransactionID()
                    : TransactionID.createTransactionID(
                            harvester.getStunStack(),
                            transactionIDAsBytes);
        }
        synchronized (requests)
        {
            try
            {
                transactionID
                    = stunStack
                        .sendRequest(
                            request,
                            stunServer,
                            hostCandidateTransportAddress,
                            this,
                            transactionID);
            }
            catch (IllegalArgumentException iaex)
            {
                if (logger.isLoggable(Level.INFO))
                {
                    logger.log(
                            Level.INFO,
                            "Failed to send "
                                + request
                                + " through " + hostCandidateTransportAddress
                                + " to " + stunServer,
                            iaex);
                }
                throw new StunException(
                        StunException.ILLEGAL_ARGUMENT,
                        iaex.getMessage(),
                        iaex);
            }
            catch (IOException ioex)
            {
                if (logger.isLoggable(Level.INFO))
                {
                    logger.log(
                            Level.INFO,
                            "Failed to send "
                                + request
                                + " through " + hostCandidateTransportAddress
                                + " to " + stunServer,
                            ioex);
                }
                throw new StunException(
                        StunException.NETWORK_ERROR,
                        ioex.getMessage(),
                        ioex);
            }

            requests.put(transactionID, request);
        }
        return transactionID;
    }

    /**
     * Sets the interval in milliseconds at which a new STUN keep-alive message
     * is to be sent to the STUN server associated with the
     * <tt>StunCandidateHarvester</tt> of this instance in order to keep one of
     * the <tt>Candidate</tt>s harvested by this instance alive.
     *
     * @param sendKeepAliveMessageInterval the interval in milliseconds at which
     * a new STUN keep-alive message is to be sent to the STUN server associated
     * with the <tt>StunCandidateHarvester</tt> of this instance in order to
     * keep one of the <tt>Candidate</tt>s harvested by this instance alive or
     * {@link #SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED} if the keep-alive
     * functionality is to not be utilized
     */
    protected void setSendKeepAliveMessageInterval(
            long sendKeepAliveMessageInterval)
    {
        if ((sendKeepAliveMessageInterval
                    != SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED)
                && (sendKeepAliveMessageInterval < 1))
            throw new IllegalArgumentException("sendKeepAliveMessageInterval");

        synchronized (sendKeepAliveMessageSyncRoot)
        {
            this.sendKeepAliveMessageInterval = sendKeepAliveMessageInterval;
            if (sendKeepAliveMessageThread == null)
            {
                if (this.sendKeepAliveMessageInterval
                        != SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED)
                    createSendKeepAliveMessageThread();
            }
            else
                sendKeepAliveMessageSyncRoot.notify();
        }
    }

    /**
     * Starts the harvesting of <tt>Candidate</tt>s to be performed for
     * {@link #hostCandidate}.
     *
     * @return <tt>true</tt> if this <tt>StunCandidateHarvest</tt> has started
     * the harvesting of <tt>Candidate</tt>s for {@link #hostCandidate};
     * otherwise, <tt>false</tt>
     * @throws Exception if anything goes wrong while starting the harvesting of
     * <tt>Candidate</tt>s to be performed for {@link #hostCandidate}
     */
    boolean startResolvingCandidate()
        throws Exception
    {
        Request requestToStartResolvingCandidate;

        if (!completedResolvingCandidate
                && ((requestToStartResolvingCandidate
                            = createRequestToStartResolvingCandidate())
                        != null))
        {
            // Short-Term Credential Mechanism
            addShortTermCredentialAttributes(requestToStartResolvingCandidate);

            sendRequest(requestToStartResolvingCandidate, true, null);
            return true;
        }
        else
            return false;
    }

    /**
     * Close the harvest.
     */
    public void close()
    {
        // stop keep alive thread
        setSendKeepAliveMessageInterval(
            SEND_KEEP_ALIVE_MESSAGE_INTERVAL_NOT_SPECIFIED);
    }

    /**
     * Sends STUN keep-alive <tt>Message</tt>s to the STUN server associated
     * with the <tt>StunCandidateHarvester</tt> of this instance.
     *
     * @author Lyubomir Marinov
     */
    private static class SendKeepAliveMessageThread
        extends Thread
    {
        /**
         * The <tt>StunCandidateHarvest</tt> which has initialized this
         * instance. The <tt>StunCandidateHarvest</tt> is referenced by a
         * <tt>WeakReference</tt> in an attempt to reduce the risk that the
         * <tt>Thread</tt> may live regardless of the fact that the specified
         * <tt>StunCandidateHarvest<tt> may no longer be reachable.
         */
        private final WeakReference<StunCandidateHarvest> harvest;

        /**
         * Initializes a new <tt>SendKeepAliveMessageThread</tt> instance with a
         * specific <tt>StunCandidateHarvest</tt>.
         *
         * @param harvest the <tt>StunCandidateHarvest</tt> to initialize the
         * new instance with
         */
        public SendKeepAliveMessageThread(StunCandidateHarvest harvest)
        {
            this.harvest = new WeakReference<StunCandidateHarvest>(harvest);
        }

        @Override
        public void run()
        {
            try
            {
                do
                {
                    StunCandidateHarvest harvest = this.harvest.get();

                    if ((harvest == null)
                            || !harvest.runInSendKeepAliveMessageThread())
                    {
                        break;
                    }
                }
                while (true);
            }
            finally
            {
                StunCandidateHarvest harvest = this.harvest.get();

                if (harvest != null)
                    harvest.exitSendKeepAliveMessageThread();
            }
        }
    }
}
