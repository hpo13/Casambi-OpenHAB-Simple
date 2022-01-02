# Simple Casambi Binding

OpenHAB driver for the Casambi lighting system

## Overview

Allows to control lighting in a Casambi network. Dimming, color and  color temperature can be controlled. Discovery of luminaries, scenes and groups is possible.

This is currently in development based on the new 3.2.0 OpenHAB release. A 'jar' file is provided in the 'target' folder that can be installed by copying it into the 'addons' folder of your OpenHAB installation.

## Features

The driver allows to control luminaries that have previously been set up on the Casambi network.

## Supported Things

The binding supports lighting devices on the Casambi network. It was tested with the following devices

* Tridonic DALI dimmer - white light, dimming only
* LEDbyDESIGN LED Bulb - white light with color temperature adjustment
* LEDbyDESIGN E27 RGBW - color light bulb with white (including color temperature) and color (hsb) channels

Other devices should work as well, as long as the control types "Dimmer", "Color", "CCT", and "White Dimmer" are used.

As a gateway an old Android phone with the Casambi app (see Android or Apple app store) installed was used.

In case it matters, the binding was tested with the Casambi network running in EVOLUTION mode. 

## Discovery

Manual discovery is supported. For a device to be discovered it has to be included in the Casambi network.

If a device has been removed from the Casambi network, the corresponding thing will be removed automatically when discovery is run.

## Binding Configuration

Binding configuration is handled entirely through the the OpenHAB GUI (see below).

## Thing Configuration

Things are configured automatically upon discovery. Manual configuration is possible through the OpenHAB GUI, though discouraged. Only a few settings (e.g. name) may be changed without breaking the setup.

There are four types of Things

* Luminaries - Individual devices on the Casambi network
* Scenes - A set of devices together with individual settings that are defined as a scene in the Casambi system.
* Groups - A number of devices that have been grouped together in the Casambi system
* Bridge - The Network as a  whole can be dimmed through the bridge.

For individual devices, all configured channels can be controlled individually. For scenes, groups and the network only the overall dim level can be controlled.

The following parameters exist for luminaries, Scenes and Groups (all are set up automatically):

* Casambi Luminary Id - Luminary id number as defined by the Casambi system. Used to control devices, scenes and groups and to link received information the correspondig entities.
* Casambi Luminary Name - Name of the luminary. Used as default for the thing name. Although this is read from the Casambi system during discovery, this may be changed.
* Casambi Luminary unique Id - Used to uniquely identify a device on on the Casambi system. The luminary id will change, whenever a device is removed and then added to the Casambi system again. The unique id is based on the fixture id and will not change. For scenes and groups this is based on the Scene/Group Id and used to distinguish between the thing types. Should not be changed.

For luminaries, additional parameters exist (as advanced settings):

* Luminary can be dimmed - true if the luminary has a "Dimmer" control
* Luminary color temperature may be set - true if the luminary has a "CCT" control
* Minimum color temperature - in °K, only relevant if color temperature can be set. Minimum color temperature that can be set. Supplied by the casambi system.
* Maximum color temperature - like minimum color temperature
* Luminary color may be set - true if the luminary has a "Color" control
* Level of white component - true if the luminary has a "White Dimmer" control

## Channels

The four types of things currently have the following channels:

### Luminaries:

| channel | type | description |
|----------|--------|---------------------------------------|
| Item Dim | Dimmer | controls the brightness of the device |
| Luminary Color Temperature | Dimmer | controls the color temperature between the minium and maximum values provided by the configuration |
| Luminary Color | Color | controls the color of the light using hue, saturation and brightness |
| Luminary White Level | Dimmer | controls the balance between white and color light on a device that supports it |

### Scenes and Groups:

| channel | type | description |
|----------|--------|---------------------------------------|
|Scene/Group Dim | Dimmer | controls the overall brightness of a scene or a group. This is write-only in the sense, that brightness levels can be set, but not read back |

### The Network:

