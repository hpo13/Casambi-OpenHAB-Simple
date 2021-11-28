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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.casambisimple.internal.driver.messages.CasambiMessageEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

/**
 * The {@link CasambiDriverSocket} manages the Websocket connection to the Casambi system
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
public class CasambiDriverSocket implements WebSocket.Listener {

    // FIXME: get websocket from WebSocketClientFactory (see Coding Guidelines)
    // private @Nullable WebSocketFactory ws1Factory;
    private @Nullable WebSocket casambiSocket;
    private String casambiSocketStatus = "null";
    private LinkedBlockingQueue<String> queue = new LinkedBlockingQueue<>();

    private String apiKey;
    private String casambiNetworkId;
    private String casambiSessionId;
    private Integer casambiWireId;
    private CasambiDriverLogger casambiMessageLogger;

    final Logger logger = LoggerFactory.getLogger(CasambiDriverSocket.class);

    CasambiDriverSocket(String key, String sessionId, String networkId, Integer wireId,
            CasambiDriverLogger messageLogger) {
        apiKey = key;
        casambiNetworkId = networkId;
        casambiSessionId = sessionId;
        casambiWireId = wireId;
        casambiMessageLogger = messageLogger;
        // WebSocketClient ws2 = ws1Factory.createWebSocketClient("CasabmiSocket");
    }

    // import org.openhab.binding.casambisimple.internal.driver.CasambiDriverConstants.*;

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
        if (casambiSocket == null) {
            try {
                URI casambiURI = new URI("wss://door.casambi.com/v1/bridge/");

                UUID uuid = UUID.randomUUID();

                JsonObject reqJson = new JsonObject();
                reqJson.addProperty(CasambiDriverConstants.controlMethod, "open");
                reqJson.addProperty(CasambiDriverConstants.targetId, casambiNetworkId);
                reqJson.addProperty("session", casambiSessionId);
                reqJson.addProperty("ref", uuid.toString());
                reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
                reqJson.addProperty("type", 1);

                // CountDownLatch latch = new CountDownLatch(1);
                // Listener listener = new WebSocketClient();

                casambiSocket = HttpClient.newHttpClient().newWebSocketBuilder().subprotocols(apiKey)
                        .buildAsync(casambiURI, this).join();

                if (casambiSocket != null) {
                    casambiSocket.sendText(reqJson.toString(), true);
                    casambiMessageLogger.dumpMessage("+++ Socket casambiOpen +++");
                    socketOk = true;
                } else {
                    logger.error("casambiOpen: Error socket is null");
                }
            } catch (Exception e) {
                logger.error("casambiOpen: Exception opening websocket {}", e);
            }
        } else {
            logger.error("casambiOpen: Error - Socket already open.");
        }
        return socketOk;
    }

    // FIXME: Socket inaktivieren (ist das richtig so?)

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
        reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiDriverConstants.controlMethod, "close");

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            CompletableFuture<WebSocket> sc = casambiSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
            try {
                sc.wait(2 * 1000);
            } catch (InterruptedException e) {
                logger.info("casambiClose: Wait interrupted.");
            }
            casambiSocket = null;
            socketOk = true;
        } else {
            logger.error("casambiClose: Error - Socket not open.");
        }
        return socketOk;
    }

    // Getter for socket_status
    public String getSocketStatus() {
        return casambiSocketStatus;
    }

    // --- Overridden WebSocket methods --------------------------------------------------------------------------------

    /**
     * opOpen handles the websockets open events.
     *
     * Here a message is put into the queue to inform the bridge handler.
     *
     * @param webSocket is the actual websocket
     */
    @Override
    public void onOpen(@Nullable WebSocket webSocket) {
        logger.debug("WebSocket.onOpen called");
        casambiMessageLogger.dumpMessage(" +++ Socket onOpen +++");
        casambiSocketStatus = "open";
        JsonObject msg = new JsonObject();
        msg.addProperty(CasambiDriverConstants.controlMethod, "socketChanged");
        msg.addProperty("status", "open");
        msg.addProperty("conditon", 0);
        msg.addProperty("response", "ok");
        try {
            queue.put(msg.toString());
        } catch (InterruptedException e) {
            logger.error("onText: Exception {}", e);
        }
        WebSocket.Listener.super.onOpen(webSocket);
    }

    /**
     * onText handles text from the websocket. Not really needed here (casambi messages are binary).
     *
     * Messages are put into the queue for the bridge handler to process.
     *
     * @param websocket is the actual websocket
     * @param data is the text (or a segment of text)
     * @param last is true if this is the last segment
     */
    @Override
    public CompletionStage<?> onText(@Nullable WebSocket webSocket, @Nullable CharSequence data, boolean last) {
        // socket_status = "data_text";
        try {
            if (data != null && data.length() > 0) {
                queue.put(data.toString());
                casambiMessageLogger.dumpJsonWithMessage("+++ Socket onText +++", data.toString());
            } else {
                logger.debug("onText: null message");
            }
        } catch (InterruptedException e) {
            logger.error("onText: Exception {}", e);
        }
        return WebSocket.Listener.super.onText(webSocket, data, last);
    }

    /**
     * onBinary handles data from the websocket. Used for messages by the casambi system
     *
     * Messages are converted into text and put into the queue for the bridge handler to process.
     *
     * @param websocket is the actual websocket
     * @param data is the text (or a segment of text)
     * @param last is true if this is the last segment
     */
    @Override
    public CompletionStage<?> onBinary(@Nullable WebSocket webSocket, @Nullable ByteBuffer data, boolean last) {
        String msg = StandardCharsets.UTF_8.decode(data).toString();
        // socket_status = "data_binary";
        try {
            if (msg.length() > 0) {
                queue.put(msg);
                casambiMessageLogger.dumpJsonWithMessage("+++ Socket onBinary +++", msg);
            } else {
                logger.debug("onBinary: null message");
            }
        } catch (InterruptedException e) {
            logger.error("onText: Exception {}", e);
        }
        return WebSocket.Listener.super.onBinary(webSocket, data, last);
    }

    /**
     * onClose handles the websocket close events
     *
     * Here a message is queue for the bridge handler and the socket is reopened
     *
     * @param websocket is the actual websocket
     * @param status code
     * @param reason
     */
    @Override
    public CompletionStage<?> onClose(@Nullable WebSocket webSocket, int statusCode, @Nullable String reason) {
        logger.debug("onClose status {}, reason {}", statusCode, reason);
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
            logger.error("onText: Exception {}", e);
        }

        logger.warn("onClose: trying to reopen socket.");

        // FIXME: need to set casambiSocket = null before open?
        open();

        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    /**
     * onClose handles the websocket error events
     *
     * Here a message is queue for the bridge handler and the socket is reopened
     *
     * @param websocket is the actual websocket
     * @param error
     */
    @Override
    public void onError(@Nullable WebSocket webSocket, @Nullable Throwable error) {
        logger.error("WebSocket.onError {}", webSocket);
        casambiMessageLogger.dumpMessage("+++ Socket onError +++");
        casambiSocketStatus = "error";
        JsonObject msg = new JsonObject();
        msg.addProperty("method", "socketChanged");
        msg.addProperty("status", "error");
        if (error != null) {
            msg.addProperty("conditon", error.hashCode());
            msg.addProperty("response", error.getMessage());
        } else {
            msg.addProperty("conditon", 0);
            msg.addProperty("response", "no message");
        }
        try {
            queue.put(msg.toString());
        } catch (InterruptedException e) {
            logger.error("onText: Exception {}", e);
        }

        logger.warn("onError: trying to reopen socket.");
        // FIXME: need to set casambiSocket = null before open?
        open();

        WebSocket.Listener.super.onError(webSocket, error);
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
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    public void setUnitOnOff(int unitId, boolean onOff) throws CasambiException {
        setObjectOnOff(CasambiDriverConstants.methodUnit, CasambiDriverConstants.targetId, unitId, onOff);
    }

    /**
     * setSceneOnOff switches a Casambi scene on or off
     *
     * Currently works by setting brightness to 0 or 1
     * FIXME: might remember the last dim command and repeat that
     *
     * @param unitId
     * @param onOff
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    public void setSceneOnOff(int unitId, boolean onOff) throws CasambiException {
        setObjectOnOff(CasambiDriverConstants.methodScene, CasambiDriverConstants.targetId, unitId, onOff);
    }

    /**
     * setGroupOnOff switches a Casambi group on or off
     *
     * Currently works by setting brightness to 0 or 1
     * FIXME: might remember the last dim command and repeat that
     *
     * @param unitId
     * @param onOff
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    public void setGroupOnOff(int unitId, boolean onOff) throws CasambiException {
        setObjectOnOff(CasambiDriverConstants.methodGroup, CasambiDriverConstants.targetId, unitId, onOff);
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
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    private void setObjectOnOff(String method, @Nullable String id, int objectId, boolean onOff)
            throws CasambiException {
        JsonObject cOnOff = new JsonObject();
        cOnOff.addProperty(CasambiDriverConstants.controlValue, onOff ? (float) 1.0 : (float) 0.0);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlOnOff, cOnOff);
        setObjectControl(method, id, objectId, control);
    }

    /**
     * setUnitDimmer sets the dim level of a Casambi luminary
     *
     * @param unitId
     * @param dim level, must be between 0 and 1. O is equivalent to off, everything else is on
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    public void setUnitDimmer(int unitId, float dim) throws CasambiException {
        setObjectDimmer(CasambiDriverConstants.methodUnit, CasambiDriverConstants.targetId, unitId, dim);
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
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    private void setObjectDimmer(String method, @Nullable String id, int objectId, float dim) throws CasambiException {
        JsonObject dimmer = new JsonObject();
        dimmer.addProperty(CasambiDriverConstants.controlValue, dim);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlDimmer, dimmer);
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
     * @throws CasambiException is thrown on error, e.g. if the socket is not open
     */
    private void setObjectControl(String method, @Nullable String id, int unitId, JsonObject control)
            throws CasambiException {

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiDriverConstants.controlMethod, method);
        if (id != null) {
            reqJson.addProperty(id, unitId);
        }
        reqJson.add(CasambiDriverConstants.controlTargetControls, control);
        logger.info("setObjectControl: unit {} control {}", unitId, reqJson.toString());

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            casambiMessageLogger.dumpMessage("+++ Socket setObjectControl +++");
        } else {
            final String msg = "setObjectControl: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    // This is for scenes, groups and the network

    public void setSceneLevel(int unitId, float dim) throws CasambiException {
        setObjectLevel(CasambiDriverConstants.methodScene, CasambiDriverConstants.targetId, unitId, dim);
    }

    public void setGroupLevel(int unitId, float dim) throws CasambiException {
        setObjectLevel(CasambiDriverConstants.methodGroup, CasambiDriverConstants.targetId, unitId, dim);
    }

    public void setNetworkLevel(float dim) throws CasambiException {
        setObjectLevel(CasambiDriverConstants.methodNetwork, null, 0, dim);
    }

    private void setObjectLevel(String method, @Nullable String id, int unitId, Float lvl) throws CasambiException {

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiDriverConstants.controlMethod, method);
        if (id != null) {
            reqJson.addProperty(id, unitId);
        }
        reqJson.addProperty(CasambiDriverConstants.controlLevel, lvl);
        logger.info("setUnitLevel: unit {} control {}", unitId, reqJson.toString());

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            casambiMessageLogger.dumpMessage("+++ Socket setObjectControl +++");
        } else {
            final String msg = "setObjectControl: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    public void setUnitHSB(int unitId, float h, float s, float b) throws CasambiException {
        logger.info("setUnitHSB: unit {} hsb {},{},{}", unitId, h, s, b);
        JsonObject rgb = new JsonObject();
        rgb.addProperty(CasambiDriverConstants.controlHue, h);
        rgb.addProperty(CasambiDriverConstants.controlSat, s);
        JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiDriverConstants.controlSource, CasambiDriverConstants.controlRGB);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlRGB, rgb);
        control.add(CasambiDriverConstants.controlColorsource, colorsource);
        setUnitControl(unitId, control);
        setUnitDimmer(unitId, b);
    }

    public void setUnitCCT(int unitId, float temp) throws CasambiException {
        JsonObject colorTemperature = new JsonObject();
        colorTemperature.addProperty(CasambiDriverConstants.controlValue, temp);
        JsonObject colorsource = new JsonObject();
        colorsource.addProperty(CasambiDriverConstants.controlSource, CasambiDriverConstants.controlTW);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlColorTemperature, colorTemperature);
        control.add(CasambiDriverConstants.controlColorsource, colorsource);
        setUnitControl(unitId, control);
    }

    public void setUnitColorBalance(int unitId, float value) throws CasambiException {
        JsonObject colorBalance = new JsonObject();
        colorBalance.addProperty(CasambiDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlColorBalance, colorBalance);
        setUnitControl(unitId, control);
    }

    public void setUnitWhitelevel(int unitId, float value) throws CasambiException {
        JsonObject whiteLevel = new JsonObject();
        whiteLevel.addProperty(CasambiDriverConstants.controlValue, value);

        JsonObject control = new JsonObject();
        control.add(CasambiDriverConstants.controlWhiteLevel, whiteLevel);
        setUnitControl(unitId, control);
    }

    private void setUnitControl(int unitId, JsonObject control) throws CasambiException {

        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiDriverConstants.controlMethod, CasambiDriverConstants.methodUnit);
        reqJson.addProperty(CasambiDriverConstants.targetId, unitId);
        reqJson.add(CasambiDriverConstants.controlTargetControls, control);
        logger.info("setUnitControl: unit {} control {}", unitId, reqJson.toString());

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
            casambiMessageLogger.dumpMessage("+++ Socket setUnitValue +++");
        } else {
            final String msg = "setUnitValue: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
        }
    }

    // --- KeepAlive

    public void ping() throws CasambiException {
        JsonObject reqJson = new JsonObject();
        reqJson.addProperty(CasambiDriverConstants.controlWire, casambiWireId);
        reqJson.addProperty(CasambiDriverConstants.controlMethod, "ping");

        if (casambiSocket != null) {
            casambiSocket.sendText(reqJson.toString(), true);
        } else {
            final String msg = "ping: Error - Socket not open.";
            logger.error(msg);
            throw new CasambiException(msg);
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
    public @Nullable CasambiMessageEvent receiveMessage() {
        Gson gson = new Gson();
        String msg = receiveMessageJson();
        if (msg != null) {
            // Write Message to log file
            // dumpJsonWithMessage("+++ receiveMessage +++", msg);
            casambiMessageLogger.flush();
            CasambiMessageEvent event = gson.fromJson(msg, CasambiMessageEvent.class);
            // System.out.println("getUnitState: " + ppJson(unitInfo));
            return event;
        } else {
            return null;
        }
    }
}
