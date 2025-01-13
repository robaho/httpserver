
# httpserver

Zero-dependency implementation of the JDK [`com.sun.net.httpserver.HttpServer` specification](https://docs.oracle.com/en/java/javase/21/docs/api/jdk.httpserver/com/sun/net/httpserver/package-summary.html) with a few significant enhancements.

- WebSocket support using modified source code from nanohttpd.
- Server-side proxy support using [ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java). (Tunneling proxies are also supported using CONNECT for https.)
- HTTP/2 [RFC 9113](https://www.rfc-editor.org/rfc/rfc9113.html) support
- Performance enhancements such as proper HTTP pipelining, optimized String parsing, etc.

All async functionality has been removed. All synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that easily integrates with Virtual Threads available in JDK 21 - simply set a virtual thread based ExecutorService.

Improves performance by more than **10x** over the JDK implementation.

Designed for embedding with only a 200kb jar and zero dependencies.

## background

The built-in JDK httpserver implementation has no support for connection upgrades, so it is not possible to add websocket support.

Additionally, the code still has a lot of async - e.g. using SSLEngine to provide SSL support - which makes it more difficult to understand and enhance.

The thread-per-connection synchronous design simplifies the code substantially.

## testing/compliance

Nearly all tests from the JDK are included, so this version should be highly compliant and reliable.

Additional proxy and websockets tests are included.

The http2 implementation passes all specification tests in [h2spec](https://github.com/summerwind/h2spec)

## maven 
[![Maven Central](https://img.shields.io/maven-central/v/io.github.robaho/httpserver.svg?label=Maven%20Central)](https://mvnrepository.com/artifact/io.github.robaho/httpserver)

```xml
<dependency>
  <groupId>io.github.robaho</groupId>
  <artifactId>httpserver</artifactId>
  <version>${the.above.version}</version>
</dependency>
```

## using

The JDK will automatically use `robaho.net.httpserver.DefaultHttpServerProvider` instead of the JDK implementation when the jar is placed on the class/module path. If there are multiple `HttpServer` providers on the classpath, the `com.sun.net.httpserver.HttpServerProvider` system property can be used to specify the correct one:

Eg. <code>-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider</code>

Alternatively, you can instantiate the server directly using [this](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/DefaultHttpServerProvider.java#L33).

### Example Usage
```java
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

public class Test {

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(8000), 0);
    server.createContext("/", new MyHandler());
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor()); // sets virtual thread executor
    server.start();
    }

  static class MyHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
      String response = "This is the response";
      byte[] bytes = response.getBytes();

      // -1 means no content, 0 means unknown content length
      var contentLength = bytes.length == 0 ? -1 : bytes.length;

      try (OutputStream os = exchange.getResponseBody()) {
        exchange.sendResponseHeaders(200, contentLength);
        os.write(bytes);
      }
    }
  }
}

```
There is a [simple file server](https://github.com/robaho/httpserver/blob/72775986b38120b30dc4bc0438d21136ff8ec192/src/test/extras/SimpleFileServer.java#L48) that can be used to for basic testing. It has download, echo, and "hello" capabilities. Use

```
gradle runSimpleFileServer
```

## logging

All logging is performed using the [Java System Logger](https://docs.oracle.com/en/java/javase/19/docs/api/java.base/java/lang/System.Logger.html)

## enable Http2

Http2 support is enabled via Java system properties.

Use `-Drobaho.net.httpserver.http2OverSSL=true` to enable Http2 only via SSL connections.

Use `-Drobaho.net.httpserver.http2OverNonSSL=true` to enable Http2 on Non-SSL connections (which requires prior knowledge). The Http2 upgrade mechanism was deprecated in RFC 9113 so it is not supported.

See the additional Http2 options in `ServerConfig.java`

## performance

This version performs more than **10x** faster than the JDK version when tested using the [Tech Empower Benchmarks](https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/httpserver) on an identical hardware/work setup with the same JDK 23 version.<sup>1</sup>

The frameworks were also tested using [go-wrk](https://github.com/tsliwowicz/go-wrk)<sup>2</sup>

<sup>1</sup>_The robaho version has been [submitted](https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/httpserver-robaho) to the Tech Empower benchmarks project for 3-party confirmation._<br>
<sup>2</sup>_`go-wrk` does not use http pipelining so, the large number of connections is the limiting factor._

Performance tests against the latest Jetty version were run. The `robaho httpserver` outperformed the Jetty http2 by 3x, and http1 by 5x.

The Javalin/Jetty project is available [here](https://github.com/robaho/javalin-http2-example)

<details>
    <summary>vs JDK performance details</summary>

**robaho tech empower**
```
robertengels@macmini go-wrk % wrk -H 'Host: imac' -H 'Accept: text/plain,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7' -H 'Connection: keep-alive' --latency -d 60 -c 64 --timeout 8 -t 2 http://imac:8080/plaintext -s ~/pipeline.lua -- 16
Running 1m test @ http://imac:8080/plaintext
  2 threads and 64 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     1.20ms    9.22ms 404.09ms   85.37%
    Req/Sec   348.78k    33.28k  415.03k    71.46%
  Latency Distribution
     50%    0.98ms
     75%    1.43ms
     90%    0.00us
     99%    0.00us
  41709198 requests in 1.00m, 5.52GB read
Requests/sec: 693983.49
Transfer/sec:     93.98MB
```

**jdk 23 tech empower**
```
robertengels@macmini go-wrk % wrk -H 'Host: imac' -H 'Accept: text/plain,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7' -H 'Connection: keep-alive' --latency -d 60 -c 64 --timeout 8 -t 2 http://imac:8080/plaintext -s ~/pipeline.lua -- 16
Running 1m test @ http://imac:8080/plaintext
  2 threads and 64 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     2.91ms   12.01ms 405.70ms   63.71%
    Req/Sec   114.30k    18.07k  146.91k    87.10%
  Latency Distribution
     50%    4.06ms
     75%    0.00us
     90%    0.00us
     99%    0.00us
  13669748 requests in 1.00m, 1.72GB read
Requests/sec: 227446.87
Transfer/sec:     29.28MB

```

**robaho go-wrk**
```
robertengels@macmini go-wrk % ./go-wrk -c=1024 -d=30 -T=100000 http://imac:8080/plaintext
Running 30s test @ http://imac:8080/plaintext
  1024 goroutine(s) running concurrently
3252278 requests in 30.118280233s, 387.70MB read
Requests/sec:		107983.52
Transfer/sec:		12.87MB
Overall Requests/sec:	105891.53
Overall Transfer/sec:	12.62MB
Fastest Request:	83µs
Avg Req Time:		9.482ms
Slowest Request:	1.415359s
Number of Errors:	0
10%:			286µs
50%:			1.018ms
75%:			1.272ms
99%:			1.436ms
99.9%:			1.441ms
99.9999%:		1.442ms
99.99999%:		1.442ms
stddev:			35.998ms
```

**jdk 23 go-wrk**
```
robertengels@macmini go-wrk % ./go-wrk -c=1024 -d=30 -T=100000 http://imac:8080/plaintext
Running 30s test @ http://imac:8080/plaintext
  1024 goroutine(s) running concurrently
264198 requests in 30.047154195s, 29.73MB read
Requests/sec:		8792.78
Transfer/sec:		1013.23KB
Overall Requests/sec:	8595.99
Overall Transfer/sec:	990.55KB
Fastest Request:	408µs
Avg Req Time:		116.459ms
Slowest Request:	1.930495s
Number of Errors:	0
10%:			1.166ms
50%:			1.595ms
75%:			1.725ms
99%:			1.827ms
99.9%:			1.83ms
99.9999%:		1.83ms
99.99999%:		1.83ms
stddev:			174.373ms

```
</details>
<details>
    <summary>vs Jetty performance details</summary>

The server is an iMac 4ghz quad-core i7 running OSX 13.7.2. JVM used is JDK 23.0.1. The `h2load` client was connected via a 20Gbs lightening network from an M1 Mac Mini.

Using `h2load -n 1000000 -m 1000 -c 16 [--h1] http://imac:<port>` 

Jetty jetty-11.0.24
Javalin version 6.4.0

Jetty 11 http2
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: h2c
finished in 3.47s, 288284.80 req/s, 10.17MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000000 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 35.29MB (37002689) total, 7.63MB (8001809) headers (space savings 90.12%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:       94us    381.85ms      6.42ms     21.51ms    96.90%
time for connect:      389us      5.88ms      3.15ms      1.75ms    62.50%
time to 1st byte:     6.61ms     11.74ms      7.85ms      1.24ms    87.50%
req/s           :   18020.94    23235.01    19829.09     1588.94    75.00%

```

Jetty 11 http1
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: http/1.1
finished in 3.63s, 275680.69 req/s, 36.02MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000021 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 130.65MB (137000000) total, 86.78MB (91000000) headers (space savings 0.00%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:     1.59ms    336.00ms     53.17ms     51.56ms    85.36%
time for connect:      422us      2.57ms      1.54ms       632us    62.50%
time to 1st byte:     2.98ms    314.97ms     26.14ms     77.12ms    93.75%
req/s           :   17232.15    21230.14    18780.35     1130.32    68.75

```

robaho http2
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: h2c
finished in 1.03s, 966710.36 req/s, 40.57MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000000 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 41.96MB (44000480) total, 5.72MB (6000000) headers (space savings 76.92%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:      457us     71.41ms     14.71ms      8.63ms    73.09%
time for connect:      336us      5.77ms      3.13ms      1.73ms    62.50%
time to 1st byte:     6.59ms     15.30ms     10.40ms      3.32ms    50.00%
req/s           :   60461.71    66800.04    62509.79     1544.65    75.00%
```

robaho http1
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: http/1.1
finished in 776.64ms, 1287592.88 req/s, 106.83MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000123 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 82.97MB (87000000) total, 46.73MB (49000000) headers (space savings 0.00%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:      376us    380.30ms      9.12ms     32.43ms    99.20%
time for connect:      240us      2.51ms      1.50ms       720us    62.50%
time to 1st byte:     3.04ms     18.85ms      8.93ms      5.77ms    68.75%
req/s           :   80530.13   167605.46   122588.82    42385.59    87.50%
```

</details>


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

The counts and rates for non "Total" statistics are reset with each pull of the statistics.

## performance notes

Http2 performance has not been fully optimized. The http2 version is about 20-30% slower than http1. I expect this to be the case with most http2 implementations due to the complexity.
http2 outperforms http1 when sending multiple simultaneous requests from the client with payloads, as most servers and clients do not implement http pipelining when payloads are involved.

TODO: sending hpack headers does not use huffman encoding or dynamic table management. see the following paper https://www.mew.org/~kazu/doc/paper/hpack-2017.pdf for optimizing the implementation further.

The most expensive operations involve converting strings to URI instances. Unfortunately, since using URI is part of the [HttpExchange API](https://docs.oracle.com/en/java/javase/11/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpExchange.html#getRequestURI())  little can be done in this regard. 
It could be instantiated lazily, but almost all handlers need access to the URI components (e.g. path, query, etc.)

The standard JDK Headers implementation normalizes all headers to be first character capitalized and the rest lowercase. To ensure optimum performance, client code should use the same format to avoid the normalization cost, i.e. 

```java
Use

var value = request.getFirst("Content-length");

instead of

var value = request.getFirst("content-length"); 
var value = request.getFirst("CONTENT-LENGTH");
```


