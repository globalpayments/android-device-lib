
# Global Payments Android Device Library 
=======================

This Android library lets you connect to a C2X, C3X, or Moby 5500 device and process credit card payments. Included is an example application which shows the basics of using the library.

Maven
-------------
```java
implementation 'com.globalpayments:android-device-lib:2.1.2'
```

Example App
-------------
To use the example app, you'll need to find these variables inside MainActivity:
```java
public static String PUBLIC_KEY;
public static String USERNAME;
public static String PASSWORD;
public static String SITE_ID;
public static String DEVICE_ID;
public static String LICENSE_ID;
public static String MERCHANT_ID;
public static String DEVELOPER_ID;
public static String TRANSACTION_KEY;
```
Update the placeholder values with your own credentials and then run the application. When using Portico, you need the USERNAME, PASSWORD, SITE_ID, DEVICE_ID, and LICENSE_ID. For TransIT, you need to set the values for USERNAME, PASSWORD, DEVICE_ID, MERCHANT_ID, and DEVELOPER_ID. Alternatively if you have the TRANSACTION_KEY, you need to set it along with the DEVICE_ID, MERCHANT_ID, and DEVELOPER_ID. 
The example app will allow you to scan and connect to your C2X or Moby 5500 device, run manual entry transactions, and run card read transactions (using connected C2X or Moby 5500).

SDK Classes
-------------

- **C2XDevice** - This is the class used for scanning and connecting to C2X or C3X devices. It also used for transactions once connected.
- **MobyDevice** - This is the class used for scanning and connecting to Moby 5500 devices. It is also used for transactions once connected.
- **ConnectionConfig** - Used for initializing the C2XDevice or MobyDevice object with your credentials.
- **DeviceListener** - Listener interface for scanning/connecting callbacks.
- **TransactionListener** - Listener interface for transaction callbacks.
- **Card** - Used for manual card entry (C2X/Moby connection is not required for manual card entry).
- **CreditAuthBuilder**, **CreditCaptureBuilder**, **CreditSaleBuilder**, **CreditAdjustBuilder**, **CreditReturnBuilder**, **CreditVoidBuilder** - Builder classes for constructing different types of transactions.
