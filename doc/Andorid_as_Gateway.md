# Setting up an Android Device as a Casambi Gateway

At first I used an old Android phone as a Casambi gateway. However this has some disadvantages (see below) and I am now using a dedicated single board computer (KHADAS VIM3) with an Android image installed as a gateway.   

I decided to set up my own gateway system as commercial gateways seem to be quite expensive and out the hobby range.

## Problems with using an old Android phone

A number of problems occur with Android phones. Some of these can be overcome and others not.

* Android battery optimisation - by default, Android will terminate app after a while, when it things that it is not used any more
* Keeping the Casambi app in the foreground - some apps will try to push themselves to the foreground from time to time
* Regular reboots - my phone automatically reboots at a fixed time each day (not sure why).
* Bluetooth failures - from time to time the bluetooth subsystem of my device will fail to communicate with the Casambi devices

## Single board computer used as a gateway

For my gateway I am using a KHADAS VIM3 SBC with a 6-core cpu and 2GB of RAM. This runs Android at reasonable (though not excessive) speed. Khadas supplies Android images with release 9, which is enough for the gateway. The SBC is run headless, screen, keyboard and mouse are supplied through VNC.

Setup and configuration is a lot easier, if phyical screen, keyboard and mouse are connected to the SBC during that phase. If possible use a wired Ethernet connection which will make setup a lot easier. In addition to the operating system, the following apps have to be set up:

* The Casambi App
* Termux (see below)
* droidVNC-NG
* F-Droid

The Casambi App an F-Droid will have to be installed through APK-files (Possible with the App-Installer supplied with the system image). 
Termux and droidVNC-NG can then be installed through F-Droid.

## Basic setup

The Casambi app will have to be installed and fully configured (including bluetooth and site features) and set up. Devices should be controllable with the app. 

It is advisable not to install unneeded apps from the SBC in order to have as few as possible sources of disturbances. 

The phone must be connected to your LAN or WLAN, be able to connect to the casambi site and bluetooth must be enabled.

## Battery Optimisation

Battery optimization for the Casambi app has to be disabled. The place to do this seems to move around depending on the Android version.

## Automated Start of the Casambi App

As I haven't figured out, how to automatically start the Casambi app directly from the Android system, this is being done with the Termux app. 

The Termux app provides you with a command line interface to the Android system, it can start Android apps from the command line and can start automatically on system boot.
It can also provide SSH access to the Android device.

With this, setup is made with the following steps:

