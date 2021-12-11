/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
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
 * Based on casambi-master by Olof Hellquist https://github.com/awahlig/casambi-master and
 * the Casambi documentation at https://developer.casambi.com/
 *
 * @author Hein Osenberg - Initial contribution
 */
@NonNullByDefault
public class CasambiSimpleDriverSocket {

    private String casambiSocketStatus = "null";
    private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    private String apiKey;
    private String casambiNetworkId;
    private String casambiSessionId;
    private Integer casambiWireId;
    private CasambiSimpleDriverLogger casambiMessageLogger;

    private @Nullable WebSocketClient casambiSocket;
    private @Nullable Session casambiSession;
    private @Nullable RemoteEndpoint casambiRemote;
    private Object socketFlag = new Object();

    final Logger logger = LoggerFactory.getLogger(CasambiSimpleDriverSocket.class);

    private final String socketUrl = "wss://door.casambi.com/v1/bridge/";

    CasambiSimpleDriverSocket(String key, String sessionId, String networkId, Integer wireId,
            CasambiSimpleDriverLogger messageLogger, WebSocketClient webSocketClient) {
        apiKey = key;
        casambiNetworkId = networkId;
        casambiSessionId = sessionId;
        casambiWireId = wireId;
        casambiMessageLogger = messageLogger;

        casambiSocket = webSocketClient;
        casambiSession = null;
        casambiRemote = null;

        CasambiListener listener = new CasambiListener();
        if (casambiSocket != null) {
            try {
                // logger.trace("CasambiSimpleDriverSocket: before casambiSocket.start");
                casambiSocket.start();
                final URI casambiURI = new URI(socketUrl);
                ClientUpgradeRequest request = new ClientUpgradeRequest();
                request.setSubProtocols(apiKey);
                // logger.trace("CasambiSimpleDriverSocket: before casambiSocket.connect");
                casambiSocket.connect(listener, casambiURI, request);
            } catch (Exception e) {
                logger.error("CasambiSimpleDriverSocket: Exception setting up session - {}", e.getMessage());
            }
        } else {
            logger.error("CasambiSimpleDriverSocket: casambiSocket is null");
        }
    }

    // import org.openhab.binding.casambisimple.internal.driver.CasambiSimpleDriverConstants.*;

    /**
     * open initialises the websocket connection. Needs networkId and wireId from the REST session.
     *
     * It is an error to open a websocket, when it is already open.
     *
     * @return true if websocket was opened successfully
     */
    public Boolean open() {

        boolean socketOk = false;
        logger.debug("casambiOpen: opening socket for casambi communication");
        UUID uuid = UUID.randomUUID();

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "open");
        reqJson.addProperty(CasambiSimpleDriverConstants.targetId, casambiNetworkId);
        reqJson.addProperty("session", casambiSessionId);
        reqJson.addProperty("ref", uuid.toString());
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty("type", 1);

        logger.trace("casambiOpen: waiting for casambiRemote");
        try {
            synchronized (socketFlag) {
                while (casambiRemote == null) {
                    socketFlag.wait();
                    // logger.trace("casambiOpen: wait is over");
                }
            }
        } catch (InterruptedException e) {
            logger.error("casambiOpen: Exception during wait - {}", e.getMessage());
            // e.printStackTrace();
        }

