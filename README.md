# httpserver

A simple http server designed for embedding based on the JDK sun.net.httpserver - only a 90kb jar.

It adds websocket support using modified source from nanohttpd.

All async functionality has been removed. Most synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that easily integrates with Virtual Threads available in JDK 21 - simply set a virtual thread based ExecutorService.

## testing

Nearly all of the tests were migrated from the JDK so the current version should be highly compliant.

## using

Set the default HttpServer provider when starting the jvm:

<code>-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider</code>

or instantiate the server directly using [this](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/DefaultHttpServerProvider.java#L33).

## future work

There is no http2 support.
