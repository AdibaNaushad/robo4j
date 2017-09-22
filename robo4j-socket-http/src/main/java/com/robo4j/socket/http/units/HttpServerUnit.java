/*
 * Copyright (C) 2014, 2017. Miroslav Wengner, Marcus Hirt
 * This HttpDynamicUnit.java  is part of robo4j.
 * module: robo4j-core
 *
 * robo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * robo4j is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with robo4j .  If not, see <http://www.gnu.org/licenses/>.
 */

package com.robo4j.socket.http.units;

import com.robo4j.BlockingTrait;
import com.robo4j.ConfigurationException;
import com.robo4j.LifecycleState;
import com.robo4j.RoboContext;
import com.robo4j.RoboReference;
import com.robo4j.RoboUnit;
import com.robo4j.configuration.Configuration;
import com.robo4j.logging.SimpleLoggingUtil;
import com.robo4j.socket.http.enums.StatusCode;
import com.robo4j.socket.http.request.RoboRequestCallable;
import com.robo4j.socket.http.request.RoboRequestFactory;
import com.robo4j.socket.http.request.RoboResponseProcess;
import com.robo4j.socket.http.util.ByteBufferUtils;
import com.robo4j.socket.http.util.JsonUtil;
import com.robo4j.socket.http.util.RoboHttpUtils;
import com.robo4j.socket.http.util.RoboResponseHeader;
import com.robo4j.socket.http.util.SocketUtil;
import com.robo4j.util.StringConstants;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Http NIO unit allows to configure format of the requests currently is only
 * GET method available
 *
 * @author Marcus Hirt (@hirt)
 * @author Miro Wengner (@miragemiko)
 */
@BlockingTrait
public class HttpServerUnit extends RoboUnit<Object> {
    private static final String DELIMITER = ",";
    private static final int _DEFAULT_PORT = 8042;
    private static final int DEFAULT_BUFFER_CAPACITY = 700000;
    private static final Set<LifecycleState> activeStates = EnumSet.of(LifecycleState.STARTED, LifecycleState.STARTING);
    private static final HttpCodecRegistry CODEC_REGISTRY = new HttpCodecRegistry();
    public static final String PROPERTY_PORT = "port";
    public static final String PROPERTY_TARGET = "target";
    public static final String PROPERTY_STOPPER = "stopper";
    public static final String PROPERTY_BUFFER_CAPACITY = "bufferCapacity";
    private boolean available;
    private Integer port;
    private Integer bufferCapacity;
    //used for encoded messages
    private Integer stopper;
    private List<String> target;
    private ServerSocketChannel server;
    private final Map<SelectableChannel, SelectionKey> channelKeyMap = new HashMap<>();
    private final Map<SelectionKey, RoboResponseProcess> outBuffers = new HashMap<>();

    public HttpServerUnit(RoboContext context, String id) {
        super(Object.class, context, id);
    }

    @Override
    protected void onInitialization(Configuration configuration) throws ConfigurationException {
        setState(LifecycleState.UNINITIALIZED);
        /* target is always initiated as the list */
        target = Arrays.asList(configuration.getString(PROPERTY_TARGET, StringConstants.EMPTY).split(DELIMITER));
        port = configuration.getInteger(PROPERTY_PORT, _DEFAULT_PORT);
        bufferCapacity = configuration.getInteger(PROPERTY_BUFFER_CAPACITY, DEFAULT_BUFFER_CAPACITY);
        stopper = configuration.getInteger(PROPERTY_STOPPER, null);

        String packages = configuration.getString("packages", null);
        if (validatePackages(packages)) {
            CODEC_REGISTRY.scan(Thread.currentThread().getContextClassLoader(), packages.split(","));
        }

        //@formatter:off
		Map<String, Object> targetUnitsMap = JsonUtil.getMapNyJson(configuration.getString("targetUnits", null));

		if(targetUnitsMap.isEmpty()){
			SimpleLoggingUtil.error(getClass(), "no targetUnits");
		} else {
			targetUnitsMap.forEach((key, value) ->
				HttpUriRegister.getInstance().addUnitPathNode(key, value.toString()));
		}
        //@formatter:on

        setState(LifecycleState.INITIALIZED);
    }

    @Override
    public void start() {
        setState(LifecycleState.STARTING);
        final List<RoboReference<Object>> targetRefs = target.stream().map(e -> getContext().getReference(e))
                .filter(Objects::nonNull).collect(Collectors.toList());
        if (!available) {
            available = true;
            getContext().getScheduler().execute(() -> server(targetRefs));
        } else {
            SimpleLoggingUtil.error(getClass(), "HttpDynamicUnit start() -> error: " + targetRefs);
        }
        setState(LifecycleState.STARTED);
    }

    @Override
    public void stop() {
        setState(LifecycleState.STOPPING);
        stopServer("stop");
        setState(LifecycleState.STOPPED);
    }

    // Private Methods
    public void stopServer(String method) {
        try {
            if (server != null && server.isOpen()) {
                server.close();
            }
        } catch (IOException e) {
            SimpleLoggingUtil.error(getClass(), "method:" + method + ",server problem: ", e);
        }
    }

