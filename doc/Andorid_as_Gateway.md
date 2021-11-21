# Using an Android Phone as a Casambi Gateway

For a private installation it may be desirable to use an old Android device as a gateway for the casambi system. Commercal gateways seem to be quite expensive and out the hobby range.

For testing I used an old Android 6 device that was still working well, but had performace and battery problems in day to day use. It is permanently hooked up to a charger and runs 24 hours a day. It does not have
a SIM card. 

## Problems with the setup

A number of problems had to be overcome for the device to be (more or less) useable.

<ul>
<li>Android battery optimisation - by default, Android will terminate app after a while, when it things that it is not used any more
<li>Keeping the Casambi app in the foreground - som apps will try to push themselves to the forground from time to time
<li>Regular reboots - my phone automatically reboots at a fixed time each day (not sure why).
<li>Bluetooth failures - from time to time the bluetooth subsystem of my device will fail to communicate with the casambi devices
</ul>

## Basic setup

The Casambi app will have to be installed and fully configured (including bluetooth and site features) and set up. Devices should be controllable with the app. 

It is advisable to remove all unneeded apps from the phone in order to have as few as possible sources of disturbances. 

The phone must be connected to your wlan (and able to connect to the casambi site) and bluetooth must be enabled.

## Battery Optimisation

Battery optimisation for the Casambi app has to be disabled. 

## Automated Start of the Casambi App

As I haven't figured out, how to automatically start the Casambi app from the Android system, this is being done with the Termux app. 

The Termux app provides you with a command line interface to the Android system, it can start Android apps from the command line and can start automatically on system boot.
It can also provide SSH access to the Android device.

With this, setup is made with the following steps:

<ul>
<li>Install Termux - I installed mine from the F-Droid app store, because there seem to be problems with the version in the Google app store
<li>Install Termux:Boot - This starts Termux on boot and allows to run commands on boot (see https://wiki.termux.com/wiki/Termux:Boot)
<li>Activate the Termux wake-lock - This prevents Android from putting the app to sleep or stopping it. This is done with a small script in the `/data/data/com.termux/files/home/.termux/boot` folder
<li>Autostart the Casambi App - This is also done by putting a small scipt into the `/data/data/com.termux/files/home/bin` folder. 
</ul>

Supplying the files to the Android device and getting them into the right places is the tricky bit. Editing with `vi` using the on-screen keyboard is not for everyone. As an alternative you can 
edit the files on your computer and use `scp` from the termux commanline on the Android phone (also not easy with the on-screen keyboard). Another method is transfer the files using a different channel
(e.g. download through the browser, use Bluetooth file transfer). Of course the challenge ist then to find the downloaded file from the Termux command line. 

In the end it is probably easiest to first set up SSH on the Android phone (see SSH section below) and then set up the rest.

The script to acquire the wakelock in `/data/data/com.termux/files/home/.termux/boot` is:

```
      #!/data/data/com.termux/files/usr/bin/sh
      termux-wake-lock
      ssh
```

The `ssh` line is only needed if you want to enable SSH access to your Android device (see section below).

The script to start the Casambi app in `/data/data/com.termux/files/home/bin` is:

```
      #!/data/data/com.termux/files/usr/bin/bash
      /data/data/com.termux/files/usr/bin/am start casambi.ambi/.ui.Casa
```
This should make sure that the Casambi App is started automatically and put into the foreground when the device boots. 

## SSH for (Limited) Remote Management of the Casambi App

Sometimes the Casambi app fails after a while. Either something terminates it or it is pushed into the backgroud. The Casambi driver will then receive a "peerChanged" message with the status offline.

Using a remote connection to the mobile phone it is possible to restart the Casambi app or put it back into the foreground. This can be achieved with a SSH connection to the phone.

Setup the SSH connection with the following steps:

<ul>
<li> Generate a new SSH key pair with `ssh-keygen` (no password)
<li> Store the key pair in the .ssh folder of the OpenHAB user and secure it properly. Name the private key something like `casambi_key` and the private key accordingly.
<li> Transfer the public key to the mobile phone and store it under `.ssh/authorized_keys`. 
<li> You should then be able login to your phone with `ssh <your-phone> -p 8022 -i .ssh\casambi_key `. 
Here `<your-phone>` is the IP-address or DNS-name of the Android gateway device. Port 8022 is used 
because Termux cannot access the standard ssh port. You should be able to login to your phone without supplying a password.
<li> Once you got here, you can set up the two files above using your computer.
<li> This also eanables you to manually restart the Casambi app with `am start casambi.ambi/.ui.Casa` from the termux command line.
<ul>

## Autostart the Casambi App from the driver

If you enable "Remote Command" in the Casambi Bridge settings, the driver will try to restart the Casambi app automaticall when it goes offline.
If you enable that option, you have to supply a command line for the driver to execute, if the gateway goes offline.

This command line may depend on the operating system that you are running OpenHAB on. For a standard Linux system you can use: 

```
      ssh <your-phone> -p 8022 -i .ssh\casambi_key am start casambi.ambi/.us.Casa
```

When the driver detects that the gateway is offline, it will send the wakeup command to the Android device three times with increasing delays and subsequently chech for a "peerChanged" message 
with status online. It will give up after three tries. However, if the device comes back online by itself afterwards the driver will notice and the device will be usable again.


