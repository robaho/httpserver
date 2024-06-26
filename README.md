# httpserver

An implementation of the JDK com.sun.net.httpserver.HttpServer specification with a few significant enhancements.

It adds websocket support using modified source from nanohttpd.

It has basic server-side proxy support using [ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java).

ProxyHandler also supports tunneling proxies using CONNECT for https.

All async functionality has been removed. Most synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that easily integrates with Virtual Threads available in JDK 21 - simply set a virtual thread based ExecutorService.

Designed for embedding only a 90kb jar.

## background

The JDK httpserver has no support for connection upgrades, so it is not possible to add websocket support.

Additionally, the code still has a lot of async - e.g. using SSLEngine to provide SSL support - which makes it more difficult to understand and enhance.

The streams based processing offered by a thread per connection design simplifies the code substantially.

## testing

Nearly all of the tests were migrated from the JDK so the current version should be highly compliant.

## using

Set the default HttpServer provider when starting the jvm:

<code>-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider</code>

or instantiate the server directly using [this](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/DefaultHttpServerProvider.java#L33).

or the service loader will automatically find it when the jar is placed on the class path when using the standard HttpServer service provider.

## server statistics

The server tracks some basic statistics. To enable the access endpoint `/__stats`, set the system property `robaho.net.httpserver.EnableStatistics=true`.

Sample usage:

```shell
$ curl http://localhost:8080/__stats
Connections: 4264
Active Connections: 2049
Requests: 2669256
Requests/sec: 73719
Handler Exceptions: 0
Socket Exceptions: 0
Mac Connections Exceeded: 0
Idle Closes: 0
Reply Errors: 0
```

The counts can be reset using `/__stats?reset`. The `requests/sec` is calculated from the previous statistics request. 

## maven

```xml
<dependency>
  <groupId>io.github.robaho</groupId>
  <artifactId>httpserver</artifactId>
  <version>1.0.5</version>
</dependency>
```
## future work

There is no http2 support.