    /**
     * Start non-blocking socket server on http protocol
     *
     * @param targetRef - reference to the target queue
     */
    private void server(final List<RoboReference<Object>> targetRefs) {
        try {
            /* selector is multiplexor to SelectableChannel */
            // Selects a set of keys whose corresponding channels are ready for
            // I/O operations
            final Selector selector = Selector.open();
            server = ServerSocketChannel.open();
            server.configureBlocking(false);
            server.socket().bind(new InetSocketAddress(port));

            // channelKeyMap.put(server, listenKey);

            server.register(selector, SelectionKey.OP_ACCEPT);
            while (activeStates.contains(getState())) {
                int channelReady = selector.select();

                if (channelReady == 0) {
                    /*
					 * token representing the registration of a SelectableChannel with a Selector
					 */
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> selectedIterator = selectedKeys.iterator();

                    while (selectedIterator.hasNext()) {
                        final SelectionKey selectedKey = selectedIterator.next();

                        // preventing similar keys coming
                        selectedIterator.remove();

                        if (!selectedKey.isValid()) {
                            continue;
                        }

                        if (selectedKey.isAcceptable()) {
                            accept(selector, selectedKey);
                        } else if (selectedKey.isReadable()) {
                            read(selector, selectedKey);
                        } else if (selectedKey.isWritable()) {
                            write(targetRefs, selectedKey);
                        }
                    }
                }

            }
        } catch (IOException e) {
            SimpleLoggingUtil.error(getClass(), "SERVER CLOSED", e);
        }
        SimpleLoggingUtil.debug(getClass(), "stopped port: " + port);
        setState(LifecycleState.STOPPED);
    }

    private void accept(Selector selector, SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel channel = serverChannel.accept();
        channel.configureBlocking(false);
        channelKeyMap.put(channel, key);
        channel.register(selector, SelectionKey.OP_READ);

    }

    private void write(List<RoboReference<Object>> targetRefs, SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();

        RoboResponseProcess responseProcess = outBuffers.get(key);

        if (responseProcess.getMethod() != null) {
            switch (responseProcess.getMethod()) {
                case GET:
                    String getResponse;
                    if (responseProcess.getResult() != null) {
                        if (responseProcess.getResult() instanceof StatusCode) {
                            getResponse = RoboHttpUtils.createResponseByCode((StatusCode) responseProcess.getResult());
                        } else {
                            String getHeader = RoboResponseHeader.headerByCodeWithUid(StatusCode.OK, getContext().getId());
                            getResponse = RoboHttpUtils.createResponseWithHeaderAndMessage(getHeader, responseProcess.getResult().toString());
                        }
                    } else {
                        getResponse = RoboHttpUtils.createResponseByCode(StatusCode.NOT_IMPLEMENTED);
                    }
                    SocketUtil.writeBuffer(channel, ByteBuffer.wrap(getResponse.getBytes()));
                    break;
                case POST:
                    if (responseProcess.getResult() != null) {
                        if (responseProcess.getResult() instanceof StatusCode) {
                            String postResponse = RoboHttpUtils.createResponseByCode((StatusCode) responseProcess.getResult());
                            SocketUtil.writeBuffer(channel, ByteBuffer.wrap(postResponse.getBytes()));
                        } else {
                            String postResponse = RoboHttpUtils.createResponseByCode(StatusCode.ACCEPTED);
                            SocketUtil.writeBuffer(channel, ByteBuffer.wrap(postResponse.getBytes()));
                            for (RoboReference<Object> ref : targetRefs) {
                                if (responseProcess.getResult() != null && ref.getMessageType().equals(responseProcess.getResult().getClass())) {
                                    ref.sendMessage(responseProcess.getResult());
                                }
                            }
                        }
                    } else {
                        String notImplementedResponse = RoboHttpUtils.createResponseByCode(StatusCode.NOT_IMPLEMENTED);
                        SocketUtil.writeBuffer(channel, ByteBuffer.wrap(notImplementedResponse.getBytes()));
                    }
			default:
				break;
            }
        } else {
            String badResponse = RoboResponseHeader.headerByCode(StatusCode.BAD_REQUEST);
            SocketUtil.writeBuffer(channel, ByteBuffer.wrap(badResponse.getBytes()));
        }

        channelKeyMap.remove(channel);

        channel.close();
        key.cancel();
    }


    private void read(Selector selector, SelectionKey key) throws IOException {

        SocketChannel channel = (SocketChannel) key.channel();
        //@formatter:off
		HttpUriRegister.getInstance().updateUnits(getContext());
		final RoboRequestFactory factory = new RoboRequestFactory(CODEC_REGISTRY);

		ByteBuffer buffer = ByteBuffer.allocate(bufferCapacity);
		int readBytes = stopper == null ? SocketUtil.readBuffer(channel, buffer) : SocketUtil.readBuffer(channel, buffer, stopper);
		buffer.flip();
		if(buffer.remaining() == 0){
			buffer.clear();
			return;
		}

		ByteBuffer validBuffer = ByteBufferUtils.copy(buffer, 0, readBytes);
		final RoboRequestCallable callable = new RoboRequestCallable(this, validBuffer, factory);
		final Future<RoboResponseProcess> futureResult = getContext().getScheduler().submit(callable);

		try{
			RoboResponseProcess result = futureResult.get();
			outBuffers.put(key, result);
		} catch (InterruptedException | ExecutionException e){
			SimpleLoggingUtil.error(getClass(), "read" + e);
		}

		channel.register(selector, SelectionKey.OP_WRITE);
	}

	private boolean validatePackages(String packages) {
		if (packages == null) {
			return false;
		}
		for (int i = 0; i < packages.length(); i++) {
			char c = packages.charAt(i);
			// if (!Character.isJavaIdentifierPart(c) || c != ',' ||
			// !Character.isWhitespace(c)) {
			if (Character.isWhitespace(c)) {
				return false;
			}
		}
		return true;
	}

}
