package org.streams.commons.io.impl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.configuration.Configuration;
import org.apache.commons.io.IOUtils;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionInputStream;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.log4j.Logger;
import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBufferOutputStream;
import org.jboss.netty.buffer.ChannelBuffers;
import org.streams.commons.io.Header;
import org.streams.commons.io.Protocol;

/**
 * Implements the writing and reading of the start of a send stream.
 * 
 */
public class ProtocolImpl implements Protocol {

	private static final Logger LOG = Logger.getLogger(ProtocolImpl.class);
	
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private org.apache.hadoop.conf.Configuration hadoopConf = new org.apache.hadoop.conf.Configuration();

	private Map<String, CompressionCodec> codecMap = new ConcurrentHashMap<String, CompressionCodec>();
	
	/**
	 * Reads the header part of a InputStream
	 * 
	 * @param conf
	 * @param inputStream
	 * @return
	 * @throws IOException
	 */
	@SuppressWarnings("unchecked")
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
			CompressionCodec codec = codecMap.get(codecName);

			if (codec == null) {
				Class<? extends CompressionCodec> compressionCodecClass = (Class<? extends CompressionCodec>) Thread
						.currentThread().getContextClassLoader()
						.loadClass(codecName);

				codec = compressionCodecClass.newInstance();

				if (codec instanceof Configurable) {

					((Configurable) codec).setConf(hadoopConf);
				}

				codecMap.put(codecName, codec);

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
			
			final ByteArrayInputStream byteInput = new ByteArrayInputStream(headerBytes);
						
			final CompressionInputStream compressionInput = codec
					.createInputStream(byteInput);
			
			final Reader reader = new InputStreamReader(compressionInput);
			try {
				
				//The jackson Object mapper does not read the stream completely
				//thus causing OutOfMemory DirectMemory errors in the Decompressor.
				//read whole stream here and pass as String to the jackson object mapper.
				StringBuilder buff = new StringBuilder(headerBytes.length);
				char chars[] = new char[256];
				int len = 0;
				
				while( (len = reader.read(chars)) > 0 ){
					buff.append(chars, 0, len);
				}
				
				header = objectMapper.readValue(buff.toString(), Header.class);
				
			} finally {
				IOUtils.closeQuietly(compressionInput);
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

		byte[] headerBytes = headerBuffer.array();

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

}
