# httpserver

A simple http server designed for embedding based on the JDK sun.net.httpserver.

It adds websocket support using modified source from nanohttpd.

All async functionality has been removed. Most synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that supports adoption of Virtual Threads available in JDK 21.

## using

Set the default HttpServer provider when starting the jvm:

<code>-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider</code>

## future work

There is no http2 support.