        if (casambiRemote != null) {
            try {
                // logger.trace("open: before casambiRemote.sendString");
                casambiRemote.sendString(reqJson.toString());
                casambiMessageLogger.dumpMessage("+++ Socket casambiOpen +++");
                socketOk = true;
            } catch (Exception e) {
                logger.error("casambiOpen: Exception opening remote connection {}", e.getMessage());
            }
        } else {
            logger.error("casambiOpen: Error - remote connection null.");
        }
        return socketOk;
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
        casambiMessageLogger.close();

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiSimpleDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiSimpleDriverConstants.controlMethod, "close");

        if (casambiRemote != null) {
            try {
                casambiRemote.sendString(reqJson.toString());
                casambiRemote = null;
                if (casambiSession != null) {
                    casambiSession.close();
                    casambiSession = null;
                }
                casambiSocket = null;
                socketOk = true;
            } catch (IOException e) {
                logger.error("casambiClose: Exception closing session {}", e.getMessage());
            }
        } else {
            logger.error("casambiClose: Error - remote connection not open.");
        }
        return socketOk;
    }

    // Getter for socket_status
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
         * Here a message is put into the queue to inform the bridge handler.
         *
         * @param session is the actual session opened on the socket
         */
        @OnWebSocketConnect
        public void onConnect(Session session) throws IOException {
            logger.debug("CasambiSocket.onConnect called");

            casambiSession = session;
            try {
                casambiRemote = casambiSession.getRemote();
                synchronized (socketFlag) {
                    socketFlag.notifyAll();
                }
            } catch (Exception e) {
                logger.error("onConnect: Exception {}", e.getMessage());
                // e.printStackTrace();
            }

            casambiMessageLogger.dumpMessage(" +++ Socket onOpen +++");
            casambiSocketStatus = "open";
            JsonObject msg = new JsonObject();
            msg.addProperty(CasambiSimpleDriverConstants.controlMethod, "socketChanged");
            msg.addProperty("status", "open");
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "ok");
            try {
                queue.put(msg.toString());
            } catch (InterruptedException e) {
                logger.error("onConnect: Exception {}", e.getMessage());
            }
        }

        /**
         * onClose handles the session close events
         *
         * Here a message is queued for the bridge handler and the session is not reopened
         * FIXME: Reopen the session if it was not closed by close()
         *
         * @param status code
         * @param reason
         */
        @OnWebSocketClose
        public void onClose(Session session, int statusCode, String reason) {
            logger.debug("onClose: status {}, reason {}", statusCode, reason);

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
                logger.error("onText: Exception {}", e.getMessage());
            }

            logger.warn("onClose: not trying to reopen socket.");
            casambiSocket = null;
            closeLatch.countDown();
            // open();
        }

        /**
         * onMessage handles text data from the websocket. Used for messages by the casambi system
         *
         * Messages are put into the queue for the bridge handler to process.
         *
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
                    logger.debug("onText: null message");
                }
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e.getMessage());
            }
        }

        /**
         * onMessage handles binary data from the websocket. Used for messages by the casambi system
         *
         * Messages are put into the queue for the bridge handler to process.
         *
         * @param message is the actual message
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
                    logger.debug("onText: null message");
                }
            } catch (InterruptedException e) {
                logger.error("onText: Exception {}", e.getMessage());
            }
        }

        /**
         * onError handles the session error events
         *
         * Here a message is queued for the bridge handler and the session is reopened
         *
         * @param session is the actual session
         * @param cause is the error record
         */
        @OnWebSocketError
        public void onError(Session session, Throwable cause) {
            logger.error("onError {}", session);
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
                logger.error("onError: Exception {}", e.getMessage());
            }

            logger.warn("onError: trying to reopen socket.");
            // close();
            casambiSession = null;
            open();
        }

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

    public void setSceneLevel(int unitId, float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodScene, CasambiSimpleDriverConstants.targetId, unitId, dim);
    }

    public void setGroupLevel(int unitId, float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodGroup, CasambiSimpleDriverConstants.targetId, unitId, dim);
    }

    public void setNetworkLevel(float dim) throws CasambiSimpleException, IOException {
        setObjectLevel(CasambiSimpleDriverConstants.methodNetwork, null, 0, dim);
    }

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

    public void setUnitHSB(int unitId, float h, float s, float b) throws CasambiSimpleException, IOException {
        logger.info("setUnitHSB: unit {} hsb {},{},{}", unitId, h, s, b);
        JsonObject rgb = new JsonObject();
        rgb.addProperty(CasambiSimpleDriverConstants.controlHue, h);
        rgb.addProperty(CasambiSimpleDriverConstants.controlSat, s);
        JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiSimpleDriverConstants.controlSource, CasambiSimpleDriverConstants.controlRGB);

        JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlRGB, rgb);
        control.add(CasambiSimpleDriverConstants.controlColorsource, colorsource);
        setUnitControl(unitId, control);
        setUnitDimmer(unitId, b);
    }

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

    public void setUnitColorBalance(int unitId, float value) throws CasambiSimpleException, IOException {
        JsonObject colorBalance = new JsonObject();
        colorBalance.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlColorBalance, colorBalance);
        setUnitControl(unitId, control);
    }

    public void setUnitWhitelevel(int unitId, float value) throws CasambiSimpleException, IOException {
        JsonObject whiteLevel = new JsonObject();
        whiteLevel.addProperty(CasambiSimpleDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
        control.add(CasambiSimpleDriverConstants.controlWhiteLevel, whiteLevel);
        setUnitControl(unitId, control);
    }

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

    // --- KeepAlive

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

    // Get messages off the queue

    // Get raw message
    public @Nullable String receiveMessageJson() {
        String res = null;
        do {
            try {
                // Blocks until there is something in the queue
                res = queue.take();
            } catch (Exception e) {
                logger.error("receiveMessageJson: Exception {}", e.getMessage());
            }
        } while (res == null);
        return res;
    }

    // Get message as CasambiMessageEvent structure
    public @Nullable CasambiSimpleMessageEvent receiveMessage() {
        Gson gson = new Gson();
        String msg = receiveMessageJson();
        if (msg != null) {
            // Write Message to log file
            // dumpJsonWithMessage("+++ receiveMessage +++", msg);
            casambiMessageLogger.flush();
            CasambiSimpleMessageEvent event = gson.fromJson(msg, CasambiSimpleMessageEvent.class);
            // System.out.println("getUnitState: " + ppJson(unitInfo));
            return event;
        } else {
            return null;
        }
    }
}