| channel | type | description |
|----------|--------|---------------------------------------|
| Item Dim | Dimmer | controls the overall brightness of a scene or a group. This is write-only in the sense, that brightness levels can be set, but not read back |
| Casambi Message | Text | Shows the last status message from the Casambi system |
| Online Status | Contact | Shows if the gateway is online or not (currently not working) |

All Channels are setup automatically together with things they belong to. For luminaries, only the channels that can be controlled for the device are activated.

## Prerequisites

You will have to setup a site and a network together with associated users and passwords. At least one luminary should be connected to the network and be controllable with the Casambi app.
With that you will have to obtain a developer key from the Casambi organization (https://developer.casambi.com/). 

In addition you will need a gateway. An Android (or Apple, not tested) device can be used together with the Casambi app (see app store).
The gateway (mobile) needs to be running continuously with the Casambi app active, within bluetooth range of the luminaries that are to be controlled. 

All device configuration (adding, removing, naming, firmware updates etc.) will have to be done with the Casambi app.

## Setup

Activate the Casambi binding by copying the 'jar' file into the 'addons' directory of the OpenHAB installation. When you then add things you will then be able to select the 'Simple Casambi Binding'. 
First add a 'Casambi Bridge'  and configure it. For the connection you will need your API key, user id and user and network passwords for the Casambi network. Further you can enable logging of the 
messages received from the Casambi network. You will have to supply a path that is writable by your OpenHAB instance (e.g. /var/log/openhab. For first tests the Remote Command section can be left inactive.

After successfully setting up the bridge (it should appear as thing with the status online) you can set up luminaries, lighting scenes and groups of luminaries through discovery. 
For this, you have to select add things again, select the Simple Casambi Binding and hit the "Scan" button. Your devices, scenes and groups should appear in the inbox with their names from the Casambi system.

Things will have channels corresponding to their features. Depending on the capabilities of the device dimming, selection of color and color temperature and balance between white and color modes 
are supported.

Most of the properties of things can also be set up manually, but it is best not to change anything.

The setup is possible even when the gateway is not active. In order to actually control the lights the gateway has of course to be active and be located within bluetooth range of the luminaries.

## Limitations

The driver currently supports luminaries only. Neither switches nor sensors are supported. Luminaries support may be spotty because I only have access to a couple of devices. 
Missing are Slider, XY and RGB color modes (only HSB is supported) and probably many more. More advanced features of Casambi devices like 'status', power consumption or sensor data are not supported. The Hue bridge was not tested either.

Also the driver is currently limited to one network (and one site). 

There is no internationalization and tests are missing as well.

## Operation with an Android Phone as a Gateway 

Using a mobile phone (Android in my case) presents its own challenges. The mobile phone will have to be awake all the time and the Casambi app needs to be active 
(and preferably running in the foreground). For this you will have to disable the battery optimization for the Casambi app. For details on
unattended operation see doc/Android_as_Gateway.md.

## Example Casambi Controls

The following is the controls section for a single device as part of a Casambi NetworkState message.

```
      "controls": [
        [
          {
            "name": "dimmer0",
            "type": "Dimmer",
            "value": 0.0
          },
          {
            "sat": 1.0,
            "name": "rgb",
            "hue": 1.0,
            "rgb": "rgb(255,  0,  4)",
            "type": "Color"
          },
          {
            "min": 0,
            "max": 100,
            "name": "White Dimmer",
            "label": "White Dimmer",
            "type": "Slider",
            "value": 100.0
          },
          {
            "min": 2200,
            "max": 6500,
            "name": "CCT",
            "label": "CCT",
            "type": "Slider",
            "value": 4358.431372549019
          }
        ]
      ],
```
Included are four controls for brightness (Dimmer), color (Color), white leve (White Dimmer) and color temperature (CCT). Some of the information seems to be vendor dependent. It remains to be seen how much the structure varies between different vendors and how this affects operation of the driver

## Known bugs

* Color (hue) setting gives wrong color
* Bridge channel 'Online status' does not work

For more issues concerning code cleanup, additional functionality, errors and production preparation see doc/ToDos_and_Issues.md
