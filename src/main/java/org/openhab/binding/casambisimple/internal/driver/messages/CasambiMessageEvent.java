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
package org.openhab.binding.casambisimple.internal.driver.messages;

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
            if ("unitChanged".equals(method)) {
                return messageType.unitChanged;
            } else if ("peerChanged".equals(method)) {
                return messageType.peerChanged;
            } else if ("networkUpdated".equals(method)) {
                return messageType.networkUpdated;
            } else if ("socketChanged".equals(method)) {
                return messageType.socketChanged;
            } else {
                return messageType.unknownMessage;
            }
        } else if (wireStatus != null) {
            if ("openWireSucceed".equals(wireStatus)) {
                return messageType.wireStatusOk;
            } else {
                return messageType.wireStatusError;
            }
        } else if (response != null) {
            if ("pong".equals(response)) {
                return messageType.keepAlive;
            } else {
                return messageType.unknownMessage;
            }
        } else {
            return messageType.unknownMessage;
        }
    }

    @Override
    public String toString() {
        String s;
        switch (getMessageType()) {
            case unitChanged:
                s = String.format("unitChanged - id: %d, name: %s, on: %b, dimLevel: %.2f ", id, name, on, dimLevel);
                break;
            case peerChanged:
                s = String.format("peerChanged - online: %b, wire: %d", online, wire);
                break;
            case networkUpdated:
                s = String.format("networkUpdated - ");
                break;
            case socketChanged:
                s = String.format("socketChanged -  status %s, message %s", status, response);
                break;
            case wireStatusOk:
                s = String.format("wireStatusOk - status %s", wireStatus);
                break;
            case wireStatusError:
                s = String.format("wireStatusError - status %s", wireStatus);
                break;
            case keepAlive:
                s = String.format("keepAlive - %s", response);
                break;
            case unknownMessage:
                s = String.format("unknownMessage - ");
                break;
            default:
                s = String.format("illegal message type - ");
                ;
        }
        return s;
    }
}
