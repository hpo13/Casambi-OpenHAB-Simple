/**
 * Copyright (c) 2010-2022 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.casambisimple.internal.driver;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.websocket.api.RemoteEndpoint;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketError;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiSimpleMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The {@link CasambiSimpleDriverSocket} manages the Websocket connection to the Casambi system
 *
 * It sets up the connection, sends commands to luminaires, scenes and groups and
 * processes messages from the system.
 *
 * Based on casambi-master (Python) by Olof Hellquist https://github.com/awahlig/casambi-master and
 * the Casambi documentation at https://developer.casambi.com/
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverSocket {

    private String casambiSocketStatus = "null";

    private final String apiKey;
    private final String casambiNetworkId;
    private final String casambiSessionId;
    private final Integer casambiWireId;
    private final CasambiSimpleDriverLogger casambiMessageLogger;
    private final WebSocketClient casambiWebSocketClient;

    private @Nullable Session casambiSession;
    private @Nullable CasambiListener casambiListener;
    private @Nullable RemoteEndpoint casambiRemote;
    private boolean socketClose = false;

    private @Nullable Future<?> reopenSocketJob;
    private volatile boolean reopenSocketJobRunning = false;

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1);
    private final LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final Lock socketLock = new ReentrantLock();
    private final Condition socketCondition = socketLock.newCondition();

    private final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverSocket.class);

    private final String socketUrl = "wss://door.casambi.com/v1/bridge/";
    private final long mSec = 1000;

    /**
     * CasambiSimpleDriverSocket constructor sets up the web-socket connection to the Casambi server. This is done
     * asynchronously so that the actual open for the socket can only proceed when an onOpen event has been received by
     * the listener.
     *
     * @param key
     * @param sessionId
     * @param networkId
     * @param wireId
     * @param messageLogger
     * @param webSocketClient
     *
     *            Also sets up the listener that handles the events on the web-socket.
     */
    CasambiSimpleDriverSocket(String key, String sessionId, String networkId, Integer wireId,
            CasambiSimpleDriverLogger messageLogger, WebSocketClient webSocketClient) {
        logger.debug("CasambiSimpleDriverSocket:constructor webSocketClient {}", webSocketClient);
        apiKey = key;
        casambiNetworkId = networkId;
        casambiSessionId = sessionId;
        casambiWireId = wireId;
        casambiMessageLogger = messageLogger;

        casambiWebSocketClient = webSocketClient;
        casambiSession = null;
        casambiListener = null;
        casambiRemote = null;
    }

    /**
     * open initializes the websocket connection. Needs networkId and wireId from the REST session. Waits for an onOpen
     * event from the listener before sending the actual open request.
     *
     * @return true if websocket was opened successfully
     */
    public Boolean open() {
        socketClose = false;

        logger.debug("casambiSocket.open, connecting to server for wire {}", casambiWireId);
        // logger.info("casambiOpen: setting up socket");
        casambiRemote = null;
        casambiListener = new CasambiListener();
        try {
            final URI casambiURI = new URI(socketUrl);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols(apiKey);
            Future<Session> futureSession = casambiWebSocketClient.connect(casambiListener, casambiURI, request);
        } catch (Exception e) {
            logger.error("casambiSocket.open, exception connecting to server - {}", e.getMessage());
        }

        logger.trace("casambiSocket.open: waiting for connection signal");
        boolean keepWaiting = true;
        socketLock.lock();
        try {
            while (casambiRemote == null && keepWaiting) {
                if (socketCondition.await(20L, TimeUnit.SECONDS)) {
                    logger.trace("casambiSocket.open, got connection signal, casambiRemote is '{}'", casambiRemote);
                } else {
                    keepWaiting = false;
                    logger.info("casambiSocket.open, timeout waiting for connection signal, casambiRemote is '{}'",
                            casambiRemote);
                }
            }
        } catch (Exception e) {
            logger.warn("casambiSocket.open, exception during await for connection signal - {}", e.getMessage());
        } finally {
            socketLock.unlock();
        }

        // No need to reopen in case of an error, this will be done by the listener (or will it?)
        boolean socketOk = false;
        final RemoteEndpoint lclCasambiRemote = casambiRemote;
        if (lclCasambiRemote != null) {

            final JsonObject reqJson = new JsonObject();
            reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "open");
            reqJson.addProperty(CasambiSimpleDriverConstants.targetId, casambiNetworkId);
            reqJson.addProperty("session", casambiSessionId);
            reqJson.addProperty("ref", UUID.randomUUID().toString());
            reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
            reqJson.addProperty("type", 1);
            try {
                lclCasambiRemote.sendString(reqJson.toString());
                casambiMessageLogger.dumpMessage("+++ Socket casambiOpen +++");
                logger.debug("casambiSocket.open: socket ok!");
                socketOk = true;
            } catch (Exception e) {
                logger.warn("casambiSocket.open, exception opening socket {}", e.getMessage());
            }
        } else {
            logger.warn("casambiSocket.open, error: no connection");
        }
        logger.trace("casambiSocket.open: return value {}", socketOk);
        return socketOk;
    }

    /**
     * reopen - tries to reopen the socket on close
     */
    public void reopen() {
        if (!socketClose && !reopenSocketJobRunning) {
            reopenSocketJob = scheduler.submit(runReopenSocket);
            // reopenSocketJob = casambiExecutor.submit(runReopenSocket);
            logger.debug("casambiSocket.reopen: runnable started.");

            casambiMessageLogger.dumpMessage("+++ Socket reopen +++");
            casambiSocketStatus = "reopening";
            final JsonObject msg = new JsonObject();
            msg.addProperty(CasambiSimpleDriverConstants.controlMethod, "socketChanged");
            msg.addProperty("status", "reopening");
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "ok");
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.reopen exception {}", e.getMessage());
            }
        } else {
            logger.warn("casambiSocket.reopen: socketClose or reopen job running, not started.");
        }
    }

    /**
     * runReopenSocket tries to reopen the casambi socket in case of an error. Rate limited
     *
     * FIXME: this could provide more diagnostics - network failure, name resolution failure, target not reachable,
     * target service not active...
     */
    private Runnable runReopenSocket = new Runnable() {
        @Override
        public void run() {
            int openErrorCount = 0;
            logger.trace("casambiSocket.runReopenSocket: runnable started");
            reopenSocketJobRunning = true;
            while (reopenSocketJobRunning) {
                if (Thread.interrupted()) {
                    logger.info("casambiSocket.runReopenSocket: got thread interrupt. Exiting job at openErrorCount {}",
                            openErrorCount);
                    reopenSocketJobRunning = false;
                    return;
                } else {
                    try {
                        if (openErrorCount <= 3) {
                            Thread.sleep(2 * mSec);
                        } else if (openErrorCount <= 10) {
                            Thread.sleep(60 * mSec);
                        } else {
                            Thread.sleep(1800 * mSec);
                        }
                        logger.debug("casambiSocket.runReopenSocket: openErrorCount {}", openErrorCount);
                        if (open()) {
                            logger.debug("casambiSocket.runReopenSocket, success at openErrorCount {}", openErrorCount);
                            reopenSocketJobRunning = false;
                        } else {
                            logger.warn("casambiSocket.runReopenSocket, open error at openErrorCount {}",
                                    openErrorCount);
                        }
                    } catch (Exception e) {
                        logger.debug("casambiSocket.runReopenSocket, sleep interrupted. Exiting");
                        Thread.currentThread().interrupt();
                    }
                }
                openErrorCount++;
            }
            logger.info("casambiSocket.runReopenSocket, exiting runnable at openErrorCount {}", openErrorCount);
        }
    };

    /**
     * close shuts down the websocket connection. Socket is set to null.
     *
     * It is an error to close a null socket.
     *
     * @return true if socket was closed successfully
     */
    public Boolean close() {
        logger.debug("casambiSocket.close, closing socket");
        socketClose = true;

        if (reopenSocketJobRunning) {
            if (reopenSocketJob != null) {
                reopenSocketJob.cancel(true);
            }
        }
        reopenSocketJobRunning = false;

        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "close");

        boolean socketOk = false;
        if (casambiRemote != null) {
            try {
                casambiRemote.sendString(reqJson.toString());
                if (casambiSession != null) {
                    casambiSession.close();
                }
                if (casambiListener != null) {
                    logger.trace("casambiSocket.close, awaitClose, casambiListener {}", casambiListener);
                    socketOk = casambiListener.awaitClose(5, TimeUnit.SECONDS);
                }
            } catch (IOException e) {
                logger.warn("casambiSocket.close: IO exception closing session {}", e.getMessage());
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.close: timeout closing session {}", e.getMessage());
            }
        } else {
            logger.warn("casambiSocket.close: error - remote connection not open.");
        }

        casambiMessageLogger.close();
        casambiRemote = null;
        casambiSession = null;
        return socketOk;
    }

    /**
     * Getter for socket_status
     *
     * @return casambiSocketStatus as set by the listener
     */
    public String getSocketStatus() {
        return casambiSocketStatus;
    }

    // --- Overridden WebSocket methods --------------------------------------------------------------------------------

    @WebSocket(maxTextMessageSize = 64 * 1024)
    public class CasambiListener {
        private final CountDownLatch closeLatch = new CountDownLatch(1);

        /**
         * opConnect handles the session open events.
         *
         * Here the open method is notified and a message is put into the queue to inform the bridge handler.
         *
         * @param session is the actual session opened on the socket
         */
        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException {
            logger.debug("casambiSocket.onConnect called, session {}", session);

            casambiSession = session;
            casambiRemote = casambiSession.getRemote();
            logger.trace("casambiSocket.onConnect signaling");
            socketLock.lock();
            try {
                socketCondition.signal();
            } catch (Exception e) {
                logger.warn("casambiSocket.onConnect signal exception {}", e.getMessage());
            } finally {
                socketLock.unlock();
            }

            casambiMessageLogger.dumpMessage("+++ Socket onOpen +++");
            casambiSocketStatus = "open";
            final JsonObject msg = new JsonObject();
            msg.addProperty(CasambiSimpleDriverConstants.controlMethod, "socketChanged");
            msg.addProperty("status", "open");
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "ok");
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.onConnect exception {}", e.getMessage());
            }
        }

        /**
         * onClose handles the session close events
         *
         * Here a message is queued for the bridge handler. The session is not reopened
         * after a scheduled close (socketClose == true), else it is reopened.
         *
         * @param session
         * @param status code
         * @param reason
         */
        @OnWebSocketClose
        public void onClose(Session session, int statusCode, String reason) {
            logger.info("casambiSocket.onClose session {}, status {}, reason {}", session, statusCode, reason);

            // Put 'closed' message into queue
            casambiMessageLogger.dumpMessage("+++ Socket onClose +++");
            casambiSocketStatus = "closed";
            final JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "closed");
            msg.addProperty("conditon", statusCode);
            msg.addProperty("response", reason);
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.warn("onClose: Exception {}", e.getMessage());
            }

            casambiRemote = null;
            casambiSession = null;
            closeLatch.countDown();

            if (socketClose) {
                logger.debug("casambiSocket.onClose socket being closed intentionally, not reopening");
            } else {
                logger.info("casambiSocket.onClose socket closed unexpectedly, reopening");
                reopen();
            }
        }

        /**
         * onText handles text data from the websocket. Not actually used by the Casambi system
         *
         * Messages are put into the queue for the bridge handler to process.
         *
         * @param session
         * @param message is the actual message
         */
        @OnWebSocketMessage
        public void onText(Session session, String message) {
            // logger.debug("onText: message {}", message);
            // FIXME: reset casambiSocketStatus to "open" here?
            try {
                if (message.length() > 0) {
                    queue.put(message);
                    casambiMessageLogger.dumpJsonWithMessage("+++ Socket onText +++", message);
                } else {
                    logger.debug("casambiSocket.onText null message");
                }
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.onText exception {}", e.getMessage());
            }
        }

        /**
         * onBinary handles binary data from the websocket. Used for messages by the casambi system
         *
         * Messages are put into the queue for the bridge handler to process.
         *
         * @param session
         * @param payload is the actual message
         * @param offset
         * @param length
         */
        @OnWebSocketMessage
        public void onBinary(Session session, byte[] payload, int offset, int length) {
            String message = new String(payload, StandardCharsets.UTF_8);
            // logger.debug("onBinary: message {}", message);
            // FIXME: reset casambiSocketStatus to "open" here?
            try {
                if (length > 0) {
                    queue.put(message);
                    casambiMessageLogger.dumpJsonWithMessage("+++ Socket onBinary +++", message);
                } else {
                    logger.debug("casambiSocket.onBinary null message");
                }
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.onBinary exception {}", e.getMessage());
            }
        }

        /**
         * onError handles the session error events
         *
         * Here a message is queued for the bridge handler and the session is (not) reopened
         * FIXME: reopen session or maybe reinitialize bridge
         *
         * @param session is the actual session
         * @param cause is the error record
         */
        @OnWebSocketError
        public void onError(Session session, Throwable cause) {
            logger.warn("casambiSocket.onError session {}, cause {}, message {}", session, cause.hashCode(),
                    cause.getMessage());

            // Put 'error' message into queue
            casambiMessageLogger.dumpMessage("+++ Socket onError +++");
            casambiSocketStatus = "error";
            final JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "error");
            msg.addProperty("conditon", cause.hashCode());
            msg.addProperty("response", cause.getMessage());
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.warn("casambiSocket.onError Exception {}", e.getMessage());
            }
        }

        /**
         * awaitClose is not currently used
         *
         * @param duration
         * @param unit
         * @return
         * @throws InterruptedException
         */
        public boolean awaitClose(int duration, TimeUnit unit) throws InterruptedException {
            return this.closeLatch.await(duration, unit);
        }
    }

    // --- luminaire controls -----------------------------------------------------------------------------------------

    /**
     * setUnitOnOff switches a Casambi luminaire on or off
     *
     * Currently works by setting brightness to 0 or 1
     * FIXME: might remember the last brightness of the device and restore that when switched on
     *
     * @param unitId
     * @param onOff
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    public void setUnitOnOff(int unitId, boolean onOff) throws CasambiSimpleException, IOException {
        setObjectOnOff(CasambiSimpleDriverConstants.methodUnit, CasambiSimpleDriverConstants.targetId, unitId, onOff);
    }

    /**
     * setSceneOnOff switches a Casambi scene on or off
     *
     * Currently works by setting brightness to 0 or 1
     * FIXME: might remember the last dim command and repeat that
     *
     * @param unitId
     * @param onOff
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    public void setSceneOnOff(int unitId, boolean onOff) throws CasambiSimpleException, IOException {
        setObjectOnOff(CasambiSimpleDriverConstants.methodScene, CasambiSimpleDriverConstants.targetId, unitId, onOff);
    }

    /**
     * setGroupOnOff switches a Casambi group on or off
     *
     * Currently works by setting brightness to 0 or 1
     * FIXME: might remember the last dim command and repeat that
     *
     * @param unitId
     * @param onOff
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    public void setGroupOnOff(int unitId, boolean onOff) throws CasambiSimpleException, IOException {
        setObjectOnOff(CasambiSimpleDriverConstants.methodGroup, CasambiSimpleDriverConstants.targetId, unitId, onOff);
    }

    /**
     * setObjectOnOff does the actual switching for , scenes and groups
     *
     * Works by setting brightness to 0 or 1
     *
     * @method selects luminaire, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaires are to be switched
     * @param objectId number of the object to be switched
     * @param onOff
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    private void setObjectOnOff(String method, @Nullable String id, int objectId, boolean onOff)
            throws CasambiSimpleException, IOException {
        final JsonObject cOnOff = new JsonObject();
        cOnOff.addProperty(CasambiSimpleDriverConstants.controlValue, onOff ? (float) 1.0 : (float) 0.0);

        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlOnOff, cOnOff);
        setObjectControl(method, id, objectId, control);
    }

    /**
     * setUnitDimmer sets the dim level of a Casambi luminaire
     *
     * @param unitId
     * @param dim level, must be between 0 and 1. O is equivalent to off, everything else is on
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    public void setUnitDimmer(int unitId, float dim) throws CasambiSimpleException, IOException {
        setObjectDimmer(CasambiSimpleDriverConstants.methodUnit, CasambiSimpleDriverConstants.targetId, unitId, dim);
    }

    /**
     * setObjectDimmer assembles the control message to dim luminaires
     *
     * Just used for luminaires, because a control-type message is needed.
     *
     * @method selects luminaire, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaires are to be switched
     * @param objectId number of the object to be switched
     * @param dim level, between 0 and 1
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    private void setObjectDimmer(String method, @Nullable String id, int objectId, float dim)
            throws CasambiSimpleException, IOException {
        final JsonObject dimmer = new JsonObject();
        dimmer.addProperty(CasambiSimpleDriverConstants.controlValue, dim);

        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlDimmer, dimmer);
        setObjectControl(method, id, objectId, control);
    }

    /**
     * setObjectControl puts together the complete message and sends it to the Casambi system
     *
     * Just used for luminaires, because a control-type message is needed.
     * FIXME: use setUnitControl() instead
     *
     * @method selects luminaire, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaires are to be switched
     * @param objectId number of the object to be switched
     * @param control, control part of the message
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException is thrown if message cannot be sent
     */
    private void setObjectControl(String method, @Nullable String id, int unitId, JsonObject control)
            throws CasambiSimpleException, IOException {

        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, method);
        if (id != null) {
            reqJson.addProperty(id, unitId);
        }
        reqJson.add(CasambiSimpleDriverConstants.controlTargetControls, control);
        logger.info("setObjectControl: unit {} control {}", unitId, reqJson.toString());

        if (casambiRemote != null) {
            casambiRemote.sendString(reqJson.toString());
            casambiMessageLogger.dumpMessage("+++ Session setObjectControl +++");
        } else {
            final String msg = "setObjectControl: Error - remote endpoint not open.";
            logger.error(msg);
            throw new CasambiSimpleException(msg);
        }
    }

    // This is for scenes, groups and the network

    /**
     * setSceneLevel sets the dim level for a scene
     *
     * @param unitId - id of the scene
     * @param dim - dim level
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setSceneLevel(int unitId, float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodScene, CasambiSimpleDriverConstants.targetId, unitId, dim);
    }

    /**
     * setGroupLevel sets the dim level for a group
     *
     * @param unitId - id of the group
     * @param dim - dim level
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setGroupLevel(int unitId, float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodGroup, CasambiSimpleDriverConstants.targetId, unitId, dim);
    }

    /**
     * setNetworkLevel sets the dim level for the whole network
     *
     * @param dim - dim level
     * @throws CasambiSimpleException
     * @throws IOException
     *             FIXME: allow for multiple networks
     */
    public void setNetworkLevel(float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodNetwork, null, 0, dim);
    }

    /**
     * setObjectLevel sets the dim level for an object (scene, group, network). Helper method for the above methods
     *
     * @param method - object to be dimmed (scene, group, network)
     * @param id - string: "id" or "ids" as appropriate
     * @param unitId - id to be dimmed
     * @param lvl - dim level (0-100)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    private void setObjectLevel(String method, @Nullable String id, int unitId, Float lvl)
            throws CasambiSimpleException, IOException {

        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, method);
        if (id != null) {
            reqJson.addProperty(id, unitId);
        }
        reqJson.addProperty(CasambiSimpleDriverConstants.controlLevel, lvl);
        logger.info("setObjectLevel: unit {} control {}", unitId, reqJson.toString());

        if (casambiRemote != null) {
            casambiRemote.sendString(reqJson.toString());
            casambiMessageLogger.dumpMessage("+++ Session setObjectLevel +++");
        } else {
            final String msg = "setObjectLevel: Error - remote endpoint not open.";
            logger.error(msg);
            throw new CasambiSimpleException(msg);
        }
    }

    /**
     * setUnitHSB sets Hue, Saturation and Brightness for a unit
     *
     * @param unitId - unit to be set
     * @param h - hue (0-360)
     * @param s - saturation (0-100)
     * @param b - brightness (0-100)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setUnitHSB(int unitId, float h, float s, float b) throws CasambiSimpleException, IOException {
        logger.info("setUnitHSB: unit {} hsb {},{},{}", unitId, h, s, b);

        final JsonObject rgbC = new JsonObject();
        rgbC.addProperty(CasambiSimpleDriverConstants.controlHue, h);
        rgbC.addProperty(CasambiSimpleDriverConstants.controlSat, s);

        // Uses rgb conversion from StackOverflow
        // int[] rgb = hslToRgb(h, s, b);
        // String rgbS = "rgb(" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ")";
        // rgbC.addProperty("rgb", rgbS);

        final JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiSimpleDriverConstants.controlSource, CasambiSimpleDriverConstants.controlRGB);
        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlRGB, rgbC);
        control.add(CasambiSimpleDriverConstants.controlColorsource, colorsource);
        setUnitDimmer(unitId, b);
        setUnitControl(unitId, control);
    }

    /**
     * setUnitCCT sets the color temperature for a unit
     *
     * @param unitId - unit to be set
     * @param temp - color temperature (between minimum and maximum temperature in degrees centigrade as specified by
     *            the device)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setUnitCCT(int unitId, float temp) throws CasambiSimpleException, IOException {
        final JsonObject colorTemperature = new JsonObject();
        colorTemperature.addProperty(CasambiSimpleDriverConstants.controlValue, temp);
        final JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiSimpleDriverConstants.controlSource, CasambiSimpleDriverConstants.controlTW);

        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlColorTemperature, colorTemperature);
        control.add(CasambiSimpleDriverConstants.controlColorsource, colorsource);
        setUnitControl(unitId, control);
    }

    /**
     * setUnitColorBalance sets the balance between color and white channels for a unit
     * FIXME: this may be obsolete, is not currently being used
     *
     * @param unitId - unit to be set
     * @param value - balance value (0-100)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setUnitColorBalance(int unitId, float value) throws CasambiSimpleException, IOException {
        final JsonObject colorBalance = new JsonObject();
        colorBalance.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlColorBalance, colorBalance);
        setUnitControl(unitId, control);
    }

    /**
     * setUnitWhiteLevel sets the level of the white channel for a unit
     * FIXME: this may be obsolete, is not currently being used
     *
     * @param unitId - unit to be set
     * @param value - level value (0-100)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void setUnitWhitelevel(int unitId, float value) throws CasambiSimpleException, IOException {
        final JsonObject whiteLevel = new JsonObject();
        whiteLevel.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        final JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlWhiteLevel, whiteLevel);
        setUnitControl(unitId, control);
    }

    /**
     * setUnitControl helper method to execute the HSB, CCT, ColorBalance and WhiteLevel commands above
     *
     * @param unitId - unit to be set
     * @param control - Json control record (see above)
     * @throws CasambiSimpleException
     * @throws IOException
     */
    private void setUnitControl(int unitId, JsonObject control) throws CasambiSimpleException, IOException {

        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, CasambiSimpleDriverConstants.methodUnit);
        reqJson.addProperty(CasambiSimpleDriverConstants.targetId, unitId);
        reqJson.add(CasambiSimpleDriverConstants.controlTargetControls, control);
        logger.info("setUnitControl: unit {} control {}", unitId, reqJson.toString());

        if (casambiRemote != null) {
            casambiRemote.sendString(reqJson.toString());
            casambiMessageLogger.dumpMessage("+++ Socket setUnitControl +++");
        } else {
            final String msg = "setUnitControl: Error - remote endpoint not open.";
            logger.error(msg);
            throw new CasambiSimpleException(msg);
        }
    }

    /**
     * ping sends keepalive message to the Casambi websocket
     *
     * @throws CasambiSimpleException
     * @throws IOException
     */
    public void ping() throws CasambiSimpleException, IOException {
        final JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "ping");

        if (casambiRemote != null) {
            casambiRemote.sendString(reqJson.toString());
        } else {
            final String msg = "ping: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiSimpleException(msg);
        }
    }

    // Get messages from the queue

    /**
     * Get raw message from the message queue (blocking)
     *
     * @return message as string
     */
    public @Nullable String receiveMessageRaw() /* throws InterruptedException */ {
        String res = null;
        do {
            try {
                // Blocks until there is something in the queue
                res = queue.take();
            } catch (InterruptedException e) {
                // FIXME: is this the right way to handle InterruptedException?
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception e) {
                logger.warn("receiveMessageJson: Exception {}", e.toString());
                // return null;
            }
        } while (res == null);
        return res;
    }

    /**
     * receiveMessage gets message from the queue as CasambiMessageEvent structure.
     *
     * @return CasambiSimpleMessageEvent structure
     */
    public @Nullable CasambiSimpleMessageEvent receiveMessage() {
        final Gson gson = new Gson();
        final String msg = receiveMessageRaw();
        if (msg != null) {
            // FIXME: why flush here?
            casambiMessageLogger.flush();
            CasambiSimpleMessageEvent event = gson.fromJson(msg, CasambiSimpleMessageEvent.class);
            return event;
        } else {
            return null;
        }
    }

    // --- RGB conversion ----------------------------------------------------------------------------------
    // https://stackoverflow.com/questions/2353211/hsl-to-rgb-color-conversion

    /**
     * Converts an HSL color value to RGB. Conversion formula
     * adapted from http://en.wikipedia.org/wiki/HSL_color_space.
     * Assumes h, s, and l are contained in the set [0, 1] and
     * returns r, g, and b in the set [0, 255].
     *
     * @param h The hue
     * @param s The saturation
     * @param l The lightness
     * @return int array, the RGB representation
     */
    // public static int[] hslToRgb(float h, float s, float l) {
    // float r, g, b;
    //
    // if (s == 0f) {
    // r = g = b = l; // achromatic
    // } else {
    // float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
    // float p = 2 * l - q;
    // r = hueToRgb(p, q, h + 1f / 3f);
    // g = hueToRgb(p, q, h);
    // b = hueToRgb(p, q, h - 1f / 3f);
    // }
    // int[] rgb = { to255(r), to255(g), to255(b) };
    // return rgb;
    // }
    //
    // public static int to255(float v) {
    // return (int) Math.min(255, 256 * v);
    // }
    //
    /** Helper method that converts hue to rgb */
    // public static float hueToRgb(float p, float q, float t) {
    // if (t < 0f) {
    // t += 1f;
    // }
    // if (t > 1f) {
    // t -= 1f;
    // }
    // if (t < 1f / 6f) {
    // return p + (q - p) * 6f * t;
    // }
    // if (t < 1f / 2f) {
    // return q;
    // }
    // if (t < 2f / 3f) {
    // return p + (q - p) * (2f / 3f - t) * 6f;
    // }
    // return p;
    // }
}
