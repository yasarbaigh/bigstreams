package org.streams.agent.send.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Exchanger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.log4j.Logger;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferInputStream;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.WriteCompletionEvent;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.timeout.ReadTimeoutHandler;
import org.jboss.netty.handler.timeout.WriteTimeoutHandler;
import org.jboss.netty.util.Timer;
import org.streams.agent.file.FileLinePointer;
import org.streams.agent.send.ClientConnection;
import org.streams.agent.send.ClientException;
import org.streams.agent.send.FileStreamer;
import org.streams.agent.send.ServerException;
import org.streams.commons.io.Header;
import org.streams.commons.io.NetworkCodes;
import org.streams.commons.io.Protocol;

/**
 * 
 * This client is meant to be a once of only use.<br/>
 * That is open, send and close should be done in that order. with only one send
 * done ever.<br/>
 * This class is not Thread safe so objects calling it should use instance
 * methods.<br/>
 * <p/>
 * Communication on the sendLines method:<br/>
 * <ul>
 * <li>ClientBoostrap (NETTY) is used to open a TCP connection to the server.</li>
 * <li>The connection is assigned 2 cached thread pools.</li>
 * <li>A ChannelPipeline is created with the wrapper ClientChannelHandler.</li>
 * <li>The ClientChannelHandler wrapper will:
 * <ul>
 * <li>set the event values on the ClientHandlerContext instance created.</li>
 * <li>Send the data to the server when the channelConnected event is called.</li>
 * <li>Set a client error on the ClientHandlerContext if the exceptionCaught
 * event is called.</li>
 * <li>Set a server error on the ClientHandlerContext if the data sent by the
 * server is not a 200 integer.</li>
 * <li>Set the data written flag on the ClientHandlerContext if the
 * writeComplete event is called.</li>
 * </ul>
 * </li>
 * 
 * </ul>
 */
public class ClientConnectionImpl implements ClientConnection {

	private static final Logger LOG = Logger
			.getLogger(ClientConnectionImpl.class);

	long connectEstablishTimeout = 10000L;

	long sendTimeOut = 20000L;

	InetSocketAddress inetAddress;

	Protocol protocol = null;

	Timer timeoutTimer;

	volatile ClientSocketChannelFactory socketChannelFactory = null;

	ExecutorService connectService;

	public ClientConnectionImpl(ExecutorService connectService,
			ClientSocketChannelFactory socketChannelFactory, Timer timeoutTimer) {
		super();
		this.connectService = connectService;
		this.socketChannelFactory = socketChannelFactory;
		this.timeoutTimer = timeoutTimer;
	}

	/**
	 * Will not open a connection to the server by only save the address.
	 */
	public void connect(InetSocketAddress inetAddress) throws IOException {
		this.inetAddress = inetAddress;
	}