* Install Termux - I installed mine from the F-Droid app store, because there seem to be problems with the version in the Google app store
* Install Termux:Boot - This starts Termux on boot and allows to run commands on boot (see https://wiki.termux.com/wiki/Termux:Boot)
* Activate the Termux wake-lock - This prevents Android from putting the app to sleep or stopping it. This is done with a small script in the `/data/data/com.termux/files/home/.termux/boot` folder
* Autostart the Casambi App - This is also done by putting a small script into the `/data/data/com.termux/files/home/bin` folder. 

Supplying the files to the Android device and getting them into the right places is the tricky bit. Editing with `vi` using the on-screen keyboard is not for everyone. As an alternative you can 
edit the files on your computer and use `scp` from the Termux commandline on the Android phone (also not easy with the on-screen keyboard). Another method is transfer the files using a different channel
(e.g. download through the browser, use Bluetooth file transfer). Of course the challenge ist then to find the downloaded file from the Termux command line. 

In the end it is probably easiest to first set up SSH on the Android phone (see SSH section below) and then set up the rest.

The script to acquire the wakelock in `/data/data/com.termux/files/home/.termux/boot` is:

```
#!/data/data/com.termux/files/usr/bin/sh
#
# Maybe sleep helps to get a valid date/time
sleep 10
#
LOG=~/log/casa.log
DAT=`/usr/bin/date +"%Y-%m-%d %T"`
EXE=`basename $0`

echo "${EXE} ${DAT} - Booting Casambi gateway server." >> ${LOG}

# set the wake-lock to keep termux from sleeping
/data/data/com.termux/files/usr/bin/termux-wake-lock

# start the ssh server on port 8022
/data/data/com.termux/files/usr/bin/sshd

# start the casmbi app in the foreground
/data/data/com.termux/files/home/bin/casa_cmd.sh --restart-app

# start the cron daemon
sv up crond

exit 0

# End

```

This should make sure that the Casambi App is started automatically and put into the foreground when the device boots. The used `casa_cmd.sh` is shown below.

## SSH for (Limited) Remote Management of the Casambi App

Sometimes the Casambi app fails after a while. Either something terminates it or it is pushed into the background. The Casambi driver will then receive a "peerChanged" message with the status offline.

Using a remote connection to the mobile phone it is possible to restart the Casambi app or put it back into the foreground. This can be achieved with a SSH connection to the phone.

Setup the SSH connection with the following steps:

* On your computer generate a new SSH key pair with `ssh-keygen` (no password)
* Store the key pair in the .ssh folder of the OpenHAB user and secure it properly. Name the private key something like `casambi_key` and the private key accordingly
* Transfer the public key to the mobile phone and store it under `.ssh/authorized_keys` (the tricky bit)
* You should then be able login to your phone with `ssh [your-phone] -p 8022 -i .ssh\casambi_key`.
Here `[your-phone]` is the IP-address or DNS-name of the Android gateway device. Port 8022 is used 
because Termux cannot access the standard ssh port. This should log you onto your phone without supplying a password
* Once you got here, you can set up the two files above from the terminal window on your computer
* This also enables you to manually restart the Casambi app with `am start casambi.ambi/.ui.Casa` from the Termux command line

## Commands to the SBC from the Casambi driver

With a small shell script installed on the SBC, limited control of the operation is possible. The following commands are supplied:

* `--restart-app` - this either starts the Casambi app or brings it into the foreground (which is necessary for proper operation)
* `--restart-bluetooth` - restarts the Android Bluetooth system on the SBC. This can help when connectivty to the luminaires is lost
* `--reboot` - reboots the Android system on the SBC. Maybe helpful if nothing else works
* `--mark` - puts a mark into the logfile on the SBC. This can be used to find out when a reboot has happened (the is no valid timestamp avaliable for the reboot message)

If you enable "Remote Command" in the Casambi bridge settings, the driver will try to restart the Casambi app automatically when it goes offline, it will also try to restart the bluetooth system if it detects that there is no bluetooth connectivity.
If you enable the "Remote Command" option you have to supply the command that will be executed with the above mentioned options.

For my system it is: 

```
ssh <dns-name> -p 8022 -i ~/.ssh/casambi_rsa_2 ~/bin/casa_cmd.sh
```

This assumes that the following script is installed as `~/bin/casa_cmd.sh.

```
#!/data/data/com.termux/files/usr/bin/sh
#
LOG=~/log/casa.log
DAT=`/usr/bin/date +"%Y-%m-%d %T"`
EXE=`basename $0`

if [ "$1" = "--restart-app" ] ; then
    echo "${EXE} ${DAT} - restarting Casambi app." >> ${LOG}
    /data/data/com.termux/files/usr/bin/am start casambi.ambi/.ui.Casa >> ${LOG}
elif [ "$1" = "--restart-bluetooth" ] ; then
    echo "${EXE} ${DAT} - restarting bluetooth connection" >> ${LOG}
    /data/data/com.termux/files/usr/bin/adb shell settings put global bluetooth_disabled_profiles 0 >> ${LOG}
    /data/data/com.termux/files/usr/bin/adb shell settings put global bluetooth_disabled_profiles 1 >> ${LOG}
elif [ "$1" = "--reboot" ] ; then
    echo "${EXE} ${DAT} - rebooting" >>${LOG}
    /usr/bin/sync
    /data/data/com.termux/files/usr/bin/adb reboot
elif [ "$1" = "--mark" ] ; then
    echo "${EXE} ${DAT} - MARK" >>${LOG}
else
    echo "${EXE}: ERROR - missing or illegal command line option"
    echo "    Possible options are --restart-app, --restart-bluetooth and --reboot"
    exit 1
fi

exit 0

# End

```
## Logging on the SBC

The commands send through `casa_cmd.sh` are logged in `~/log/casa.log`. The logfile is managed with `logrotate`.

Example logrotate configuration
```
/data/data/com.termux/files/home/log/casa.log {
        rotate 7
        size 100k
        compress
        delaycompress
        missingok
        notifempty
}
```

In addion `cron` is enabled with the following crontab:

```
#
# khadas crontab for default user
#
# mn hr dy mo wd cmd
  55 23  *  *  * /data/data/com.termux/files/usr/bin/logrotate -s ~/log/logrotate.state ~/log/logrotate.conf
  0  *   *  *  * /data/data/com.termux/files/home/bin/casa_cmd.sh --mark
#
```

