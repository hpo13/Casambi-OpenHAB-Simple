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
import java.util.concurrent.LinkedBlockingQueue;
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
 * It sets up the connection, sends commands to luminaries, scenes and groups and
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

    private String apiKey;
    private String casambiNetworkId;
    private String casambiSessionId;
    private Integer casambiWireId;
    private CasambiSimpleDriverLogger casambiMessageLogger;

    private @Nullable WebSocketClient casambiSocket;
    private @Nullable Session casambiSession;
    private @Nullable CasambiListener casambiListener;
    private @Nullable RemoteEndpoint casambiRemote;
    private boolean socketClose = false;

    private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private int openErrorCount = 0;
    private Lock socketLock = new ReentrantLock();
    private Condition socketCondition = socketLock.newCondition();

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverSocket.class);

    private final String socketUrl = "wss://door.casambi.com/v1/bridge/";
    private final int mSec = 1000;

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
     *
     *            FIXME: catch errors here or transmit them to caller?
     */
    CasambiSimpleDriverSocket(String key, String sessionId, String networkId, Integer wireId,
            CasambiSimpleDriverLogger messageLogger, WebSocketClient webSocketClient) {
        logger.debug("CasambiSimpleDriverSocket:constructor webSocketClient {}", webSocketClient);
        apiKey = key;
        casambiNetworkId = networkId;
        casambiSessionId = sessionId;
        casambiWireId = wireId;
        casambiMessageLogger = messageLogger;

        casambiSocket = webSocketClient;
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
        boolean socketOk = false;

        logger.info("casambiSocket:open, opening socket for casambi communication");
        // logger.info("casambiOpen: setting up socket");
        casambiRemote = null;
        casambiListener = new CasambiListener();
        try {
            final URI casambiURI = new URI(socketUrl);
            ClientUpgradeRequest request = new ClientUpgradeRequest();
            request.setSubProtocols(apiKey);
            if (casambiSocket != null) {
                WebSocketClient casambiSocketLocal = casambiSocket;
                // We don't need to start the shared socket
                // casambiSocketLocal.start();
                casambiSocketLocal.connect(casambiListener, casambiURI, request);
            } else {
                logger.error("casambiSocket:open, casambiSocket is null");
            }
        } catch (Exception e) {
            logger.error("casambiSocket:open, exception setting up session - {}", e.getMessage());
        }

        logger.info("casambiSocket:open: waiting for socketCondition");
        boolean keepWaiting = true;
        socketLock.lock();
        try {
            while (casambiRemote == null && keepWaiting) {
                if (socketCondition.await(20L, TimeUnit.SECONDS)) {
                    logger.info("casambiSocket:open, got signal, opened casambiRemote '{}'", casambiRemote);
                } else {
                    keepWaiting = false;
                    logger.warn("casambiSocket:open, timeout opening casambiRemote '{}'", casambiRemote);
                }
            }
        } catch (Exception e) {
            logger.error("casambiSocket:open, exception during await - {}", e.getMessage());
        } finally {
            socketLock.unlock();
        }

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "open");
        reqJson.addProperty(CasambiSimpleDriverConstants.targetId, casambiNetworkId);
        reqJson.addProperty("session", casambiSessionId);
        reqJson.addProperty("ref", UUID.randomUUID().toString());
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty("type", 1);

        // No need to reopen in case of an error, this will be done by the listener (or will it?)
        if (casambiRemote != null) {
            try {
                casambiRemote.sendString(reqJson.toString());
                casambiMessageLogger.dumpMessage("+++ Socket casambiOpen +++");
                logger.info("casambiSocket:open: socket ok!");
                socketOk = true;
            } catch (Exception e) {
                logger.error("casambiSocket:open, exception opening remote connection {}", e.getMessage());
            }
        } else {
            logger.error("casambiSocket:open, error - remote connection null");
        }
        openErrorCount = 0;
        return socketOk;
    }

    /**
     * reopen - tries to reopen the socket on error or close. Rate limited
     */
    private void reopen() {
        openErrorCount++;
        logger.info("casambiSocket:reopen, openErrorCount {}", openErrorCount);
        try {
            if (openErrorCount <= 3) {
                Thread.sleep(1 * mSec);
            } else if (openErrorCount <= 10) {
                Thread.sleep(60 * mSec);
            } else {
                Thread.sleep(3600 * mSec);

            }
            if (!open()) {
                logger.error("ocasambiSocket:reopen, error reopening socket");
            }
        } catch (Exception e) {
            logger.warn("casambiSocket:reopen, sleep interrupted");
        }
    }

    /**
     * close shuts down the websocket connection. Socket is set to null.
     *
     * It is an error to close a null socket.
     *
     * @return true if socket was closed successfully
     */
    public Boolean close() {
        boolean socketOk = false;
        logger.info("casambiSocket:close, closing socket");

        socketClose = true;
        casambiMessageLogger.close();

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "close");

        if (casambiRemote != null) {
            try {
                casambiRemote.sendString(reqJson.toString());
                if (casambiSession != null) {
                    casambiSession.close();
                }
                logger.trace("casambiSocket:close, awaitClose, casambiListener {}", casambiListener);
                if (casambiListener != null) {
                    socketOk = casambiListener.awaitClose(5, TimeUnit.SECONDS);
                }
                // socketOk = true;
            } catch (IOException e) {
                logger.error("casambiSocket:close: IO exception closing session {}", e.getMessage());
            } catch (InterruptedException e) {
                logger.error("casambiSocket:close: timeout closing session {}", e.getMessage());
            }
        } else {
            logger.error("casambiSocket:close: error - remote connection not open.");
        }
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
            logger.info("casambiSocket:onConnect called, session {}", session);

            casambiSession = session;
            casambiRemote = casambiSession.getRemote();
            logger.debug("casambiSocket:onConnect signaling");
            socketLock.lock();
            try {
                socketCondition.signal();
            } catch (Exception e) {
                logger.error("casambiSocket:onConnect signal exception {}", e.getMessage());
            } finally {
                socketLock.unlock();
            }

            casambiMessageLogger.dumpMessage("+++ Socket onOpen +++");
            casambiSocketStatus = "open";
            JsonObject msg = new JsonObject();
            msg.addProperty(CasambiSimpleDriverConstants.controlMethod, "socketChanged");
            msg.addProperty("status", "open");
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "ok");
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("casambiSocket:onConnect exception {}", e.getMessage());
            }
        }

        /**
         * onClose handles the session close events
         *
         * Here a message is queued for the bridge handler. The session is not reopened
         * after a scheduled close (socketClose == true), else it is reopened.
         *
         * FIXME: do not try to reopen socket when bridge is being taken down
         *
         * @param session
         * @param status code
         * @param reason
         */
        @OnWebSocketClose
        public void onClose(Session session, int statusCode, String reason) {
            logger.warn("casambiSocket:onClose session {}, status {}, reason {}", session, statusCode, reason);

            // Put 'closed' message into queue
            casambiMessageLogger.dumpMessage("+++ Socket onClose +++");
            casambiSocketStatus = "closed";
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "closed");
            msg.addProperty("conditon", statusCode);
            msg.addProperty("response", reason);
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("onClose: Exception {}", e.getMessage());
            }

            casambiRemote = null;
            casambiSession = null;
            closeLatch.countDown();

            if (socketClose) {
                logger.info("casambiSocket:onClose socket being closed intentionally, not reopening");
                socketClose = false;
            } else {
                logger.warn("casambiSocket:onClose socket closed unexpectedly, reopening");
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
            try {
                if (message.length() > 0) {
                    queue.put(message);
                    casambiMessageLogger.dumpJsonWithMessage("+++ Socket onText +++", message);
                } else {
                    logger.debug("casambiSocket:onText null message");
                }
            } catch (InterruptedException e) {
                logger.error("casambiSocket:onText exception {}", e.getMessage());
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
            try {
                if (length > 0) {
                    queue.put(message);
                    casambiMessageLogger.dumpJsonWithMessage("+++ Socket onBinary +++", message);
                } else {
                    logger.debug("casambiSocket:onBinary null message");
                }
            } catch (InterruptedException e) {
                logger.error("casambiSocket:onBinary exception {}", e.getMessage());
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
            logger.error("casambiSocket:onError session {}, cause {}, message {}", session, cause.hashCode(),
                    cause.getMessage());

            // Put 'error' message into queue
            casambiMessageLogger.dumpMessage("+++ Socket onError +++");
            casambiSocketStatus = "error";
            JsonObject msg = new JsonObject();
            msg.addProperty("method", "socketChanged");
            msg.addProperty("status", "error");
            msg.addProperty("conditon", cause.hashCode());
            msg.addProperty("response", cause.getMessage());
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("casambiSocket:onError Exception {}", e.getMessage());
            }

            // FIXME: maybe do a complete reinitialisation of the bridge
            // evtl. neues runnable, mit variabler Wartezeit, das die Verbindung komplett neu initialisiert
            // (initCasambiSession)
            // logger.warn("onError: trying to reopen socket.");
            // reopen();

            // Reopening should not be done here, will be handled by 'onClose'. Else there will be multiple open sockets
            logger.warn("casambiSocket:onError not reopening socket.");
            // reopen();
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

    // --- Luminary controls -----------------------------------------------------------------------------------------

    /**
     * setUnitOnOff switches a Casambi luminary on or off
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
     * setObjectOnOff does the actual switching for luminaries, scenes and groups
     *
     * Works by setting brightness to 0 or 1
     *
     * @method selects luminary, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaries are to be switched
     * @param objectId number of the object to be switched
     * @param onOff
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    private void setObjectOnOff(String method, @Nullable String id, int objectId, boolean onOff)
            throws CasambiSimpleException, IOException {
        JsonObject cOnOff = new JsonObject();
        cOnOff.addProperty(CasambiSimpleDriverConstants.controlValue, onOff ? (float) 1.0 : (float) 0.0);

        JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlOnOff, cOnOff);
        setObjectControl(method, id, objectId, control);
    }

    /**
     * setUnitDimmer sets the dim level of a Casambi luminary
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
     * setObjectDimmer assembles the control message to dim luminaries
     *
     * Just used for luminaries, because a control-type message is needed.
     *
     * @method selects luminary, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaries are to be switched
     * @param objectId number of the object to be switched
     * @param dim level, between 0 and 1
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException
     */
    private void setObjectDimmer(String method, @Nullable String id, int objectId, float dim)
            throws CasambiSimpleException, IOException {
        JsonObject dimmer = new JsonObject();
        dimmer.addProperty(CasambiSimpleDriverConstants.controlValue, dim);

        JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlDimmer, dimmer);
        setObjectControl(method, id, objectId, control);
    }

    /**
     * setObjectControl puts together the complete message and sends it to the Casambi system
     *
     * Just used for luminaries, because a control-type message is needed.
     * FIXME: use setUnitControl() instead
     *
     * @method selects luminary, group or scene (method attribute of the JSON message)
     * @param id usually just "id", may be set to "ids", when multiple luminaries are to be switched
     * @param objectId number of the object to be switched
     * @param control, control part of the message
     * @throws CasambiSimpleException is thrown on error, e.g. if the socket is not open
     * @throws IOException is thrown if message cannot be sent
     */
    private void setObjectControl(String method, @Nullable String id, int unitId, JsonObject control)
            throws CasambiSimpleException, IOException {

        JsonObject reqJson = new JsonObject();
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

        JsonObject reqJson = new JsonObject();
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

        JsonObject rgbC = new JsonObject();
        if (true) {
            rgbC.addProperty(CasambiSimpleDriverConstants.controlHue, h);
            rgbC.addProperty(CasambiSimpleDriverConstants.controlSat, s);
        } else {
            int[] rgb = hslToRgb(h, s, b);
            String rgbS = "rgb(" + rgb[0] + ", " + rgb[1] + ", " + rgb[2] + ")";
            rgbC.addProperty("rgb", rgbS);
        }
        JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiSimpleDriverConstants.controlSource, CasambiSimpleDriverConstants.controlRGB);
        JsonObject control = new JsonObject();
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
        JsonObject colorTemperature = new JsonObject();
        colorTemperature.addProperty(CasambiSimpleDriverConstants.controlValue, temp);
        JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiSimpleDriverConstants.controlSource, CasambiSimpleDriverConstants.controlTW);

        JsonObject control = new JsonObject();
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
        JsonObject colorBalance = new JsonObject();
        colorBalance.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
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
        JsonObject whiteLevel = new JsonObject();
        whiteLevel.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
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

        JsonObject reqJson = new JsonObject();
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
        JsonObject reqJson = new JsonObject();
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
    public @Nullable String receiveMessageRaw() {
        String res = null;
        do {
            try {
                // Blocks until there is something in the queue
                res = queue.take();
            } catch (Exception e) {
                logger.warn("receiveMessageJson: Exception {}", e.toString());
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
        Gson gson = new Gson();
        String msg = receiveMessageRaw();
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
    public static int[] hslToRgb(float h, float s, float l) {
        float r, g, b;

        if (s == 0f) {
            r = g = b = l; // achromatic
        } else {
            float q = l < 0.5f ? l * (1 + s) : l + s - l * s;
            float p = 2 * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }
        int[] rgb = { to255(r), to255(g), to255(b) };
        return rgb;
    }

    public static int to255(float v) {
        return (int) Math.min(255, 256 * v);
    }

    /** Helper method that converts hue to rgb */
    public static float hueToRgb(float p, float q, float t) {
        if (t < 0f) {
            t += 1f;
        }
        if (t > 1f) {
            t -= 1f;
        }
        if (t < 1f / 6f) {
            return p + (q - p) * 6f * t;
        }
        if (t < 1f / 2f) {
            return q;
        }
        if (t < 2f / 3f) {
            return p + (q - p) * (2f / 3f - t) * 6f;
        }
        return p;
    }
}
