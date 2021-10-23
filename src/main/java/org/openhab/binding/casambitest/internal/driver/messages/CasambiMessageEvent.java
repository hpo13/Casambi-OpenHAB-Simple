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
package org.openhab.binding.casambitest.internal.driver.messages;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * The {@link CasambiMessageEvent} is used to parse event messages with gson
 *
 * @author Hein Osenberg - Initial contribution
 * @version V0.1 210827@hpo First version, setup IDE
 */
@NonNullByDefault
public class CasambiMessageEvent {

    public static enum messageType {
        unitChanged,
        peerChanged,
        networkUpdated,
        socketChanged, // Driver message (not Casambi message)
        wireStatusOk,
        wireStatusError,
        keepAlive,
        unknownMessage
    };

    public @Nullable String method;
    public @Nullable Integer priority;
    public @Nullable Integer id;
    public @Nullable Integer groupId;
    public @Nullable Integer position;
    public @Nullable String address;
    public @Nullable String name;
    public @Nullable Integer fixtureId;
    public @Nullable String type;
    public @Nullable Integer condition;
    public @Nullable Integer wire;
    // public Set<String> sensors;
    public @Nullable Boolean online;
    public @Nullable Integer activeSceneId;
    public @Nullable Float dimLevel;
    // public ComplicatedStructure details;
    public @Nullable Boolean on;
    public @Nullable String status;
    public @Nullable CasambiMessageControl @Nullable [] controls;
    public @Nullable String ref;
    public @Nullable String wireStatus;
    public @Nullable String response;

    CasambiMessageEvent() {
        id = 0;
        name = "";
        address = "";
        controls = null;
    };

    public messageType getMessageType() {
        if (method != null) {
            if (method.compareTo("unitChanged") == 0) {
                return messageType.unitChanged;
            } else if (method.compareTo("peerChanged") == 0) {
                return messageType.peerChanged;
            } else if (method.compareTo("networkUpdated") == 0) {
                return messageType.networkUpdated;
            } else if (method.compareTo("socketChanged") == 0) {
                return messageType.socketChanged;
            } else {
                return messageType.unknownMessage;
            }
        } else if (wireStatus != null) {
            if (wireStatus.compareTo("openWireSucceed") == 0) {
                return messageType.wireStatusOk;
            } else {
                return messageType.wireStatusError;
            }
        } else if (response != null) {
            if (response.compareTo("pong") == 0) {
                return messageType.keepAlive;
            } else {
                return messageType.unknownMessage;
            }
        } else {
            return messageType.unknownMessage;
        }
    }
}