	/**
	 * Tries to connect using an async method call.<br/>
	 * Any response is sent via the exchanger
	 * 
	 * @param header
	 * @param fileLineStreamer
	 * @param input
	 * @return Exchanger
	 */
	private Exchanger<ClientHandlerContext> asyncConnect(final Header header,
			final FileStreamer fileLineStreamer, final BufferedReader input) {

		final Exchanger<ClientHandlerContext> exchanger = new Exchanger<ClientHandlerContext>();

		connectService.submit(new Runnable() {
			public void run() {

				ChannelFuture channelFuture = null;

				try {

					ClientBootstrap bootstrap = new ClientBootstrap(
							socketChannelFactory);

					bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
						@Override
						public ChannelPipeline getPipeline() throws Exception {
							return Channels.pipeline(
									new ClientMessageFrameDecoder(),
									// timeouts added in as a safety measure
									// this ensures that channels are not kept
									// open indefinitely due to any
									// communication mismatch
									// between the server and the local client
									new ReadTimeoutHandler(timeoutTimer, 1,
											TimeUnit.MINUTES),
									new WriteTimeoutHandler(timeoutTimer, 1,
											TimeUnit.MINUTES),
									new ClientChannelHandler(exchanger,
											new ClientHandlerContext(header,
													input, fileLineStreamer),
											protocol.clone(), sendTimeOut));
						}
					});

					bootstrap.setOption("connectTimeoutMillis",
							connectEstablishTimeout);
					bootstrap.setOption("tcpNoDelay", "true");
					// 30 seconds linger timeout.
					bootstrap.setOption("soLinger", String.valueOf(30000));

					// Start the connection attempt.
					channelFuture = bootstrap.connect(inetAddress);
					
				} catch (Throwable t) {

					if (channelFuture != null) {
						
						//if any connection was made cancel to intent
						try {
							channelFuture.cancel();
						} catch (Throwable t1) {
							t1.printStackTrace();
						}
					}

					LOG.error(t.toString(), t);

				}
			}
		});

		return exchanger;
	}

	/**
	 * Calling this method will open and close a connection to the server.
	 */
	public boolean sendLines(final FileLinePointer fileLinePointer,
			final Header header, final FileStreamer fileLineStreamer,
			final BufferedReader input) throws IOException {

		// first check that we have a protocol
		if (protocol == null) {
			throw new ClientException("Please set a protocol", -1);
		}

		Exchanger<ClientHandlerContext> exchanger = asyncConnect(header,
				fileLineStreamer, input);

		ClientHandlerContext clientHandlerContext = null;

		try {
			clientHandlerContext = exchanger.exchange(null, sendTimeOut * 2,
					TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			// this is called if the thread is to be closed by some shutdown
			// process.
			Thread.currentThread().interrupt();

		} catch (TimeoutException e) {
			throw new ServerException(
					"The server did not respond within the timeout "
							+ (sendTimeOut * 2), null,
					ClientHandlerContext.NO_SERVER_RESPONSE);
		}

		// complete io operations
		// check error codes

		if (clientHandlerContext == null) {
			throw new ClientException("No response from server", null,
					ClientHandlerContext.NO_SERVER_RESPONSE);
		} else if (clientHandlerContext.getClientStatusCode() != ClientHandlerContext.STATUS_OK) {
			// client error

			// parse code:
			NetworkCodes.CODE statusCode = NetworkCodes
					.findCode(clientHandlerContext.getClientStatusCode());

			Throwable t = clientHandlerContext.getErrorCause();
			throw new ClientException("Client Error: " + statusCode.msg(), t,
					statusCode.num());
		}

		// if all when well and data was written copy the file pointer
		if (clientHandlerContext.getLogDataSent()) {
			// only check for server error if data was sent
			if (clientHandlerContext.getServerStatusCode() == ClientHandlerContext.STATUS_CONFLICT) {
				// do nothing here
				LOG.warn("Conflict detected by collector");

			} else if (clientHandlerContext.getServerStatusCode() != ClientHandlerContext.STATUS_OK) {

				NetworkCodes.CODE statusCode = NetworkCodes
						.findCode(clientHandlerContext.getServerStatusCode());

				if (clientHandlerContext.getServerStatusCode() == ClientHandlerContext.NO_SERVER_RESPONSE) {

					throw new ServerException(
							"The server did not respond within the timeout "
									+ (sendTimeOut * 2), null,
							clientHandlerContext.getServerStatusCode());
				} else {
					throw new ServerException(
							"Error with server communication : "
									+ statusCode.msg(), null, statusCode.num());
				}

			}

			fileLinePointer.copyIncrement(clientHandlerContext
					.getIntermediatePointer());
		}

		return clientHandlerContext.getLogDataSent();
	}

	public void close() {

	}

	public long getConnectEstablishTimeout() {
		return connectEstablishTimeout;
	}

	public void setConnectEstablishTimeout(long connectEstablishTimeout) {
		this.connectEstablishTimeout = connectEstablishTimeout;
	}

	public long getSendTimeOut() {
		return sendTimeOut;
	}

	public void setSendTimeOut(long sendTimeOut) {
		this.sendTimeOut = sendTimeOut;
	}

	public Protocol getProtocol() {
		return protocol;
	}

	public void setProtocol(Protocol protocol) {
		this.protocol = protocol;
	}

	/**
	 * 
	 * Handlers for sending receiving and notifying any communication errors.
	 * 
	 */
	static class ClientChannelHandler extends SimpleChannelHandler {

		private final ClientHandlerContext clientHandlerContext;
		Exchanger<ClientHandlerContext> exchanger;

		AtomicBoolean exhanged = new AtomicBoolean(false);
		Protocol protocol;
		long sendTimeOut;

		public ClientChannelHandler(Exchanger<ClientHandlerContext> exchanger,
				ClientHandlerContext clientHandlerContext, Protocol protocol,
				long sendTimeout) {
			this.clientHandlerContext = clientHandlerContext;
			this.exchanger = exchanger;
			this.protocol = protocol;
			this.sendTimeOut = sendTimeout;
		}

		/**
		 * This method will be called as soon as the client is connected and
		 * will start sending data.
		 */
		@Override
		public void channelConnected(ChannelHandlerContext ctx,
				ChannelStateEvent e) throws Exception {
			// super.channelConnected(ctx, e);
			// as soon as connected send data

			Channel channel = e.getChannel();
			boolean sentLines = false;

			// --- Create ChannelBuffer for output chunk
			ChannelBuffer channelBuffer = ChannelBuffers.dynamicBuffer(1024);
			ChannelBufferOutputStream output = new ChannelBufferOutputStream(
					channelBuffer);

			protocol.send(clientHandlerContext.getHeader(),
					clientHandlerContext.getFileLineStreamer().getCodec(),
					output);

			// write the fileInput to the channel buffer outBuffer
			// we are reading several lines of data as configured in the
			// FileLineStreamer
			// and compressing the output to a channel buffer.
			// this allows more file lines to be read as the in-memory storage
			// is a
			// compressed.

			sentLines = clientHandlerContext.getFileLineStreamer()
					.streamContent(
							clientHandlerContext.getIntermediatePointer(),
							clientHandlerContext.getReader(), output);

			if (sentLines) {
				// get the total message length, and write integer as first
				// bytes of message
				// this is needed to that the server can know how much bytes to
				// expect in a message
				int messageLen = channelBuffer.readableBytes();
				ChannelBuffer messageLenBuffer = ChannelBuffers.buffer(4);
				messageLenBuffer.writeInt(messageLen);

				// create a wrapped composite buffer
				ChannelBuffer messageBuffer = ChannelBuffers.wrappedBuffer(
						messageLenBuffer, channelBuffer);

				// write the composite buffer containing the message length and
				// compressed data to the channel
				channel.write(messageBuffer);

			} else {
				// close channel if no write
				channel.close();
				if (!exhanged.getAndSet(true)) {
					exchanger.exchange(clientHandlerContext, sendTimeOut,
							TimeUnit.MILLISECONDS);
				}
			}

			super.channelConnected(ctx, e);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e)
				throws Exception {
			LOG.warn("Client Exception Caught");

			// any client side exception in the IO pipeline is captured here.
			// if this happens close the connection
			try {
				clientHandlerContext
						.setClientStatusCode(ClientHandlerContext.STATUS_ERROR);
				Throwable t = e.getCause();

				clientHandlerContext.setErrorCause(t);

				String msg = "Client Error: " + t.toString();
				LOG.error(msg, t);

				ctx.getChannel().close();
			} catch (Throwable t) {
				LOG.error(t.toString(), t);
			}

			// only wait if the channel has been connected
			if (!exhanged.getAndSet(true)) {
				try {
					exchanger.exchange(clientHandlerContext, sendTimeOut,
							TimeUnit.MILLISECONDS);
				} catch (TimeoutException te) {
					LOG.error("The calling object did not respond");
				}
			}

		}

		@Override
		public void messageReceived(ChannelHandlerContext ctx, MessageEvent e)
				throws Exception {
			// wait for and interpret the response message from the
			// server
			// only the HTTP OK response is accepted i.e. 200.
			// if (LOG.isDebugEnabled()) {
			// LOG.debug("Server response received");
			// }

			// LOG.info("----------------- Server response received");

			ChannelBuffer buff = (ChannelBuffer) e.getMessage();

			ChannelBufferInputStream in = new ChannelBufferInputStream(buff);
			try {
				int status = in.readInt();

				if (status == ClientHandlerContext.STATUS_OK) {
					clientHandlerContext
							.setServerStatusCode(ClientHandlerContext.STATUS_OK);
					// only if ok copy the data over.
				} else if (status == ClientHandlerContext.STATUS_CONFLICT) {
					// when a conflict message is sent it means that the file
					// line pointer
					// is not the same as that held by the collectors
					// here we set the status to conflict and set the file line
					// pointer on the intermediate pointer to
					// that indicated by the server
					long fileLinePointer = in.readLong();
					if (fileLinePointer < 0) {
						throw new ServerException(
								"Server send 409 by fileLinePointer is not valid. Collector send pointer: "
										+ fileLinePointer
										+ ". Please check the coordination service",
								ClientHandlerContext.STATUS_CONFLICT);
					}
					clientHandlerContext
							.setServerStatusCode(ClientHandlerContext.STATUS_CONFLICT);

					clientHandlerContext.getIntermediatePointer()
							.setConflictFilePointer(fileLinePointer);
				} else {
					clientHandlerContext.setServerStatusCode(status);
				}
			} finally {
				in.close();
			}

			if (!exhanged.getAndSet(true)) {
				exchanger.exchange(clientHandlerContext, sendTimeOut,
						TimeUnit.MILLISECONDS);
			}

			super.messageReceived(ctx, e);
		}

		@Override
		public void writeComplete(ChannelHandlerContext ctx,
				WriteCompletionEvent e) throws Exception {
			// This method is called when the client has finished sending data
			// data is only ever sent if file lines were read and no
			// communication errors appeared.
			// setting the logDataSent flag will inform calling classes that
			// this instance actually did sent data.
			if (e.getWrittenAmount() > 0)
				clientHandlerContext.setLogDataSent(true);

			if (LOG.isDebugEnabled()) {
				LOG.info("waiting for server response: ("
						+ e.getWrittenAmount() + ") bytes written");
			}

			super.writeComplete(ctx, e);
		}

	}

}
