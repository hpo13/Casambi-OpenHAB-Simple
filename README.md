# Casambi-OpenHAB-Simple
OpenHAB driver for the Casambi lighting system

Allows to control Casambi luminaries and scenes. On/off, dim and selection of lighting scenes are currently supported. No colors, color-temperature for lighting or input devices (switches) are supported yet. No auto-discovery either.

In order to define luminaries and scenes you will have to know their id numbers. These have to be configured during the setup of things. Items currently supported are switches (on/off) and dimmers (0-100 %).

In order to use the driver you will have to obtain a developer key from the Casambi organisation (https://developer.casambi.com/). With that you will have to setup a site and a network togeher with associated users and passwords. This is possible using the Casambi app (available for apple and android).

In order to control the Casambi devices you can use an Android (or Apple, not tested) mobile phone. This phone will have to be configured as a gatway an it needs to be running continuously with the Casambi App active, within bluetooth range of the luminaries that are to be controlled. Other setups (e.g. using a specialized bridge device) may be possible, but haven't been tested.

Using a mobile phone (Android in my case) presents its own challenges. The mobile phone will have to be awake all the time and the Casambi app needs to be active (and preferrably running in the foreground). This is a challenge with current power saving mechanisms that will put applications to sleep or terminate them altogether. For Android there is a way to insure continuous operation (see seperate document). For other systems I have no experience.

For further development I plan to add color, input devices and auto-discovery (probably in that order). Advanced features such as multiple networks, device status handling (overheating, overload), data collection, live network reconfiguration etc. are not on the map.
