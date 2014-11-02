/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 * Maintained by the Jitsi community (https://jitsi.org).
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.ice4j.pseudotcp;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.*;

class PseudoTcpSocketImpl 
    extends SocketImpl
    implements PseudoTcpNotify
{
    /**
     * The logger.
     */
    private static final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(PseudoTcpSocketImpl.class.getName());
    /**
     * Pseudotcp logic instance
     */
    private final PseudoTCPBase pseudoTcp;
    /**
     * Datagram socket used to handle network operations
     */
    private DatagramSocket socket;
    /**
     * Current socket address of remote socket that we are connected to
     */
    private SocketAddress remoteAddr;
    /**
     * Receive buffer size used for receiving packets TODO: this should be
     * checked with MTU ?
     */
    private int DATAGRAM_RCV_BUFFER_SIZE = 8000;
    /**
     * Monitor object used to block threads on write operation. That is when the
     * send buffer is full.
     */
    private final Object write_notify = new Object();
    /**
     * Monitor object used to block threads on read operation. That is when
     * there's no more data available for reading.
     */
    private final Object read_notify = new Object();
    /**
     * Monitor object used to block thread waiting for change of TCP state.
     */
    private final Object state_notify = new Object();

    /**
     * Exception which occurred in pseudotcp logic and must be propagated to
     * threads blocked on any operations.
     */
    private IOException exception;
    /**
     * Read operations timeout in ms
     */
    private long writeTimeout;
    /**
     * Write operations timeout in ms
     */
    private long readTimeout;

    
    /**
     * 
     */
    private PseudoTcpInputStream inputStream;
    
    /**
     * 
     */
    private PseudoTcpOutputStream outputstream;
    
    /**
     *
     * @param conv_id conversation id, must be the same on both sides
     * @param sock datagram socket used for network operations
     */
    public PseudoTcpSocketImpl(long conv_id, DatagramSocket sock)
    {
        pseudoTcp = new PseudoTCPBase(this, conv_id);
        //Default MTU
        setMTU(1450);
        this.socket = sock;
        //TODO: find out if this call is required
        /*try
        {
            setOption(SO_TIMEOUT, 0);
        }
        catch (SocketException e)
        {
            throw new RuntimeException(e);
        }*/
    }

    /**
     * This constructor creates <tt>DatagramSocket</tt> with random port. Should
     * be used for clients.
     *
     * @param conv_id conversation id, must be the same on both sides
     * @throws SocketException
     */
    public PseudoTcpSocketImpl(long conv_id)
        throws SocketException
    {
        this(conv_id, new DatagramSocket());
    }

    /**
     * Binds <tt>DatagramSocket</tt> to given <tt>local_port</tt>
     *
     * @param conv_id conversation id, must be the same on both sides
     * @param local_port the local port that will be used for this socket
     * @throws SocketException
     */
    public PseudoTcpSocketImpl(long conv_id, int local_port)
        throws SocketException
    {
        this(conv_id, new DatagramSocket(local_port));
    }

    /**
     * Creates DatagramSocket for <tt>local_ip</tt>:<tt>local_port</tt>
     *
     * @param conv_id conversation id, must be the same on both sides
     * @param local_ip used by <tt>DatagramSocket</tt>
     * @param local_port used by <tt>DatagramSocket</tt>
     * @throws SocketException
     * @throws UnknownHostException
     */
    public PseudoTcpSocketImpl(long conv_id, String local_ip, int local_port)
        throws SocketException,
               UnknownHostException
    {
        this(conv_id, new DatagramSocket(local_port,
                                         InetAddress.getByName(local_ip)));
    }
    
    /**
     * Sets the MTU parameter for this socket
     * @param mtu the MTU value
     */
    public void setMTU(int mtu)
    {
        this.pseudoTcp.notifyMTU(mtu);
    }
    
    /**
     * 
     * @return current MTU set
     */
    public int getMTU()
    {
        return pseudoTcp.getMTU();
    }
    
    long getConversationID()
    {
        return pseudoTcp.getConversationID();
    }
    
    void setConversationID(long convID)
    {
        pseudoTcp.setConversationID(convID);
    }
    
    /**
     * Sets debug name that will be displayed in log messages for this socket
     * @param debugName the debug name to set 
     */
    public void setDebugName(String debugName)
    {
        this.pseudoTcp.debugName = debugName;
    }

    /**
     * Creates either a stream or a datagram socket.
     * @param stream if true, create a stream socket; otherwise, create a datagram socket.
     * @throws IOException if an I/O error occurs while creating the socket.
     */
    protected void create(boolean stream) 
        throws IOException
    {
        //no effect        
    }

    /**
     * Connects this socket to the specified port on the named host.
     * @param host the name of the remote host.
     * @param port the port number.
     * @throws IOException 
     */
    protected void connect(String host, int port) 
        throws IOException
    {
        doConnect(new InetSocketAddress(InetAddress.getByName(host), port), 0);
    }

    /**
     * Connects this socket to the specified port number on the specified host.
     * @param address the IP address of the remote host.
     * @param port the port number.
     * @throws IOException if an I/O error occurs when attempting a connection.
     */
    protected void connect(InetAddress address, int port) 
        throws IOException
    {
        connect(address.getHostAddress(), port);
    }

    /**
     * Connects this socket to the specified port number on the specified host. 
     * A timeout of zero is interpreted as an infinite timeout. 
     * The connection will then block until established or an error occurs.
     * @param address the Socket address of the remote host.
     * @param timeout the timeout value, in milliseconds, or zero for no timeout.
     * @throws IOException if an I/O error occurs when attempting a connection.
     */
    protected void connect(SocketAddress address, int timeout) 
        throws IOException
    {
        InetSocketAddress inetAddr = (InetSocketAddress) address;
        doConnect(inetAddr, timeout);
    }

    /**
     * Binds this socket to the specified port number on the specified host.
     * @param host the IP address of the remote host.
     * @param port the port number.
     * @throws IOException 
     */
    public void bind(InetAddress host, int port) 
        throws IOException
    {
        if(socket != null)
            socket.close();
        InetSocketAddress newAddr = new InetSocketAddress(host.getHostAddress(),port);
        this.socket = new DatagramSocket(newAddr);
    }

    /**
     * Sets the maximum queue length for incoming connection 
     * indications (a request to connect) to the count argument. 
     * If a connection indication arrives when the queue is full,
     * the connection is refused.
     * @param backlog the maximum length of the queue.
     * @throws IOException if an I/O error occurs when creating the queue.
     */
    protected void listen(int backlog) 
        throws IOException
    {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    private Map<Integer, Object> options = new HashMap<Integer,Object>();   
    public void setOption(int optID, Object value) 
        throws SocketException
    {
        //TODO: map options to PTCP options/method calls
        options.put(optID, value);
    }

    public Object getOption(int optID) 
        throws SocketException
    {
        //TODO: map options to PTCP options/method calls
		if(optID == SocketOptions.TCP_NODELAY) 
        {
			Object ret = options.get(Option.OPT_NODELAY.ordinal());
			return ret != null;
		}

		Object option = options.get(optID);
		if(option == null) 
        {
			logger.warning("Asked for unknown optID" + optID);
		}
		return option;
    }
    
    public long getPTCPOption(Option opt)
    {
        if(Option.OPT_READ_TIMEOUT == opt)
        {
            return this.readTimeout;
        }
        else if(Option.OPT_WRITE_TIMEOUT == opt)
        {
            return this.writeTimeout;
        }
        else
        {
            return pseudoTcp.getOption(opt);
        }
    }
    
    public void setPTCPOption(Option opt, long optValue)
    {
        if(Option.OPT_WRITE_TIMEOUT == opt)
        {
            this.writeTimeout = optValue >= 0 ? optValue : 0;
        }
        else if(Option.OPT_READ_TIMEOUT == opt)
        {
            this.readTimeout = optValue >= 0 ? optValue : 0;
        }
        else
        {
            pseudoTcp.setOption(opt, optValue);
        }
    }
    
    
    /**
     * Start connection procedure
     *
     * @param remoteAddress to which this socket connects to
     * @param timeout for this operation in ms
     * @throws IOException
     */
    void doConnect(InetSocketAddress remoteAddress, long timeout)
        throws IOException
    {
        logger.fine("Connecting to "+remoteAddress);
        this.remoteAddr = remoteAddress;
        startThreads();
        pseudoTcp.connect();
        updateClock();
        boolean noTimeout = timeout <= 0;
        try
        {
            long elapsed = 0;
            //Here the threads is blocked untill we reach TCP_ESTABLISHED state
            //There's also check for timeout for that op
            synchronized (state_notify)
            {
                while (pseudoTcp.getState() != PseudoTcpState.TCP_ESTABLISHED
                    &&  (noTimeout ? true : (elapsed < timeout)) )
                {
                    long start = System.currentTimeMillis();
                    state_notify.wait(timeout);
                    long end = System.currentTimeMillis();
                    elapsed += end - start;
                }
                if (pseudoTcp.getState() != PseudoTcpState.TCP_ESTABLISHED)
                {
                    throw new IOException("Connect timeout");
                }
            }
        }
        catch (InterruptedException ex)
        {
            close();
            throw new IOException("Connect aborted");
        }
    }

    /**
     * Blocking method waits for connection.
     *
     * @param remoteAddress the one and only address that will be
     *                      accepted as the source for remote packets
     * @param timeout for this operation in ms
     * @throws IOException If socket gets closed or timeout expires
     */
    void accept(SocketAddress remoteAddress, int timeout)
        throws IOException
    {
        this.remoteAddr = remoteAddress;
        accept(timeout);
    }


    /**
     * Blocking method waits for connection.
     *
     * @param timeout for this operation in ms
     * @throws IOException If socket gets closed or timeout expires
     */
    void accept(int timeout)
        throws IOException
    {
        try
        {
            startThreads();
            PseudoTcpState state = pseudoTcp.getState();
            if (state == PseudoTcpState.TCP_CLOSED)
            {
                throw new IOException("Socket closed");
            }
            if (pseudoTcp.getState() != PseudoTcpState.TCP_ESTABLISHED)
            {
                synchronized (state_notify)
                {
                    state_notify.wait(timeout);
                }
            }
            if (pseudoTcp.getState() != PseudoTcpState.TCP_ESTABLISHED)
            {
                throw new IOException("Accept timeout");
            }
        }
        catch (InterruptedException ex)
        {
            IOException e = new IOException("Accept aborted");
            pseudoTcp.closedown(e);
            throw e;
        }
    }
    
    /**
     * Accepts a connection.
     * @param s the accepted connection.
     * @throws IOException if an I/O error occurs when accepting the connection.
     */
    protected void accept(SocketImpl s)
                        throws IOException
    {
        //TODO: not sure how this should work
        int timeout = 5000;
        accept(timeout);
    }
    
    /**
     *
     * @return current TCP state
     */
    public PseudoTcpState getState()
    {
        return pseudoTcp.getState();
    }

    /**
     * Interrupts clock thread's wait method to force time update
     */
    private void updateClock()
    {
		scheduleClockTask(0);
    }

    /**
     * Starts all threads required by the socket
     */
    private void startThreads()
    {
        pseudoTcp.notifyClock(PseudoTCPBase.now());
        receiveThread = new Thread(new Runnable()
        {
            public void run()
            {
                receivePackets();
            }
        }, "PseudoTcpReceiveThread");

        runReceive = true;
        runClock = true;
        receiveThread.start();
        scheduleClockTask(0);
    }

    /**
     * Implements <tt>PseudoTcpNotify</tt>
     * Called when TCP enters connected state.
     *
     * @param tcp the {@link PseudoTCPBase} that caused an event
     * @see PseudoTcpNotify#onTcpOpen(PseudoTCPBase)
     */
    public void onTcpOpen(PseudoTCPBase tcp)
    {
        logger.log(Level.FINE, "tcp opened");
        //Release threads blocked at state_notify monitor object.
        synchronized (state_notify)
        {
            state_notify.notifyAll();
        }
        //TCP is considered writeable at this point
        onTcpWriteable(tcp);
    }

    /**
     * Implements <tt>PseudoTcpNotify</tt>
     *
     * @param tcp the {@link PseudoTCPBase} that caused an event
     * @see PseudoTcpNotify#onTcpReadable(PseudoTCPBase)
     */
    public void onTcpReadable(PseudoTCPBase tcp)
    {
        if(logger.isLoggable(Level.FINER))
        {
            logger.log(
                Level.FINER,
                "TCP READABLE data available for reading: "+tcp.getAvailable());
        }
        //release all thread blocked at read_notify monitor
        synchronized (read_notify)
        {
            read_notify.notifyAll();
        }
    }

    /**
     * Implements <tt>PseudoTcpNotify</tt>
     *
     * @param tcp the {@link PseudoTCPBase} that caused an event
     * @see PseudoTcpNotify#onTcpWriteable(PseudoTCPBase)
     */
    public void onTcpWriteable(PseudoTCPBase tcp)
    {

        logger.log(Level.FINER, "stream writeable");
        //release all threads blocked at write monitor
        synchronized (write_notify)
        {
            write_notify.notifyAll();
        }
        //writeSemaphore.release(1);        
        logger.log(Level.FINER, "write notified - now !");

    }

    /**
     * Implements <tt>PseudoTcpNotify</tt>
     *
     * @param tcp the {@link PseudoTCPBase} that caused an event
     * @param e the <tt>Exception</tt> which is the reason for closing socket,
     *  or <tt>null</tt> if there wasn't any
     * 
     * @see PseudoTcpNotify#onTcpClosed(PseudoTCPBase, IOException)
     */
    public void onTcpClosed(PseudoTCPBase tcp, IOException e)
    {
        if (e != null)
        {
            //e.printStackTrace();
            logger.log(Level.SEVERE, "PseudoTcp closed: " + e);
        }
        else
        {
            logger.log(Level.FINE, "PseudoTcp closed");
        }
        runReceive = false;
        runClock = false;
        this.exception = e;
        releaseAllLocks();
		cancelClockTask(true);

    }

    /**
     * Releases all monitor objects so that the threads will check their "run
     * flags"
     */
    private void releaseAllLocks()
    {
        synchronized (read_notify)
        {
            read_notify.notifyAll();
        }
        synchronized (write_notify)
        {
            write_notify.notifyAll();
        }
        synchronized (state_notify)
        {
            state_notify.notifyAll();
        }
        //this interrupt won't work for DatagramSocket read packet operation
        //receiveThread.interrupt();
    }

    /**
     * Joins all running threads
     *
     * @throws InterruptedException
     */
    private void joinAllThreads() throws InterruptedException
    {
        receiveThread.join();
    }

    /**
     * Implements <tt>PseudoTcpNotify</tt>
     *
     * @param tcp the {@link PseudoTCPBase} that caused an event
     * @param buffer the buffer containing packet data
     * @param len packet data length in bytes
     * @return operation result
     * 
     * @see PseudoTcpNotify#tcpWritePacket(PseudoTCPBase, byte[], int)
     */
    public WriteResult tcpWritePacket(PseudoTCPBase tcp, byte[] buffer, int len)
    {
        if (logger.isLoggable(Level.FINEST))
        {
            logger.log(Level.FINEST,
                       "write packet to network length " + len
                            + " address " + remoteAddr);
        }
        try
        {
            //TODO: in case the packet is too long it should return WR_TOO_LARGE
            DatagramPacket packet = new DatagramPacket(buffer, len, remoteAddr);
            socket.send(packet);
            return WriteResult.WR_SUCCESS;
        }
        catch (IOException ex)
        {
            logger.log(Level.SEVERE, "TcpWritePacket exception: " + ex);
            return WriteResult.WR_FAIL;
        }

    }
    /**
     * Flag which enables packets receive thread
     */
    private boolean runReceive = false;
    /**
     * Thread receiving packets from the network
     */
    private Thread receiveThread;

    /**
     * Receives packets from the network and passes them to TCP logic class
     */
    private void receivePackets()
    {
        byte[] buffer = new byte[DATAGRAM_RCV_BUFFER_SIZE];
        DatagramPacket packet = new DatagramPacket(buffer,
                                                   DATAGRAM_RCV_BUFFER_SIZE);
        while (runReceive)
        {
            try
            {
                socket.receive(packet);
                //Here is the binding point for remote socket if wasn't
                //specified earlier
                if (remoteAddr == null)
                {
                    remoteAddr = packet.getSocketAddress();
                    logger.log(Level.WARNING,
                               "Remote addr not set previously, setting to "
                                       + remoteAddr);
                }
                else
                {
                    if (!packet.getSocketAddress().equals(remoteAddr))
                    {
                        logger.log(Level.WARNING,
                                   "Ignoring packet from " + packet.getAddress()
                                    + ":" + packet.getPort()
                                    + " should be: " + remoteAddr);
                        continue;
                    }                    
                }
                synchronized (pseudoTcp)
                {
                    pseudoTcp.notifyPacket(buffer, packet.getLength());
                    //we need to update the clock after new packet is receivied
                    updateClock();
                }
            }
            catch (IOException ex)
            {
                //this exception occurs even when the socket 
                //is closed with the close operation, so we check
                //here if this exception is important
                if (runReceive)
                {
                    logger.log(Level.SEVERE,
                              "ReceivePackets exception: " + ex);
                    pseudoTcp.closedown(ex);
                }
                break;
            }
        }
    }
    /**
     * The run flag for clock thread
     */
    private boolean runClock = false;

    // FIXME: consider larger thread pool and/or making it configurable
    private final static ScheduledThreadPoolExecutor clockExecutor
        = new ScheduledThreadPoolExecutor(1);

    private volatile ScheduledFuture<?> currentlyScheduledClockTask = null;

    /**
     * Method runs cyclic notification about time progress for TCP logic class
     * It runs in a separate thread
     */
    private void runClock()
    {
		if(!runClock)
		{
			return;
		}
        long sleep;

		synchronized (pseudoTcp)
		{
			pseudoTcp.notifyClock(PseudoTCPBase.now());
			sleep = pseudoTcp.getNextClock(PseudoTCPBase.now());
		}

		//there might be negative interval even if there's no error
		if (sleep == -1)
		{
			releaseAllLocks();
			if (exception != null)
			{
				logger.log(Level.SEVERE,
						   "STATE: " + pseudoTcp.getState()
					       + " ERROR: " + exception.getMessage());
			}
		}
		else
		{
			//logger.log(Level.FINEST, "Clock sleep for " + sleep);
			scheduleClockTask(sleep);
		}
    }

	private Runnable clockTaskRunner = new Runnable()
	{
		@Override
		public void run() {
			runClock();
		}
	};

	private void scheduleClockTask(long sleep)
	{
		synchronized (clockTaskRunner)
		{
			// Cancel any existing tasks, to make sure we don't run duplicates.
			cancelClockTask(false);
			if(runClock)
			{
				currentlyScheduledClockTask
                    = clockExecutor.schedule(
                            clockTaskRunner, sleep, TimeUnit.MILLISECONDS);
			}
		}
	}

	private void cancelClockTask(boolean interruptIfRunning)
	{
		// Copy the reference, in case it changes.
		ScheduledFuture<?> taskToCancel = this.currentlyScheduledClockTask;
		if(taskToCancel != null)
		{
			taskToCancel.cancel(interruptIfRunning);
		}
	}

	/**
     * Returns an output stream for this socket.
     * @return an output stream for writing to this socket.
     * @throws IOException if an I/O error occurs when creating the output stream.
     */
    public OutputStream getOutputStream()
        throws IOException
    {
        if (outputstream == null)
        {
            outputstream = new PseudoTcpOutputStream();
        }
        return outputstream;
    }
    

    /**
     * Returns an input stream for this socket.
     * @return a stream for reading from this socket.
     * @throws IOException 
     */
    public InputStream getInputStream()
        throws IOException
    {
        if (inputStream == null)
        {
            inputStream = new PseudoTcpInputStream();
        }
        return inputStream;
    }

    /**
     * Returns the number of bytes that can be read from this socket without blocking.
     * @return the number of bytes that can be read from this socket without blocking.
     * @throws IOException if an I/O error occurs when determining the number of bytes available.
     */
    protected int available()
        throws IOException
    {
        return getInputStream().available();
    }
    
    /**
     * Closes this socket.
     * @throws IOException 
     */
    public void close()
        throws IOException
    {
        try
        {
            pseudoTcp.close(true);
            //System.out.println("ON CLOSE: in flight "+pseudoTcp.GetBytesInFlight());
            //System.out.println("ON CLOSE: buff not sent "+pseudoTcp.GetBytesBufferedNotSent());
            onTcpClosed(pseudoTcp, null);
            socket.close();
            joinAllThreads();
            //UpdateClock();
            //TODO: closing procedure
            //Here the thread should be blocked until TCP
            //reaches CLOSED state, but there's no closing procedure
            /*
             * synchronized(state_notify){ while(pseudoTcp.getState() !=
             * PseudoTcpState.TCP_CLOSED){ try { state_notify.wait(); } catch
             * (InterruptedException ex) { throw new IOException("Close
             * connection aborted"); } } }
             */
        }
        catch (InterruptedException ex)
        {
            throw new IOException("Closing socket interrupted", ex);
        }
    }
    
    /**
     * Send one byte of urgent data on the socket. The byte to be sent is the low eight bits of the parameter
     * @param data The byte of data to send
     * @throws IOException if there is an error sending the data.
     */
    protected void sendUrgentData(int data)
        throws IOException
    {
        throw new RuntimeException("Sending urgent data is not supported");
    }

    
    
    /**
     * This class implements <tt>java.io.InputStream</tt>
     */
    class PseudoTcpInputStream extends InputStream
    {
        public PseudoTcpInputStream()
        {
        }

        @Override
        public boolean markSupported()
        {
            return false;
        }

        /**
         * There's no end of stream detection at the moment. Method blocks until
         * it returns any data or an exception is thrown
         *
         * @return read byte count
         * @throws IOException in case of en error
         */
        @Override
        public int read() throws IOException
        {
            byte[] buff = new byte[1];
            int readCount = read(buff, 0, 1);
            return readCount == 1 ? (buff[0] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] bytes) throws IOException
        {
            return read(bytes, 0, bytes.length);
        }

        /**
         * This method blocks until any data is available
         *
         * @param buffer destination buffer
         * @param offset destination buffer's offset
         * @param length maximum count of bytes that can be read
         * @return byte count actually read
         * @throws IOException in case of error or if timeout occurs
         */
        @Override
        public int read(byte[] buffer, int offset, int length)
            throws IOException
        {
            long start = System.currentTimeMillis();
            int read;
            while (true)
            {
                logger.log(Level.FINER, "Read Recv");
                try
                {
                    read = pseudoTcp.recv(buffer, offset, length);
                    if (logger.isLoggable(Level.FINER))
                    {
                        logger.log(Level.FINER,
                                   "Read Recv read count: " + read);
                    }
                    if (read > 0)
                    {
                        return read;
                    }
                    logger.log(Level.FINER, "Read wait for data available");
                    if(readTimeout > 0)
                    {
                        //Check for timeout
                        long elapsed = System.currentTimeMillis() - start;
                        long left = readTimeout - elapsed;
                        if(left <= 0)
                        {
                            IOException exc =
                                new IOException("Read operation timeout");
                            pseudoTcp.closedown(exc);
                            throw exc;
                        }
                        synchronized (read_notify)
                        {
                            if(pseudoTcp.getAvailable() == 0)
                            {
                                read_notify.wait(left);
                            }
                        }
                    }
                    else
                    {
                        synchronized (read_notify)
                        {
                            if(pseudoTcp.getAvailable() == 0)
                            {
                                read_notify.wait();
                            }
                        }
                    }
                    if (logger.isLoggable(Level.FINER))
                    {
                        logger.log(
                            Level.FINER,
                            "Read notified: " + pseudoTcp.getAvailable());
                    }
                    if (exception != null)
                    {
                        throw exception;
                    }
                }
                catch (InterruptedException ex)
                {
                    if (exception != null)
                    {
                        throw new IOException("Read aborted", exception);
                    }
                    else
                    {
                        throw new IOException("Read aborted");
                    }
                }
            }
        }

        @Override
        public int available() throws IOException
        {
            return pseudoTcp.getAvailable();
        }

        @Override
        public void close() throws IOException
        {
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public long skip(long n) throws IOException
        {
            return super.skip(n);
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void mark(int readlimit)
        {
            throw new UnsupportedOperationException("mark");
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public synchronized void reset() throws IOException
        {
            throw new UnsupportedOperationException("reset");
        }
    }

    /**
     * Implements <tt>java.io.OutputStream</tt>
     */
    class PseudoTcpOutputStream extends OutputStream
    {
        @Override
        public void write(int b) throws IOException
        {
            byte[] bytes = new byte[1];
            bytes[0] = (byte) b;
            write(bytes);
        }

        @Override
        public void write(byte[] bytes) throws IOException
        {
            write(bytes, 0, bytes.length);
        }

        /**
         * This method blocks until all data has been written.
         *
         * @param buffer source buffer
         * @param offset source buffer's offset
         * @param length byte count to be written
         * @throws IOException in case of error or if timeout occurs 
         * 
         */
        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException
        {
            int toSend = length;
            int sent;
            long start = System.currentTimeMillis();
            while (toSend > 0)
            {
                synchronized (pseudoTcp)
                {
                    sent = pseudoTcp.send(buffer, offset + length - toSend, toSend);
                }
                if (sent > 0)
                {
                    toSend -= sent;
                }
                else
                {
                    try
                    {
                        logger.log(Level.FINER, "Write wait for notify");                        
                        synchronized (write_notify)
                        {
                            if(writeTimeout > 0)
                            {
                                long elapsed = System.currentTimeMillis() - start;
                                long left = writeTimeout - elapsed;
                                if(left <= 0)
                                {
                                    IOException exc = 
                                        new IOException("Write operation timeout");
                                    pseudoTcp.closedown(exc);
                                    throw exc;
                                }
                                write_notify.wait(left);
                            }
                            else
                            {
                                write_notify.wait();
                            }
                        }
                        logger.log(Level.FINER,
                                   "Write notified, available: "
                            + pseudoTcp.getAvailableSendBuffer());
                        if (exception != null)
                        {
                            throw exception;
                        }
                    }
                    catch (InterruptedException ex)
                    {
                        if (exception != null)
                        {
                            throw new IOException("Write aborted", exception);
                        }
                        else
                        {
                            throw new IOException("Write aborted", ex);
                        }
                    }
                }
            }
        }

        /**
         * This method block until all buffered data has been written
         *
         * @throws IOException in case of error or if timeout occurs
         */
        @Override
        public synchronized void flush() throws IOException
        {
            logger.log(Level.FINE, "Flushing...");
            long start = System.currentTimeMillis();
            final Object ackNotify = pseudoTcp.getAckNotify();
            synchronized (ackNotify)
            {
                while (pseudoTcp.getBytesBufferedNotSent() > 0)
                {   
                    try
                    {
                        if(writeTimeout > 0)
                        {
                            //Check write timeout
                            long elapsed = System.currentTimeMillis() - start;
                            long left = writeTimeout - elapsed;                            
                            if(left <= 0)
                            {
                                IOException e = 
                                    new IOException("Flush operation timeout"); 
                                pseudoTcp.closedown(e);
                                throw e;
                            }
                            ackNotify.wait(left);
                        }
                        else
                        {
                            ackNotify.wait();
                        }
                    }
                    catch (InterruptedException ex)
                    {
                        throw new IOException("Flush stream interrupted", ex);
                    }                    
                }
            }
            logger.log(Level.FINE, "Flushing completed");
        }

        @Override
        public void close() throws IOException
        {
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected FileDescriptor getFileDescriptor()
    {
        return fd;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void shutdownInput()
            throws IOException
    {
        throw new IOException("Method not implemented!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void shutdownOutput()
            throws IOException
    {
        throw new IOException("Method not implemented!");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected InetAddress getInetAddress()
    {
        return ((InetSocketAddress) remoteAddr).getAddress();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getPort()
    {
        return ((InetSocketAddress) remoteAddr).getPort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected boolean supportsUrgentData()
    {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected int getLocalPort()
    {
        return socket.getLocalPort();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void setPerformancePreferences(int connectionTime,
                                             int latency,
                                             int bandwidth)
    {
        throw new UnsupportedOperationException("setPerformancePreferences");
    }
}
