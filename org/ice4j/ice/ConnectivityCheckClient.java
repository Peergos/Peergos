/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice;

import java.net.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;
import org.ice4j.stack.*;

/**
 * The class that will be generating our outgoing connectivity checks and that
 * will be handling their responses or lack thereof.
 *
 * @author Emil Ivov
 * @author Lyubomir Marinov
 */
class ConnectivityCheckClient
    implements ResponseCollector
{
    /**
     * The <tt>Logger</tt> used by the <tt>ConnectivityCheckClient</tt>
     * class and its instances for logging output.
     */
    private static final Logger logger
        = Logger.getLogger(ConnectivityCheckClient.class.getName());

    /**
     * The agent that created us.
     */
    private final Agent parentAgent;

    /**
     * The <tt>StunStack</tt> that we will use for connectivity checks.
     */
    private final StunStack stunStack;

    /**
     * The {@link PaceMaker}s that are currently running checks in this client.
     */
    private final List<PaceMaker> paceMakers = new LinkedList<PaceMaker>();

    /**
     * Timer that is used to let some seconds before a CheckList is considered
     * as FAILED.
     */
    private Map<String, Timer> timers = new HashMap<String, Timer>();

    /**
     * Creates a new <tt>ConnectivityCheckHandler</tt> setting
     * <tt>parentAgent</tt> as the agent that will be used for retrieving
     * information such as user fragments for example.
     *
     * @param parentAgent the <tt>Agent</tt> that is creating this instance.
     */
    public ConnectivityCheckClient(Agent parentAgent)
    {
        this.parentAgent = parentAgent;

        stunStack = this.parentAgent.getStunStack();
    }

    /**
     * Starts client connectivity checks for the first {@link IceMediaStream}
     * in our parent {@link Agent}. This method should only be called by
     * the parent {@link Agent} when connectivity establishment starts for a
     * particular check list.
     */
    public void startChecks()
    {
        CheckList firstCheckList
            = parentAgent
                .getStreamsWithPendingConnectivityEstablishment()
                    .get(0)
                        .getCheckList();

        logger.info("Start connectivity checks!");
        startChecks(firstCheckList);
    }

    /**
     * Starts client connectivity checks for the {@link CandidatePair}s in
     *  <tt>checkList</tt>
     *
     * @param checkList the {@link CheckList} to start client side connectivity
     * checks for.
     */
    public void startChecks(CheckList checkList)
    {
        PaceMaker paceMaker = new PaceMaker(checkList);

        synchronized (paceMakers)
        {
            paceMakers.add(paceMaker);
        }
        paceMaker.start();
    }

    /**
     * Creates a STUN Binding {@link Indication} to a candidate pair. It is used
     * as a keep-alive.
     *
     * @param candidatePair that {@link CandidatePair} that we'd like to send
     * an indication
     */
    protected void sendBindingIndicationForPair(CandidatePair candidatePair)
    {
        LocalCandidate localCandidate = candidatePair.getLocalCandidate();
        Indication indication = MessageFactory.createBindingIndication();

        try
        {
            stunStack.sendIndication(
                    indication,
                    candidatePair.getRemoteCandidate().getTransportAddress(),
                    localCandidate.getBase().getTransportAddress());
            if(logger.isLoggable(Level.FINEST))
            {
                logger.finest(
                        "sending binding indication to pair " + candidatePair);
            }
        }
        catch (Exception ex)
        {
            IceSocketWrapper stunSocket = localCandidate.getStunSocket(null);

            if(stunSocket != null)
            {
                logger.log(
                        Level.INFO,
                        "Failed to send " + indication + " through "
                            + stunSocket.getLocalSocketAddress(),
                        ex);
            }
        }
    }

    /**
     * Creates a STUN {@link Request} containing the necessary PRIORITY and
     * CONTROLLING/CONTROLLED attributes. Also stores a reference to
     * <tt>candidatePair</tt> in the newly created transactionID so that we
     * could then refer back to it in subsequent response or failure events.
     *
     * @param candidatePair that {@link CandidatePair} that we'd like to start
     * a check for.
     *
     * @return a reference to the {@link TransactionID} used in the connectivity
     * check client transaction or <tt>null</tt> if sending the check has
     * failed for some reason.
     */
    protected TransactionID startCheckForPair(CandidatePair candidatePair)
    {
        return startCheckForPair(candidatePair, -1, -1, -1);
    }

    /**
     * Creates a STUN {@link Request} containing the necessary PRIORITY and
     * CONTROLLING/CONTROLLED attributes. Also stores a reference to
     * <tt>candidatePair</tt> in the newly created transactionID so that we
     * could then refer back to it in subsequent response or failure events.
     *
     * @param candidatePair that {@link CandidatePair} that we'd like to start
     * a check for.
     * @param originalWaitInterval
     * @param maxWaitInterval
     * @param maxRetransmissions
     * @return a reference to the {@link TransactionID} used in the connectivity
     * check client transaction or <tt>null</tt> if sending the check has
     * failed for some reason.
     */
    protected TransactionID startCheckForPair(
            CandidatePair candidatePair,
            int originalWaitInterval,
            int maxWaitInterval,
            int maxRetransmissions)
    {
        LocalCandidate localCandidate = candidatePair.getLocalCandidate();
        //we don't need to do a canReach() verification here as it has been
        //already verified during the gathering process.

        Request request = MessageFactory.createBindingRequest();

        //the priority we'd like the remote party to use for a peer
        //reflexive candidate if one is discovered as a consequence of this
        //check.
        PriorityAttribute priority
            = AttributeFactory.createPriorityAttribute(
                    localCandidate.computePriorityForType(
                            CandidateType.PEER_REFLEXIVE_CANDIDATE));

        request.putAttribute(priority);

        //controlling controlled
        if (parentAgent.isControlling())
        {
            request.putAttribute(
                    AttributeFactory.createIceControllingAttribute(
                            parentAgent.getTieBreaker()));

            //if we are the controlling agent then we need to indicate our
            //nominated pairs.
            if(candidatePair.isNominated())
            {
                logger.fine(
                        "Add USE-CANDIDATE in check for: "
                            + candidatePair.toShortString());
                request.putAttribute(
                        AttributeFactory.createUseCandidateAttribute());
            }
        }
        else
        {
            request.putAttribute(
                    AttributeFactory.createIceControlledAttribute(
                            parentAgent.getTieBreaker()));
        }

        //credentials
        String media
            = candidatePair
                .getParentComponent().getParentStream().getName();
        String localUserName = parentAgent.generateLocalUserName(media);

        if(localUserName == null)
            return null;

        UsernameAttribute unameAttr
            = AttributeFactory.createUsernameAttribute(localUserName);

        request.putAttribute(unameAttr);

        // TODO Also implement SASL prepare
        MessageIntegrityAttribute msgIntegrity
            = AttributeFactory.createMessageIntegrityAttribute(
                    localUserName);

        // when we will encode the MESSAGE-INTEGRITY attribute (thus
        // generate the HMAC-SHA1 authentication), we need to know the
        // remote key of the current stream, that why we pass the media
        // name.
        msgIntegrity.setMedia(media);
        request.putAttribute(msgIntegrity);

        TransactionID tran = TransactionID.createNewTransactionID();

        tran.setApplicationData(candidatePair);

        if (isReliableTransport(candidatePair))
            maxRetransmissions = 0;
        logger.fine(
                "start check for " + candidatePair.toShortString() + " tid "
                    + tran);
        try
        {
            tran
                = stunStack.sendRequest(
                        request,
                        candidatePair
                            .getRemoteCandidate().getTransportAddress(),
                        localCandidate.getBase().getTransportAddress(),
                        this,
                        tran,
                        originalWaitInterval,
                        maxWaitInterval,
                        maxRetransmissions);
            if(logger.isLoggable(Level.FINEST))
            {
                logger.finest(
                        "checking pair " + candidatePair + " tid " + tran);
            }
        }
        catch (Exception ex)
        {
            tran = null;

            IceSocketWrapper stunSocket = localCandidate.getStunSocket(null);

            if(stunSocket != null)
            {
                String msg
                    = "Failed to send " + request + " through "
                        + stunSocket.getLocalSocketAddress() + ".";

                if((ex instanceof NoRouteToHostException)
                        || (ex.getMessage() != null
                                && ex.getMessage().equals("No route to host")))
                {
                    msg += " No route to host.";
                    ex = null;
                }
                logger.log(Level.INFO, msg, ex);
            }
        }

        return tran;
    }

    /**
     * Handles the <tt>response</tt> as per the procedures described in RFC 5245
     * or in other words, by either changing the state of the corresponding pair
     * to FAILED, or SUCCEEDED, or rescheduling a check in case of a role
     * conflict.
     *
     * @param ev the {@link StunResponseEvent} that contains the newly received
     * response.
     */
    public void processResponse(StunResponseEvent ev)
    {
        CandidatePair checkedPair
            = (CandidatePair) ev.getTransactionID().getApplicationData();

        //make sure that the response came from the right place.
        if (!checkSymmetricAddresses(ev))
        {
            logger.info("Received a non-symmetric response for pair: "
                                + checkedPair.toShortString() + ". Failing.");
            checkedPair.setStateFailed();
        }
        else
        {
            Response response = ev.getResponse();
            char messageType = response.getMessageType();

            //handle error responses.
            if(messageType == Response.BINDING_ERROR_RESPONSE)
            {
                if(!response.containsAttribute(Attribute.ERROR_CODE))
                {
                    logger.fine("Received a malformed error response.");
                    return; //malformed error response
                }

                processErrorResponse(ev);
            }
            //handle success responses.
            else if(messageType == Response.BINDING_SUCCESS_RESPONSE)
            {
                processSuccessResponse(ev);
            }
        }

        //Regardless of whether the check was successful or failed, the
        //completion of the transaction may require updating of check list and
        //timer states.
        updateCheckListAndTimerStates(checkedPair);
    }

    /**
     * Updates all check list and timer states after a check has completed
     * (both if completion was successful or not). The method implements
     * section "7.1.3.3. Check List and Timer State Updates"
     *
     * @param checkedPair the pair whose check has just completed.
     */
    private void updateCheckListAndTimerStates(CandidatePair checkedPair)
    {
        IceMediaStream stream
            = checkedPair.getParentComponent().getParentStream();
        final CheckList checkList = stream.getCheckList();

        if(stream.getParentAgent().getState().equals(
                IceProcessingState.COMPLETED))
            return;

        //If all of the pairs in the check list are now either in the Failed or
        //Succeeded state:
        if(checkList.allChecksCompleted())
        {
            //If there is not a pair in the valid list for each component of the
            //media stream, the state of the check list is set to Failed.
            if ( !stream.validListContainsAllComponents())
            {
                final String streamName = stream.getName();
                Timer timer = timers.get(streamName);

                if(timer == null)
                {
                    logger.info("CheckList will failed in a few seconds if no" +
                            "succeeded checks come");

                    TimerTask task = new TimerTask()
                    {
                        @Override
                        public void run()
                        {
                            if(checkList.getState() != CheckListState.COMPLETED)
                            {
                                logger.info("CheckList for stream " +
                                    streamName + " FAILED");

                                checkList.setState(CheckListState.FAILED);
                                parentAgent.checkListStatesUpdated();
                            }
                        }
                    };
                    timer = new Timer();
                    timers.put(streamName, timer);
                    timer.schedule(task, 5000);
                }
            }

            //For each frozen check list, the agent groups together all of the
            //pairs with the same foundation, and for each group, sets the
            //state of the pair with the lowest component ID to Waiting.  If
            //there is more than one such pair, the one with the highest
            //priority is used.
            List<IceMediaStream> allOtherStreams = parentAgent.getStreams();

            allOtherStreams.remove(stream);
            for (IceMediaStream anotherStream : allOtherStreams)
            {
                CheckList anotherCheckList = anotherStream.getCheckList();

                if(anotherCheckList.isFrozen())
                {
                    anotherCheckList.computeInitialCheckListPairStates();
                    startChecks(anotherCheckList);
                }
            }
        }

        parentAgent.checkListStatesUpdated();
    }

    /**
     * Handles STUN success responses as per the rules in RFC 5245.
     *
     * @param ev the event that delivered the error response.
     */
    private void processSuccessResponse(StunResponseEvent ev)
    {
        Response response = ev.getResponse();
        Request  request  = ev.getRequest();

        CandidatePair checkedPair
            = (CandidatePair) ev.getTransactionID().getApplicationData();

        TransportAddress mappedAddress = null;

        if(! response.containsAttribute(Attribute.XOR_MAPPED_ADDRESS))
        {
            logger.fine("Received a success response with no "
                    + "XOR_MAPPED_ADDRESS attribute.");
            logger.info("Pair failed (no XOR-MAPPED-ADDRESS): "
                    + checkedPair.toShortString());
            checkedPair.setStateFailed();
            return; //malformed error response
        }

        XorMappedAddressAttribute mappedAddressAttr
            = (XorMappedAddressAttribute)response
                .getAttribute(Attribute.XOR_MAPPED_ADDRESS);

        mappedAddress = mappedAddressAttr
            .getAddress(response.getTransactionID());

        // XXX AddressAttribute always returns UDP based TransportAddress
        if(checkedPair.getLocalCandidate().getTransport() == Transport.TCP)
        {
            mappedAddress
                = new TransportAddress(
                        mappedAddress.getAddress(),
                        mappedAddress.getPort(),
                        Transport.TCP);
        }

        LocalCandidate validLocalCandidate = null;

        validLocalCandidate = parentAgent
            .findLocalCandidate(mappedAddress);

        RemoteCandidate validRemoteCandidate = checkedPair.getRemoteCandidate();

        // RFC 5245: The agent checks the mapped address from the STUN
        // response. If the transport address does not match any of the
        // local candidates that the agent knows about, the mapped address
        // represents a new candidate -- a peer reflexive candidate.
        if ( validLocalCandidate == null)
        {
            //Like other candidates, PEER-REFLEXIVE candidates have a type,
            //base, priority, and foundation.  They are computed as follows:
            //o The type is equal to peer reflexive.
            //o The base is the local candidate of the candidate
            //  pair from which the STUN check was sent.
            //o Its priority is set equal to the value of the PRIORITY attribute
            //  in the Binding request.
            long priority = 0;
            PriorityAttribute prioAttr = (PriorityAttribute)request
                .getAttribute(Attribute.PRIORITY);
            priority = prioAttr.getPriority();

            LocalCandidate peerReflexiveCandidate
                = new PeerReflexiveCandidate(
                        mappedAddress,
                        checkedPair.getParentComponent(),
                        checkedPair.getLocalCandidate(),
                        priority);

            peerReflexiveCandidate.setBase(checkedPair.getLocalCandidate());

            //This peer reflexive candidate is then added to the list of local
            //candidates for the media stream, so that it would be available for
            //updated offers.
            checkedPair.getParentComponent().addLocalCandidate(
                    peerReflexiveCandidate);

            //However, the peer reflexive candidate is not paired with other
            //remote candidates. This is not necessary; a valid pair will be
            //generated from it momentarily
            validLocalCandidate = peerReflexiveCandidate;

            if(checkedPair.getParentComponent().getSelectedPair() == null)
                logger.info("Receive a peer-reflexive candidate: " +
                    peerReflexiveCandidate.getTransportAddress());
        }

        //check if the resulting valid pair was already in our check lists.
        CandidatePair existingPair
            = parentAgent.findCandidatePair(
                    validLocalCandidate.getTransportAddress(),
                    validRemoteCandidate.getTransportAddress());

        // RFC 5245: 7.1.3.2.2. The agent constructs a candidate pair whose
        // local candidate equals the mapped address of the response, and whose
        // remote candidate equals the destination address to which the request
        // was sent. This is called a valid pair, since it has been validated
        // by a STUN connectivity check.
        CandidatePair validPair
            = (existingPair == null)
                ? new CandidatePair(validLocalCandidate, validRemoteCandidate)
                : existingPair;

        // we synchronize here because the same pair object can be processed (in
        // another thread) in Agent's triggerCheck. A controlled agent select
        // its pair here if the pair has useCandidateReceived as true (set in
        // triggerCheck) or in triggerCheck if the pair state is succeeded (set
        // here). So be sure that if a binding response and a binding request
        // (for the same check) from other peer come at the very same time, that
        // we will trigger the nominationConfirmed (that will pass the pair as
        // as selected if it is the first time).
        synchronized(checkedPair)
        {
            //The agent sets the state of the pair that *generated* the check to
            //Succeeded.  Note that, the pair which *generated* the check may be
            //different than the valid pair constructed above
            if(checkedPair.getParentComponent().getSelectedPair() == null)
                logger.info("Pair succeeded: " + checkedPair.toShortString());
            checkedPair.setStateSucceeded();
        }

        if(! validPair.isValid())
        {
            if(validPair.getParentComponent().getSelectedPair() == null)
                logger.info("Pair validated: " + validPair.toShortString());
            parentAgent.validatePair(validPair);
        }

        //The agent changes the states for all other Frozen pairs for the
        //same media stream and same foundation to Waiting.
        IceMediaStream parentStream = checkedPair.getParentComponent()
            .getParentStream();

        synchronized(this)
        {
            Vector<CandidatePair> parentCheckList
                = new Vector<CandidatePair>(parentStream.getCheckList());

            for(CandidatePair pair : parentCheckList)
                if ((pair.getState() == CandidatePairState.FROZEN)
                        && checkedPair.getFoundation().equals(
                                pair.getFoundation()))
                    pair.setStateWaiting();
        }

        // The agent examines the check list for all other streams in turn. If
        // the check list is active, the agent changes the state of all Frozen
        // pairs in that check list whose foundation matches a pair in the valid
        // list under consideration to Waiting.
        List<IceMediaStream> allOtherStreams = parentAgent.getStreams();
        allOtherStreams.remove(parentStream);

        for (IceMediaStream stream : allOtherStreams)
        {
            CheckList checkList = stream.getCheckList();
            boolean wasFrozen = checkList.isFrozen();

            synchronized (checkList)
            {
                for(CandidatePair pair : checkList)
                {
                    if (parentStream.validListContainsFoundation(
                                pair.getFoundation())
                            && pair.getState() == CandidatePairState.FROZEN)
                    {
                        pair.setStateWaiting();
                    }
                }
            }

            //if the checklList is still frozen after the above operations,
            //the agent groups together all of the pairs with the same
            //foundation, and for each group, sets the state of the pair with
            //the lowest component ID to Waiting.  If there is more than one
            //such pair, the one with the highest priority is used.
            if(checkList.isFrozen())
                checkList.computeInitialCheckListPairStates();

            if (wasFrozen)
            {
                logger.info("Start checks for checkList of stream " +
                        stream.getName() + " that was frozen");
                startChecks(checkList);
            }
        }

        if(validPair.getParentComponent().getSelectedPair() == null)
        {
            logger.info("IsControlling: "  + parentAgent.isControlling() +
                " USE-CANDIDATE:" +
                    (request.containsAttribute(Attribute.USE_CANDIDATE) ||
                        checkedPair.useCandidateSent()));
        }

        //If the agent was a controlling agent, and it had included a USE-
        //CANDIDATE attribute in the Binding request, the valid pair generated
        //from that check has its nominated flag set to true.
        if((parentAgent.isControlling()
                    && request.containsAttribute(Attribute.USE_CANDIDATE)))
        {
            if(validPair.getParentComponent().getSelectedPair() == null)
            {
                logger.info(
                        "Nomination confirmed for pair: "
                            + validPair.toShortString());
                parentAgent.nominationConfirmed( validPair );
            }
            else
            {
                logger.fine(
                        "Keep alive for pair: " + validPair.toShortString());
            }
        }
        //If the agent is the controlled agent, the response may be the result
        //of a triggered check that was sent in response to a request that
        //itself had the USE-CANDIDATE attribute.  This case is described in
        //Section 7.2.1.5, and may now result in setting the nominated flag for
        //the pair learned from the original request.
        else if(!parentAgent.isControlling()
                && checkedPair.useCandidateReceived()
                && !checkedPair.isNominated())
        {
            if(checkedPair.getParentComponent().getSelectedPair() == null)
            {
                logger.info(
                        "Nomination confirmed for pair: "
                            + validPair.toShortString());
                parentAgent.nominationConfirmed( checkedPair );
            }
            else
            {
                logger.fine(
                        "Keep alive for pair: " + validPair.toShortString());
            }
        }

        // Selected pairs get their consent freshness confirmed.
        if (checkedPair == checkedPair.getParentComponent().getSelectedPair())
            checkedPair.setConsentFreshness();
    }

    /**
     * Returns <tt>true</tt> if the {@link Response} in <tt>evt</tt> had a
     * source or a destination address that match those of the {@link Request},
     * or <tt>false</tt> otherwise.<p>
     * RFC 5245: The agent MUST check that the source IP address and port of
     * the response equal the destination IP address and port to which the
     * Binding request was sent, and that the destination IP address and
     * port of the response match the source IP address and port from which
     * the Binding request was sent.  In other words, the source and
     * destination transport addresses in the request and responses are
     * symmetric.  If they are not symmetric, the agent sets the state of
     * the pair to Failed.
     *
     * @param evt the {@link StunResponseEvent} that contains the {@link
     * Response} we need to examine
     *
     * @return <tt>true</tt> if the {@link Response} in <tt>evt</tt> had a
     * source or a destination address that matched those of the
     * {@link Request}, or <tt>false</tt> otherwise.
     */
    private boolean checkSymmetricAddresses(StunResponseEvent evt)
    {
        CandidatePair pair
            = ((CandidatePair) evt.getTransactionID().getApplicationData());
        TransportAddress localAddr
            = pair.getLocalCandidate().getBase().getTransportAddress();

        return
            localAddr.equals(evt.getLocalAddress())
                && pair.getRemoteCandidate().getTransportAddress().equals(
                        evt.getRemoteAddress());
    }

    /**
     * Checks whether the transport protocol used by <tt>pair</tt> is to be
     * considered reliable for the purposes of <tt>ConnectivityCheckClient</tt>,
     * that is, if client transactions need to be retransmitted or not.
     * @param pair the pair to check.
     * @return <tt>true</tt> iff the transport protocol of <tt>pair</tt> is
     * to be considered reliable and retransmissions should be disabled for
     * a client transaction for <tt>pair</tt>.
     */
    private boolean isReliableTransport(CandidatePair pair)
    {
        return pair.getLocalCandidate().getTransport() == Transport.TCP;
    }

    /**
     * In case of a role conflict, changes the state of the agent and
     * reschedules the check, in all other cases sets the corresponding peer
     * state to FAILED.
     *
     * @param ev the event that delivered the error response.
     */
    private void processErrorResponse(StunResponseEvent ev)
    {
        Response response = ev.getResponse();
        Request originalRequest = ev.getRequest();

        ErrorCodeAttribute errorAttr
            = (ErrorCodeAttribute) response.getAttribute(Attribute.ERROR_CODE);
        // GTalk error code is not RFC3489/RFC5389 compliant
        // example: 400 becomes 0x01 0x90 with GTalk
        // RFC3489/RFC5389 gives 0x04 0x00
        int cl = errorAttr.getErrorClass();
        int co = errorAttr.getErrorNumber() & 0xff;
        char errorCode = errorAttr.getErrorCode();

        CandidatePair pair
            = (CandidatePair) ev.getTransactionID().getApplicationData();

        logger.finer("Received error code " + ((int) errorCode));

        //RESOLVE ROLE_CONFLICTS
        if(errorCode == ErrorCodeAttribute.ROLE_CONFLICT)
        {
            boolean wasControlling
                = originalRequest.containsAttribute(Attribute.ICE_CONTROLLING);

            logger.finer("Swithing to isControlling=" + !wasControlling);
            parentAgent.setControlling(!wasControlling);

            pair.getParentComponent().getParentStream().getCheckList()
                .scheduleTriggeredCheck(pair);
        }
        else
        {
            int code = cl * 100 + co;
            logger.info(
                    "Error response for pair: " + pair.toShortString() +
                    ", failing.  Code = " + code +
                    "(class=" + cl + "; number=" + co + ")");
            pair.setStateFailed();
        }
    }

    /**
     * Sets the state of the corresponding {@link CandidatePair} to
     * {@link CandidatePairState#FAILED} and updates check list and timer
     * states.
     *
     * @param ev the {@link StunTimeoutEvent} containing the original
     * transaction and hence {@link CandidatePair} that's being checked.
     */
    public void processTimeout(StunTimeoutEvent ev)
    {
        CandidatePair pair
            = (CandidatePair) ev.getTransactionID() .getApplicationData();

        logger.info("timeout for pair: " + pair.toShortString() + ", failing.");
        pair.setStateFailed();
        updateCheckListAndTimerStates(pair);
    }

    /**
     * The thread that actually sends the checks for a particular check list
     * in the pace defined in RFC 5245.
     */
    private class PaceMaker
        extends Thread
    {
        /**
         * Indicates whether this <tt>Thread</tt> should still be running.
         */
        boolean running = true;

        /**
         * The {@link CheckList} that this <tt>PaceMaker</tt> will be running
         * checks for.
         */
        private final CheckList checkList;

        /**
         * Creates a new {@link PaceMaker} for this
         * <tt>ConnectivityCheckClient</tt>.
         *
         * @param checkList the {@link CheckList} that we'll be sending checks
         * for
         */
        public PaceMaker(CheckList checkList)
        {
            super("ICE PaceMaker: " + parentAgent.getLocalUfrag());

            this.checkList = checkList;

            /*
             * Because PaceMaker does not seem to be a daemon at least on Ubuntu
             * in the run-sample target and it looks like it should be from the
             * standpoint of the application, explicitly tell it to be a daemon.
             * Otherwise, it could prevent the application from exiting because
             * of a problem in the ICE implementation.
             */
            setDaemon(true);
        }

        /**
         * Returns the number milliseconds to wait before we send the next
         * check.
         *
         * @return  the number milliseconds to wait before we send the next
         * check.
         */
        private long getNextWaitInterval()
        {
            int activeCheckLists = parentAgent.getActiveCheckListCount();

            if (activeCheckLists < 1)
            {
                //don't multiply by 0. even when we no longer have active check
                //lists we may still have nomination checks to
                activeCheckLists = 1;
            }

            return parentAgent.calculateTa() * activeCheckLists;
        }

        /**
         * Sends connectivity checks at the pace determined by the {@link
         * Agent#calculateTa()} method and using either the trigger check queue
         * or the regular check lists.
         */
        @Override
        public synchronized void run()
        {
            try
            {
                while(running)
                {
                    long waitFor = getNextWaitInterval();

                    if(waitFor > 0)
                    {
                        /*
                         * waitFor will be 0 for the first check since we won't
                         * have any active check lists at that point yet.
                         */
                        try
                        {
                            wait(waitFor);
                        }
                        catch (InterruptedException e)
                        {
                            logger.log(Level.FINER, "PaceMaker got interrupted",
                                    e);
                        }

                        if (!running)
                            break;
                    }

                    CandidatePair pairToCheck = checkList.popTriggeredCheck();

                    //if there are no triggered checks, go for an ordinary one.
                    if(pairToCheck == null)
                        pairToCheck = checkList.getNextOrdinaryPairToCheck();

                    if(pairToCheck != null)
                    {
                        /*
                         * Since we suspect that it is possible to
                         * startCheckForPair, processSuccessResponse and only
                         * then setStateInProgress, we'll synchronize. The
                         * synchronization root is the one of the
                         * CandidatePair#setState method.
                         */
                        synchronized (pairToCheck)
                        {
                            TransactionID transactionID
                                = startCheckForPair(pairToCheck);

                            if(transactionID == null)
                            {
                                logger.info(
                                        "Pair failed: "
                                            + pairToCheck.toShortString());
                                pairToCheck.setStateFailed();
                            }
                            else
                                pairToCheck.setStateInProgress(transactionID);
                        }
                    }
                    else
                    {
                        /*
                         * We are done sending checks for this list. We'll set
                         * its final state in either the processResponse(),
                         * processTimeout() or processFailure() method.
                         */
                        logger.finest("will skip a check beat.");
                        checkList.fireEndOfOrdinaryChecks();
                    }
                }
            }
            finally
            {
                synchronized (paceMakers)
                {
                    synchronized(this)
                    {
                        paceMakers.remove(this);
                    }
                }
            }
        }
    }

    /**
     * Stops and removes all <tt>PaceMaker</tt>s.
     */
    public void stop()
    {
        synchronized (paceMakers)
        {
            Iterator<PaceMaker> paceMakersIter = paceMakers.iterator();

            while(paceMakersIter.hasNext())
            {
                PaceMaker paceMaker = paceMakersIter.next();

                synchronized(paceMaker)
                {
                    paceMaker.running = false;
                    paceMaker.notify();
                }

                paceMakersIter.remove();
            }
        }
    }
}
