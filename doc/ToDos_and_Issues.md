# Casambi Simple Driver To Do's and Issues

## To Do's

* General Cleanup
  * Javadoc for all classes and methods  - should be ok (230129)
  * Add tests
  * Code checks
      * Thread safety
  * Restructure Casambi message classes (e.g. super- and subclasses)
  * More Attributes for things and channels
  * is childHandlerDispose() needed?
  * Cleanup parameters for bridge and things
      * Remove unneeded parameters 
      * Make select parameters read-only
  * More robust error handling - should be mostly ok (230129)

## Functions

  * Add onOff channels that remember the last setting
  * Don't generate a thing for each scene/group, just add channels to a single scene/group thing
  * Support more than one network (network/wire as a thing? or one bridge per network?)
  * Check that values are read back correctly. E.g. luminaire dim level after a scene has been activated
  * Auto-generate items from channels?
  * Primary channel for equipment groups
  * Really remove things on discovery?
  
## Issues

* Channel definitions
  * Define the needed channels directly in the code instead of defining all possible channels in things.xml and deleting unneeded channels
  * Is it necessary to setup channels with every 'initialize()' or is it sufficient do this on definition only
* Correct handling of pong failures (restart websocket?) - should be ok (230129)
* Bundle shutdown and restart without errors (e.g. no updating of disposed channels) - should be ok (230129)
* 'Online Status' channel doesn't work
* Discovery
    * Make sure discovery works after restart (seems to be ok)
    * Add scenes and groups to oldThings before discovery
* Restart Bluetooth on the mobile
* Deleting a bridge leaves the attached things in limbo

## Production preparation

* Internationalization (e.g. English and German)
* Use OpenHAB supplied factories for HttpClient and WebSocket (See Coding Guidelines) - should be ok (230129)
* Add binding to the OpenHAB source tree
* Do 'mvn clean install' and 'mvn spotless:apply' - should be ok (230129)
