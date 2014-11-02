/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the SIP Communicator community (http://sip-communicator.org).
 *
 * Distributable under LGPL license. See terms of license at gnu.org.
 */
package org.ice4j.ice.harvest;

import java.io.*;
import java.lang.ref.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;
import java.util.logging.*;

import org.ice4j.*;
import org.ice4j.attribute.*;
import org.ice4j.ice.*;
import org.ice4j.message.*;
import org.ice4j.socket.*;

/**
 * A <tt>CandidateHarvester</tt> implementation, which listens on a specified
 * list of TCP server sockets. On {@link #harvest(org.ice4j.ice.Component)}, a
 * TCP candidate with type "passive" is added for each of the server sockets.
 *
 * This instance runs two threads: {@link #acceptThread} and
 * {@link #readThread}. The 'accept' thread just accepts new <tt>Socket</tt>s
 * and passes them over to the 'read' thread. The 'read' thread reads a STUN
 * message from an accepted socket and, based on the STUN username, passes it
 * to the appropriate <tt>Component</tt>.
 *
 * @author Boris Grozev
 */
public class MultiplexingTcpHostHarvester
    extends CandidateHarvester
{
    /**
     * Our class logger.
     */
    private static final Logger logger
        = Logger.getLogger(MultiplexingTcpHostHarvester.class.getName());

    /**
     * The constant which specifies how often to perform purging on
     * {@link #components}.
     */
    private static final int PURGE_INTERVAL = 20;

    /**
     * Channels which we have failed to read from after at least
     * <tt>READ_TIMEOUT</tt> milliseconds will be considered failed and will
     * be closed.
     */
    private static final int READ_TIMEOUT = 10000;

    /**
     * Returns a list of all addresses on the interfaces in <tt>interfaces</tt>
     * which are found suitable for candidate allocations (are not loopback, are
     * up, and are allowed by the configuration.
     *
     * @param port the port to use.
     * @param interfaces the list of interfaces to use.
     */
    private static List<TransportAddress> getLocalAddresses(
            int port,
            List<NetworkInterface> interfaces)
        throws IOException

    {
        List<TransportAddress> addresses = new LinkedList<TransportAddress>();

        for (NetworkInterface iface : interfaces)
        {
            if (NetworkUtils.isInterfaceLoopback(iface)
                    || !NetworkUtils.isInterfaceUp(iface)
                    || !HostCandidateHarvester.isInterfaceAllowed(iface))
            {
                //this one is obviously not going to do
                continue;
            }

            Enumeration<InetAddress> ifaceAddresses = iface.getInetAddresses();

            while(ifaceAddresses.hasMoreElements())
            {
                InetAddress addr = ifaceAddresses.nextElement();

                addresses.add(new TransportAddress(addr, port, Transport.TCP));
            }
        }
        return addresses;
    }

    /**
     * The thread which <tt>accept</tt>s TCP connections from the sockets in
     * {@link #serverSocketChannels}.
     */
    private AcceptThread acceptThread;

    /**
     * Triggers the termination of the threads of this instance.
     */
    private boolean close = false;

    /**
     * Maps a local "ufrag" to the single <tt>Component</tt> instance with that
     * "ufrag".
     *
     * We only keep weak references, because we do not want to prevent
     * <tt>Component</tt>s from being freed.
     */
    private final Map<String, WeakReference<Component>> components
        = new HashMap<String, WeakReference<Component>>();

    /**
     * The list of transport addresses which we have found to be listening on,
     * and which we will advertise as candidates in
     * {@link #harvest(org.ice4j.ice.Component)}
     */
    private final List<TransportAddress> localAddresses
        = new LinkedList<TransportAddress>();

    /**
     * Maps a public address to a local address.
     */
    private final Map<InetAddress, InetAddress> mappedAddresses
        = new HashMap<InetAddress, InetAddress>();

    /**
     * Sets of additional ports, for which server reflexive candidates will be
     * added.
     */
    private final Set<Integer> mappedPorts = new HashSet<Integer>();

    /**
     * Channels pending to be added to the list that {@link #readThread} reads
     * from.
     */
    private final List<SocketChannel> newChannels
        = new LinkedList<SocketChannel>();

    /**
     * A counter used to decide when to purge {@link #components}.
     */
    private int purgeCounter = 0;

    /**
     * The <tt>Selector</tt> used by {@link #readThread}.
     */
    private final Selector readSelector = Selector.open();

    /**
     * The thread which reads from the already <tt>accept</tt>ed sockets.
     */
    private ReadThread readThread;

    /**
     * The list of <tt>ServerSocketChannel</tt>s that we will <tt>accept</tt>
     * on.
     */
    private final List<ServerSocketChannel> serverSocketChannels
        = new LinkedList<ServerSocketChannel>();

    /**
     * Whether or not to use ssltcp.
     */
    private final boolean ssltcp;

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on port number <tt>port</tt> on all IP addresses on all available
     * interfaces.
     *
     * @param port the port to listen on.
     */
    public MultiplexingTcpHostHarvester(int port)
        throws IOException
    {
        this(port, /* ssltcp */ false);
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on port number <tt>port</tt> on all IP addresses on all available
     * interfaces.
     *
     * @param port the port to listen on.
     * @param ssltcp <tt>true</tt> to use ssltcp; otherwise, <tt>false</tt>
     */
    public MultiplexingTcpHostHarvester(int port, boolean ssltcp)
            throws IOException
    {
        this(port,
             Collections.list(NetworkInterface.getNetworkInterfaces()),
             ssltcp);
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on port number <tt>port</tt> on all the IP addresses on the
     * specified <tt>NetworkInterface</tt>s.
     *
     * @param port the port to listen on.
     * @param interfaces the interfaces to listen on.
     * @param ssltcp <tt>true</tt> to use ssltcp; otherwise, <tt>false</tt>
     */
    public MultiplexingTcpHostHarvester(int port,
                                        List<NetworkInterface> interfaces,
                                        boolean ssltcp)
        throws IOException
    {
        this(getLocalAddresses(port, interfaces), ssltcp);
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on the specified list of <tt>TransportAddress</tt>es.
     *
     * @param transportAddresses the transport addresses to listen on.
     */
    public MultiplexingTcpHostHarvester(
            List<TransportAddress> transportAddresses)
        throws IOException
    {
        this(transportAddresses, /* ssltcp */ false);
    }

    /**
     * Initializes a new <tt>MultiplexingTcpHostHarvester</tt>, which is to
     * listen on the specified list of <tt>TransportAddress</tt>es.
     *
     * @param transportAddresses the transport addresses to listen on.
     * @param ssltcp <tt>true</tt> to use ssltcp; otherwise, <tt>false</tt>
     */
    public MultiplexingTcpHostHarvester(
            List<TransportAddress> transportAddresses,
            boolean ssltcp)
        throws IOException
    {
        this.ssltcp = ssltcp;
        addLocalAddresses(transportAddresses);
        init();
    }

    /**
     * Adds to {@link #localAddresses} those addresses from
     * <tt>transportAddresses</tt> which are found suitable for candidate
     * allocation.
     *
     * @param transportAddresses the list of addresses to add.
     */
    private void addLocalAddresses(List<TransportAddress> transportAddresses)
        throws IOException
    {
        boolean useIPv6 = !StackProperties.getBoolean(
                StackProperties.DISABLE_IPv6,
                false);

        boolean useIPv6LinkLocal = !StackProperties.getBoolean(
                StackProperties.DISABLE_LINK_LOCAL_ADDRESSES,
                false);

        // White list from the configuration
        String[] allowedAddressesStr
            = StackProperties.getStringArray(StackProperties.ALLOWED_ADDRESSES,
                                             ";");
        InetAddress[] allowedAddresses = null;
        if (allowedAddressesStr != null)
        {
            allowedAddresses = new InetAddress[allowedAddressesStr.length];
            for (int i = 0; i < allowedAddressesStr.length; i++)
            {
                allowedAddresses[i]
                    = InetAddress.getByName(allowedAddressesStr[i]);
            }
        }

        // Black list from the configuration
        String[] blockedAddressesStr
            = StackProperties.getStringArray(StackProperties.BLOCKED_ADDRESSES,
                                             ";");
        InetAddress[] blockedAddresses = null;
        if (blockedAddressesStr != null)
        {
            blockedAddresses = new InetAddress[blockedAddressesStr.length];
            for (int i = 0; i < blockedAddressesStr.length; i++)
            {
                blockedAddresses[i]
                    = InetAddress.getByName(blockedAddressesStr[i]);
            }
        }

        for (TransportAddress transportAddress : transportAddresses)
        {
            InetAddress address = transportAddress.getAddress();

            if (address.isLoopbackAddress())
            {
                //loopback again
                continue;
            }

            if (!useIPv6 && (address instanceof Inet6Address))
                continue;

            if (!useIPv6LinkLocal
                    && (address instanceof Inet6Address)
                    && address.isLinkLocalAddress())
            {
                logger.info("Not using link-local address " + address +" for"
                                    + " TCP candidates.");
                continue;
            }

            if (allowedAddresses != null)
            {
                boolean found = false;
                for (InetAddress allowedAddress : allowedAddresses)
                {
                    if (allowedAddress.equals(address))
                    {
                        found = true;
                        break;
                    }
                }

                if (!found)
                {
                    logger.info("Not using " + address +" for TCP candidates, "
                                + "because it is not in the allowed list.");
                    continue;
                }
            }

            if (blockedAddresses != null)
            {
                boolean found = false;
                for (InetAddress blockedAddress : blockedAddresses)
                {
                    if (blockedAddress.equals(address))
                    {
                        found = true;
                        break;
                    }
                }

                if (found)
                {
                    logger.info("Not using " + address + " for TCP candidates, "
                                + "because it is in the blocked list.");
                    continue;
                }
            }

            // Passed all checks
            localAddresses.add(transportAddress);
        }
    }

    /**
     * Adds a mapping between <tt>publicAddress</tt> and <tt>localAddress</tt>.
     * This means that on harvest, along with any host candidates that have
     * <tt>publicAddress</tt>, a server reflexive candidate will be added (with
     * the same port as the host candidate).
     *
     * @param publicAddress the public address.
     * @param localAddress the local address.
     */
    public void addMappedAddress(InetAddress publicAddress,
                                 InetAddress localAddress)
    {
        mappedAddresses.put(publicAddress, localAddress);
    }

    /**
     * Adds port as an additional port. When harvesting, additional server
     * reflexive candidates will be added with this port.
     *
     * @param port the port to add.
     */
    public void addMappedPort(int port)
    {
        mappedPorts.add(port);
    }

    /**
     * Triggers the termination of the threads of this
     * <tt>MultiplexingTcpHarvester</tt>.
     */
    public void close()
    {
        close = true;
    }

    /**
     * Creates and returns the list of <tt>LocalCandidate</tt>s which are to be
     * added by this <tt>MultiplexingTcpHostHarvester</tt> to a specific
     * <tt>Component</tt>.
     *
     * @param component the <tt>Component</tt> for which to create candidates.
     * @return the list of <tt>LocalCandidate</tt>s which are to be added by
     * this <tt>MultiplexingTcpHostHarvester</tt> to a specific
     * <tt>Component</tt>.
     */
    private List<LocalCandidate> createLocalCandidates(Component component)
    {
        List<TcpHostCandidate> hostCandidates
            = new LinkedList<TcpHostCandidate>();

        // Add the host candidates for the addresses we really listen on
        for (TransportAddress transportAddress : localAddresses)
        {
            TcpHostCandidate candidate
                = new TcpHostCandidate(transportAddress, component);

            candidate.setTcpType(CandidateTcpType.PASSIVE);
            if (ssltcp)
                candidate.setSSL(true);

            hostCandidates.add(candidate);
        }

        // Add srflx candidates for any mapped addresses
        List<LocalCandidate> mappedCandidates
            = new LinkedList<LocalCandidate>();

        for (Map.Entry<InetAddress, InetAddress> mapping
                : mappedAddresses.entrySet())
        {
            InetAddress localAddress = mapping.getValue();

            for (TcpHostCandidate base : hostCandidates)
            {
                TransportAddress baseTransportAddress
                    = base.getTransportAddress();

                if (localAddress.equals(baseTransportAddress.getAddress()))
                {
                    InetAddress publicAddress = mapping.getKey();
                    ServerReflexiveCandidate mappedCandidate
                        = new ServerReflexiveCandidate(
                            new TransportAddress(publicAddress,
                                                 baseTransportAddress.getPort(),
                                                 Transport.TCP),
                            base,
                            base.getStunServerAddress(),
                            CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE);

                    if (base.isSSL())
                        mappedCandidate.setSSL(true);
                    mappedCandidate.setTcpType(CandidateTcpType.PASSIVE);

                    mappedCandidates.add(mappedCandidate);
                }
            }
        }

        // Add srflx candidates for mapped ports
        List<LocalCandidate> portMappedCandidates
            = new LinkedList<LocalCandidate>();

        for (TcpHostCandidate base : hostCandidates)
        {
            for (Integer port : mappedPorts)
            {
                ServerReflexiveCandidate portMappedCandidate
                    = new ServerReflexiveCandidate(
                        new TransportAddress(
                            base.getTransportAddress().getAddress(),
                            port,
                            Transport.TCP),
                        base,
                        base.getStunServerAddress(),
                        CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE);

                if (base.isSSL())
                    portMappedCandidate.setSSL(true);
                portMappedCandidate.setTcpType(CandidateTcpType.PASSIVE);

                portMappedCandidates.add(portMappedCandidate);
            }
        }
        // Mapped ports for mapped addresses
        for (LocalCandidate mappedCandidate : mappedCandidates)
        {
            TcpHostCandidate base
                = (TcpHostCandidate) mappedCandidate.getBase();

            for (Integer port : mappedPorts)
            {
                ServerReflexiveCandidate portMappedCandidate
                    = new ServerReflexiveCandidate(
                        new TransportAddress(
                                mappedCandidate.getTransportAddress()
                                        .getAddress(),
                                port,
                                Transport.TCP),
                        base,
                        base.getStunServerAddress(),
                        CandidateExtendedType.STATICALLY_MAPPED_CANDIDATE);

                if (base.isSSL())
                    portMappedCandidate.setSSL(true);
                portMappedCandidate.setTcpType(CandidateTcpType.PASSIVE);

                portMappedCandidates.add(portMappedCandidate);
            }
        }

        LinkedList<LocalCandidate> allCandidates
            = new LinkedList<LocalCandidate>();

        allCandidates.addAll(hostCandidates);
        allCandidates.addAll(mappedCandidates);
        allCandidates.addAll(portMappedCandidates);
        return allCandidates;
    }

    /**
     * Returns the <tt>Component</tt> instance, if any, for a given local
     * &quot;ufrag&quot;.
     *
     * @param localUfrag the local &quot;ufrag&quot;
     * @return the <tt>Component</tt> instance, if any, for a given local
     * &quot;ufrag&quot;.
     */
    private Component getComponent(String localUfrag)
    {
        synchronized (components)
        {
            WeakReference<Component> wr = components.get(localUfrag);

            if (wr != null)
            {
                Component component = wr.get();

                if (component == null)
                {
                    components.remove(localUfrag);
                }

                return component;
            }
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * Saves a (weak) reference to <tt>Component</tt>, so that it can be
     * notified if/when a socket for one of it <tt>LocalCandidate</tt>s is
     * accepted.
     * <p>
     * The method does not perform any network operations and should return
     * quickly.
     * </p>
     */
    @Override
    public Collection<LocalCandidate> harvest(Component component)
    {
        IceMediaStream stream = component.getParentStream();
        Agent agent = stream.getParentAgent();

        if (stream.getComponentCount() != 1 || agent.getStreamCount() != 1)
        {
            /*
             * MultiplexingTcpHostHarvester only works with streams with a
             * single component, and agents with a single stream. This is
             * because we use the local "ufrag" to de-multiplex the accept()-ed
             * sockets between the known components.
             */
            throw new IllegalStateException(
                    "More than one Component for an Agent, cannot harvest.");
        }

        List<LocalCandidate> candidates = createLocalCandidates(component);

        for (LocalCandidate candidate : candidates)
            component.addLocalCandidate(candidate);

        synchronized (components)
        {
            components.put(agent.getLocalUfrag(),
                           new WeakReference<Component>(component));
            purgeComponents();
        }

        return candidates;
    }

    /**
     * Initializes {@link #serverSocketChannels}, creates and starts the threads
     * used by this instance.
     */
    private void init()
        throws IOException
    {
        for (TransportAddress transportAddress : localAddresses)
        {
            ServerSocketChannel channel = ServerSocketChannel.open();
            ServerSocket socket = channel.socket();
            socket.bind(
                    new InetSocketAddress(transportAddress.getAddress(),
                                          transportAddress.getPort()));
            serverSocketChannels.add(channel);
        }

        acceptThread = new AcceptThread();
        acceptThread.start();

        readThread = new ReadThread();
        readThread.start();
    }

    /**
     * Removes entries from {@link #components} for which the
     * <tt>WeakReference</tt> has been cleared.
     */
    private void purgeComponents()
    {
        ++purgeCounter;
        if (purgeCounter % PURGE_INTERVAL == 0)
        {
            synchronized (components)
            {
                for (Iterator<WeakReference<Component>> i
                            = components.values().iterator();
                        i.hasNext();)
                {
                    if (i.next().get() == null)
                        i.remove();
                }
            }
        }
    }

    /**
     * A <tt>Thread</tt> which will accept new <tt>SocketChannel</tt>s from all
     * <tt>ServerSocketChannel</tt>s in {@link #serverSocketChannels}.
     */
    private class AcceptThread
        extends Thread
    {
        /**
         * The <tt>Selector</tt> used to select a specific
         * <tt>ServerSocketChannel</tt> which is ready to <tt>accept</tt>.
         */
        private final Selector selector;

        /**
         * Initializes a new <tt>AcceptThread</tt>.
         */
        private AcceptThread()
            throws IOException
        {
            setName("MultiplexingTcpHostHarvester AcceptThread");
            setDaemon(true);

            selector = Selector.open();
            for (ServerSocketChannel channel : serverSocketChannels)
            {
                channel.configureBlocking(false);
                channel.register(selector, SelectionKey.OP_ACCEPT);
            }
        }

        /**
         * Notifies {@link #readThread} that new channels have been added to
         */
        private void notifyReadThread()
        {
            readSelector.wakeup();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run()
        {
            while (true)
            {
                int readyChannels;
                if (close)
                {
                    break;
                }

                try
                {
                    // Allow to go on, so we can quit if closed
                    readyChannels = selector.select(3000);
                }
                catch (IOException ioe)
                {
                    logger.info("Failed to select an accept-ready socket: "
                                    + ioe);
                    break;
                }

                if (readyChannels > 0)
                {
                    IOException exception = null;
                    List<SocketChannel> channelsToAdd
                            = new LinkedList<SocketChannel>();

                    for (SelectionKey key : selector.selectedKeys())
                    {
                        SocketChannel channel;
                        if (key.isAcceptable())
                        {
                            try
                            {
                                channel = ((ServerSocketChannel)
                                    key.channel()).accept();
                            }
                            catch (IOException ioe)
                            {
                                exception = ioe;
                                break;
                            }

                            // Add the accepted socket to newChannels, so
                            // the 'read' thread can pick it up.
                            channelsToAdd.add(channel);
                        }
                    }
                    selector.selectedKeys().clear();

                    if (!channelsToAdd.isEmpty())
                    {
                        synchronized (newChannels)
                        {
                            newChannels.addAll(channelsToAdd);
                        }
                        notifyReadThread();
                    }

                    if (exception != null)
                    {
                        logger.info("Failed to accept a socket, which"
                                        + "should have been ready to accept: "
                                        + exception);
                        break;
                    }
                }
            } // while(true)

            //now clean up and exit
            for (ServerSocketChannel serverSocketChannel : serverSocketChannels)
            {
                try
                {
                    serverSocketChannel.close();
                }
                catch (IOException ioe)
                {
                }
            }

            try
            {
                selector.close();
            }
            catch (IOException ioe)
            {}
        }
    }

    /**
     * An <tt>IceSocketWrapper</tt> implementation which allows a
     * <tt>DatagramPacket</tt> to be pushed back and received on the first call
     * to {@link #receive(java.net.DatagramPacket)}.
     */
    private static class PushBackIceSocketWrapper
        extends IceSocketWrapper
    {
        /**
         * The <tt>DatagramPacket</tt> which will be used on the first call to
         * {@link #receive(java.net.DatagramPacket)}.
         */
        private DatagramPacket datagramPacket;

        /**
         * The <tt>IceSocketWrapper</tt> that this instance wraps around.
         */
        private final IceSocketWrapper wrapped;

        /**
         * Initializes a new <tt>PushBackIceSocketWrapper</tt> instance that
         * wraps around <tt>wrappedWrapper</tt> and reads from
         * <tt>datagramSocket</tt> on the first call to
         * {@link #receive(java.net.DatagramPacket)}
         *
         * @param wrappedWrapper the <tt>IceSocketWrapper</tt> instance that we
         * wrap around.
         * @param datagramPacket the <tt>DatagramPacket</tt> which will be used
         * on the first call to {@link #receive(java.net.DatagramPacket)}
         */
        private PushBackIceSocketWrapper(IceSocketWrapper wrappedWrapper,
                                         DatagramPacket datagramPacket)
        {
            this.wrapped = wrappedWrapper;
            this.datagramPacket = datagramPacket;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void close()
        {
            wrapped.close();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public InetAddress getLocalAddress()
        {
            return wrapped.getLocalAddress();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public int getLocalPort()
        {
            return wrapped.getLocalPort();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public SocketAddress getLocalSocketAddress()
        {
            return wrapped.getLocalSocketAddress();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public Socket getTCPSocket()
        {
            return wrapped.getTCPSocket();
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public DatagramSocket getUDPSocket()
        {
            return wrapped.getUDPSocket();
        }

        /**
         * {@inheritDoc}
         *
         * On the first call to this instance reads from
         * {@link #datagramPacket}, on subsequent calls delegates to
         * {@link #wrapped}.
         */
        @Override
        public void receive(DatagramPacket p) throws IOException
        {
            if (datagramPacket != null)
            {
                int len = Math.min(p.getLength(), datagramPacket.getLength());
                System.arraycopy(datagramPacket.getData(), 0,
                                 p.getData(), 0,
                                 len);
                p.setAddress(datagramPacket.getAddress());
                p.setPort(datagramPacket.getPort());
                datagramPacket = null;
            }
            else
            {
                wrapped.receive(p);
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void send(DatagramPacket p) throws IOException
        {
            wrapped.send(p);
        }
    }

    private class ReadThread
        extends Thread
    {
        /**
         * Contains the <tt>SocketChanel</tt>s that we are currently reading
         * from.
         */
        private final List<ChannelDesc> channels
            = new LinkedList<ChannelDesc>();

        /**
         * Initializes a new <tt>ReadThread</tt>.
         *
         * @throws IOException if the selector to be used fails to open.
         */
        private ReadThread()
            throws IOException
        {
            setName("MultiplexingTcpHostHarvester ReadThread");
            setDaemon(true);
        }

        /**
         * Adds the channels from {@link #newChannels} to {@link #channels} and
         * registers them in {@link #readSelector}.
         */
        private void checkForNewChannels()
        {
            synchronized (newChannels)
            {
                for (SocketChannel channel : newChannels)
                {
                    try
                    {
                        channel.configureBlocking(false);
                        channel.register(readSelector, SelectionKey.OP_READ);
                    }
                    catch (IOException ioe)
                    {
                        logger.info("Failed to register channel: " + ioe);
                        try
                        {
                            channel.close();
                        }
                        catch (IOException ioe2)
                        {}
                    }

                    channels.add(new ChannelDesc(channel));
                }
                newChannels.clear();
            }
        }

        /**
         * Checks {@link #channels} for channels which have been added over
         * {@link #READ_TIMEOUT} milliseconds ago and closes them.
         */
        private void cleanup()
        {
            long now = System.currentTimeMillis();

            for (Iterator<ChannelDesc> i = channels.iterator();
                 i.hasNext();)
            {
                ChannelDesc channelDesc = i.next();

                if (channelDesc.lastActive != -1
                        && now - channelDesc.lastActive > READ_TIMEOUT)
                {
                    i.remove();
                    logger.info("Read timeout for socket: "
                                        + channelDesc.channel.socket());

                    // De-register from the selector
                    for (SelectionKey key : readSelector.keys())
                    {
                        if (key.channel().equals(channelDesc.channel))
                        {
                            key.cancel();
                        }
                    }

                    try
                    {
                        channelDesc.channel.close();
                    }
                    catch (IOException ioe)
                    {
                        logger.info("Failed to close channel: " + ioe);
                    }
                }
            }
        }

        /**
         * Searches among the local candidates of <tt>Component</tt> for a
         * <tt>TcpHostCandidate</tt> with the same transport address as the
         * local transport address of <tt>socket</tt>.
         *
         * We expect to find such a candidate, which has been added by this
         * <tt>MultiplexingTcpHostHarvester</tt> while harvesting.
         *
         * @param component the <tt>Component</tt> to search.
         * @param socket the <tt>Socket</tt> to match the local transport
         * address of.
         * @return a <tt>TcpHostCandidate</tt> among the local candidates of
         * <tt>Component</tt> with the same transport address as the local
         * address of <tt>Socket</tt>, or <tt>null</tt> if no such candidate
         * exists.
         */
        private TcpHostCandidate findCandidate(
                Component component,
                Socket socket)
        {
            InetAddress localAddress = socket.getLocalAddress();
            int localPort = socket.getLocalPort();

            for (LocalCandidate candidate : component.getLocalCandidates())
            {
                TransportAddress transportAddress
                    = candidate.getTransportAddress();
                if (candidate instanceof TcpHostCandidate
                        && Transport.TCP.equals(transportAddress.getTransport())
                        && localPort == transportAddress.getPort()
                        && localAddress.equals(transportAddress.getAddress()))
                {
                    return (TcpHostCandidate) candidate;
                }
            }
            return null;
        }

        /**
         * Finds the <tt>ChannelDesc</tt> in {@link #channels} whose
         * channel is <tt>channel</tt>.
         *
         * @param channel the channel to search for.
         * @return the <tt>ChannelDesc</tt> in {@link #channels} whose
         * channel is <tt>channel</tt>
         */
        private ChannelDesc findChannelDesc(SelectableChannel channel)
        {
            for (ChannelDesc channelDesc : channels)
            {
                if (channelDesc.channel.equals(channel))
                    return channelDesc;
            }

            return null;
        }

        /**
         * Makes <tt>socket</tt> available to <tt>component</tt> and pushes back
         * <tt>datagramPacket</tt> into the STUN socket.
         *
         * @param socket the <tt>Socket</tt>.
         * @param component the <tt>Component</tt>.
         * @param datagramPacket the <tt>DatagramPacket</tt> to push back.
         */
        private void handSocketToComponent(Socket socket,
                                           Component component,
                                           DatagramPacket datagramPacket)
        {
            IceProcessingState state
                = component.getParentStream().getParentAgent().getState();
            if (!IceProcessingState.WAITING.equals(state)
                    && !IceProcessingState.RUNNING.equals(state))
            {
                logger.info("Not adding a socket to an ICE agent with state "
                                + state);
                return;
            }

            // Socket to add to the candidate
            IceSocketWrapper candidateSocket = null;

            // STUN-only filtered socket to add to the StunStack
            IceSocketWrapper stunSocket = null;

            try
            {
                MultiplexingSocket multiplexing = new MultiplexingSocket(socket);
                candidateSocket = new IceTcpSocketWrapper(multiplexing);

                stunSocket
                    = new IceTcpSocketWrapper(
                        multiplexing.getSocket(new StunDatagramPacketFilter()));
                stunSocket
                    = new PushBackIceSocketWrapper(stunSocket, datagramPacket);
            }
            catch (IOException ioe)
            {
                logger.info("Failed to create sockets: " + ioe);
            }

            TcpHostCandidate candidate = findCandidate(component, socket);
            if (candidate != null)
            {
                component.getParentStream().getParentAgent().getStunStack()
                    .addSocket(stunSocket);
                candidate.addSocket(candidateSocket);

                // the socket is not our responsibility anymore. It is up to
                // the candidate/component to close/free it.
            }
            else
            {
                logger.info("Failed to find the local candidate for socket: "
                                    + socket);
                try
                {
                    socket.close();
                }
                catch (IOException ioe)
                {}
            }

        }

        /**
         * Tries to read, without blocking, from <tt>channel</tt> to its
         * buffer. If after reading the buffer is filled, handles the data in
         * the buffer.
         *
         * This works in three stages:
         * 1 (optional, only if ssltcp is enabled): Read a fixed-size message.
         * If it matches the hard-coded pseudo SSL ClientHello, sends the
         * hard-coded ServerHello.
         * 2: Read two bytes as an unsigned int and interpret it as the length
         * to read in the next stage.
         * 3: Read number of bytes indicated in stage2 and try to interpret
         * them as a STUN message.
         *
         * If a STUN message is successfully read, and it contains a USERNAME
         * attribute, the local &quot;ufrag&quot; is extracted from the
         * attribute value and the socket is passed on to the <tt>Component</tt>
         * that this <tt>MultiplexingTcpHostHarvester</tt> has associated with
         * that &quot;ufrag&quot;.
         *
         * @param channel the <tt>SocketChannel</tt> to read from.
         * @param key the <tt>SelectionKey</tt> associated with
         * <tt>channel</tt>, which is to be canceled in case no further
         * reading is required from the channel.
         */
        private void readFromChannel(ChannelDesc channel,
                                     SelectionKey key)
        {
            if (channel.buffer == null)
            {
                // Set up a buffer with a pre-determined size

                if (ssltcp && !channel.sslHandshakeRead)
                {
                    channel.buffer
                            = ByteBuffer.allocate(
                            GoogleTurnSSLCandidateHarvester
                                    .SSL_CLIENT_HANDSHAKE.length);
                }
                else if (channel.length == -1)
                {
                    channel.buffer = ByteBuffer.allocate(2);
                }
                else
                {
                    channel.buffer = ByteBuffer.allocate(channel.length);
                }
            }

            try
            {
                if (channel.channel.read(channel.buffer) == -1)
                {
                    throw new IOException("Socket closed.");
                }

                if (!channel.buffer.hasRemaining())
                {
                    // We've filled in the buffer.
                    if (ssltcp && !channel.sslHandshakeRead)
                    {
                        byte[] bytesRead
                                = new byte[GoogleTurnSSLCandidateHarvester
                                .SSL_CLIENT_HANDSHAKE.length];

                        channel.buffer.flip();
                        channel.buffer.get(bytesRead);

                        // Set to null, so that we re-allocate it for the next
                        // stage
                        channel.buffer = null;
                        channel.sslHandshakeRead = true;

                        if (Arrays.equals(bytesRead,
                                          GoogleTurnSSLCandidateHarvester
                                                  .SSL_CLIENT_HANDSHAKE))
                        {
                            ByteBuffer byteBuffer = ByteBuffer.wrap(
                                    GoogleTurnSSLCandidateHarvester
                                            .SSL_SERVER_HANDSHAKE);
                            channel.channel.write(byteBuffer);
                        }
                        else
                        {
                            throw new IOException("Expected a pseudo ssl"
                                + " handshake, but received something else.");
                        }

                    }
                    else if (channel.length == -1)
                    {
                        channel.buffer.flip();
                        int fb = channel.buffer.get();
                        int sb = channel.buffer.get();

                        channel.length = (((fb & 0xff) << 8) | (sb & 0xff));

                        // Set to null, so that we re-allocate it for the next
                        // stage
                        channel.buffer = null;
                    }
                    else
                    {
                        byte[] bytesRead = new byte[channel.length];
                        channel.buffer.flip();
                        channel.buffer.get(bytesRead);

                        // Does this look like a STUN binding request?
                        // What's the username?
                        Message stunMessage
                            = Message.decode(bytesRead,
                                             (char) 0,
                                             (char) bytesRead.length);

                        if (stunMessage.getMessageType()
                                != Message.BINDING_REQUEST)
                        {
                            throw new IOException("Not a binding request");
                        }

                        UsernameAttribute usernameAttribute
                                = (UsernameAttribute)
                                stunMessage.getAttribute(Attribute.USERNAME);
                        if (usernameAttribute == null)
                            throw new IOException(
                                    "No USERNAME attribute present.");

                        String usernameString
                                = new String(usernameAttribute.getUsername());
                        String localUfrag = usernameString.split(":")[0];
                        Component component = getComponent(localUfrag);
                        if (component == null)
                            throw new IOException("No component found.");

                        // The rest of the stack will read from the socket's
                        // InputStream. We cannot change the blocking mode
                        // bore the channel is removed from the selector (by
                        // cancelling the key)
                        key.cancel();
                        channel.channel.configureBlocking(true);

                        // Construct a DatagramPacket from the just-read packet
                        // which is to be pushed pack
                        DatagramPacket p
                                = new DatagramPacket(bytesRead,
                                                     bytesRead.length);
                        Socket socket = channel.channel.socket();
                        p.setAddress(socket.getInetAddress());
                        p.setPort(socket.getPort());

                        handSocketToComponent(socket, component, p);

                        // Successfully accepted, we don't need it anymore.
                        channels.remove(channel);
                    }
                }
            }
            catch (IOException ioe)
            {
                logger.info("Failed to handle TCP socket "
                                    + channel.channel.socket() + ": " + ioe);
                channels.remove(channel);
                key.cancel();
                try
                {
                    channel.channel.close();
                }
                catch (IOException ioe2)
                {}
            }
            catch (StunException se)
            {
                logger.info("Failed to handle TCP socket "
                                    + channel.channel.socket() + ": " + se);
                channels.remove(channel);
                key.cancel();
                try
                {
                    channel.channel.close();
                }
                catch (IOException ioe)
                {}
            }
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public void run()
        {
            Set<SelectionKey> selectedKeys;
            int readyChannels = 0;
            SelectableChannel selectedChannel;

            while (true)
            {
                synchronized (MultiplexingTcpHostHarvester.this)
                {
                    if (close)
                        break;
                }

                // clean up stale channels
                cleanup();

                checkForNewChannels();

                try
                {
                    readyChannels = readSelector.select(READ_TIMEOUT / 2);
                }
                catch (IOException ioe)
                {
                    logger.info("Failed to select a read-ready channel.");
                }

                if (readyChannels > 0)
                {
                    selectedKeys = readSelector.selectedKeys();
                    for (SelectionKey key : selectedKeys)
                    {
                        if (key.isReadable())
                        {
                            selectedChannel = key.channel();

                            ChannelDesc channelDesc
                                    = findChannelDesc(selectedChannel);
                            channelDesc.lastActive = System.currentTimeMillis();

                            readFromChannel(channelDesc, key);
                        }
                    }
                    selectedKeys.clear();
                }
            } //while(true)

            //we are all done, clean up.
            synchronized (newChannels)
            {
                for (SocketChannel channel : newChannels)
                {
                    try
                    {
                        channel.close();
                    }
                    catch (IOException ioe)
                    {}
                }
                newChannels.clear();
            }

            for (ChannelDesc channelDesc : channels)
            {
                try
                {
                    channelDesc.channel.close();
                }
                catch (IOException ioe)
                {
                }
            }

            channels.clear();

            try
            {
                readSelector.close();
            }
            catch (IOException ioe)
            {}
        }

        /**
         * Contains a <tt>SocketChannel</tt> that <tt>ReadThread</tt> is
         * reading from.
         */
        private class ChannelDesc
        {
            /**
             * The actual <tt>SocketChannel</tt>.
             */
            private final SocketChannel channel;

            /**
             * The time the channel was last found to be active.
             */
            private long lastActive = System.currentTimeMillis();

            /**
             * The buffer which stores the data so far read from the channel.
             */
            ByteBuffer buffer = null;

            /**
             * Whether or not the initial "pseudo" SSL handshake has been read.
             */
            boolean sslHandshakeRead = false;

            /**
             * The value of the RFC4571 "length" field read from the channel, or
             * -1 if it hasn't (yet) been read.
             */
            int length = -1;

            /**
             * Initializes a new <tt>ChannelDesc</tt> with the given channel.
             * @param channel the channel.
             */
            private ChannelDesc(SocketChannel channel)
            {
                this.channel = channel;
            }
        }
    }
}
