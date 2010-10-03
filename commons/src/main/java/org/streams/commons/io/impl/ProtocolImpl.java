package org.streams.commons.io.impl;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.streams.commons.compression.CompressionPool;
import org.streams.commons.compression.CompressionPoolFactory;
import org.streams.commons.io.Header;
import org.streams.commons.io.Protocol;
import org.streams.commons.util.CompressionCodecLoader;

/**
 * Implements the writing and reading of the start of a send stream.
 * 
 */
public class ProtocolImpl implements Protocol {

	// private static final Logger LOG = Logger.getLogger(ProtocolImpl.class);

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private ConcurrentMap<String, CompressionCodec> codecMap = new ConcurrentHashMap<String, CompressionCodec>();

	private CompressionPoolFactory compressionPoolFactory;

	/**
	 * Time that this class will wait for a compression resource to become available. Default 10000L
	 */
	private long waitForCompressionResource = 10000L;

	public ProtocolImpl(CompressionPoolFactory compressionPoolFactory) {
		super();
		this.compressionPoolFactory = compressionPoolFactory;
	}

	/**
	 * Reads the header part of a InputStream
	 * 
	 * @param conf
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	public Header read(Configuration conf, DataInputStream inputStream)
			throws IOException {
		Header header = null;

		try {
			int codecNameLen = inputStream.readInt();

			byte[] codecBytes = new byte[codecNameLen];
			int bytesRead = inputStream.read(codecBytes, 0, codecNameLen);

			if (bytesRead != codecNameLen) {
				throw new RuntimeException(
						"The codecLength in the stream is not equal to the Stream Length");
			}

			String codecName = new String(codecBytes, 0, bytesRead);

			// we don't synchronise here because we do not care if the codec is
			// created more than once.
			CompressionCodec codec = codecMap.putIfAbsent(codecName, CompressionCodecLoader.loadCodec(conf, codecName));

			if (codec == null) {
				codec = codecMap.get(codecName);
			}

			// read header length
			final int headerLen = inputStream.readInt();
			final byte[] headerBytes = new byte[headerLen];
			final int ioBytesRead = inputStream.read(headerBytes, 0, headerLen);

			if (ioBytesRead != headerLen) {
				throw new RuntimeException(
						"The bytes available in the input stream does not match the header length integer passed in the stream ("
								+ headerLen + ")");
			}

			ByteArrayInputStream byteInput = new ByteArrayInputStream(
					headerBytes);

			CompressionPool pool = compressionPoolFactory.get(codec);
			CompressionInputStream compressionInput = pool.create(byteInput,
					waitForCompressionResource, TimeUnit.MILLISECONDS);

			if (compressionInput == null) {
				throw new IOException("No decompression resource available for "
						+ codec);
			}

			Reader reader = new InputStreamReader(compressionInput);
			try {

				// The jackson Object mapper does not read the stream completely
				// thus causing OutOfMemory DirectMemory errors in the
				// Decompressor.
				// read whole stream here and pass as String to the jackson
				// object mapper.
				StringBuilder buff = new StringBuilder(headerBytes.length);
				char chars[] = new char[256];
				int len = 0;

				while ((len = reader.read(chars)) > 0) {
					buff.append(chars, 0, len);
				}

				header = objectMapper.readValue(buff.toString(), Header.class);

			} finally {
				pool.closeAndRelease(compressionInput);
				IOUtils.closeQuietly(byteInput);
				IOUtils.closeQuietly(reader);
			}

		} catch (Throwable t) {
			IOException ioExcp = new IOException(t.toString(), t);
			ioExcp.setStackTrace(t.getStackTrace());
			throw ioExcp;
		}
		return header;
	}

	/**
	 * Write the protocol header and start bytes.<br/>
	 * 4 bytes length of header codec class name.<br/>
	 * string which is header codec class name.<br/>
	 * 4 bytes length of header.<br/>
	 * compressed json object representing the header.<br/>
	 */
	public void send(Header header, CompressionCodec codec, DataOutput dataOut)
			throws IOException {

		ChannelBuffer headerBuffer = writeCompressedHeaderBytes(header, codec);

		byte[] headerBytes = headerBuffer.toByteBuffer().array();

		byte[] compressCodecNameBytes = codec.getClass().getName().getBytes();

		dataOut.writeInt(compressCodecNameBytes.length);
		dataOut.write(compressCodecNameBytes);

		dataOut.writeInt(headerBytes.length);
		dataOut.write(headerBytes);

	}

	/**
	 * Returns a ChannelBuffer with the compressed Header data
	 * 
	 * @param header
	 * @param codec
	 * @return
	 * @throws IOException
	 */
	private ChannelBuffer writeCompressedHeaderBytes(Header header,
			CompressionCodec codec) throws IOException {

		// GET RAW HEADER BYTES
		byte[] headerBytes = header.toJsonString().getBytes();

		// COMPRESS HEADER DATA
		ChannelBuffer channelBuffer = ChannelBuffers.dynamicBuffer();
		ChannelBufferOutputStream channelBufferOut = new ChannelBufferOutputStream(
				channelBuffer);

		CompressionOutputStream compressionOut = codec
				.createOutputStream(channelBufferOut);
		compressionOut.write(headerBytes);
		compressionOut.finish();
		compressionOut.close();

		return channelBuffer;
	}

	public long getWaitForCompressionResource() {
		return waitForCompressionResource;
	}

	public void setWaitForCompressionResource(long waitForCompressionResource) {
		this.waitForCompressionResource = waitForCompressionResource;
	}

}
