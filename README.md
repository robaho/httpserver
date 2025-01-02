# httpserver

A zero-dependency implementation of the JDK com.sun.net.httpserver.HttpServer specification with a few significant enhancements.

It adds websocket support using modified source from nanohttpd.

It has basic server-side proxy support using [ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java).

ProxyHandler also supports tunneling proxies using CONNECT for https.

It supports Http2 [RFC 9113](https://www.rfc-editor.org/rfc/rfc9113.html)

All async functionality has been removed. All synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that easily integrates with Virtual Threads available in JDK 21 - simply set a virtual thread based ExecutorService.

Improved performance by more than **10x** over the JDK implementation, using http pipelining, optimized String parsing, etc.

Designed for embedding with only a 90kb jar and zero dependencies.

## background

The JDK httpserver implementation has no support for connection upgrades, so it is not possible to add websocket support.

Additionally, the code still has a lot of async - e.g. using SSLEngine to provide SSL support - which makes it more difficult to understand and enhance.

The streams based processing and thread per connection design simplifies the code substantially.

## testing

Nearly all of the tests were included from the JDK so this version should be highly compliant and reliable.

## using

Set the default HttpServer provider when starting the jvm:

<code>-Dcom.sun.net.httpserver.HttpServerProvider=robaho.net.httpserver.DefaultHttpServerProvider</code>

or instantiate the server directly using [this](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/DefaultHttpServerProvider.java#L33).

or the service loader will automatically find it when the jar is placed on the class path when using the standard HttpServer service provider.

## performance

** updated 11/22/2024: retested using JDK 23. The results for the JDK version dropped dramatically because I was able to resolve the source of the errors (incorrect network configuration) - and now the robaho version is more than 10x faster.

This version performs more than **10x** better than the JDK version when tested using the [Tech Empower Benchmarks](https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/httpserver) on an identical hardware/work setup with the same JDK 23 version.<sup>1</sup>

The frameworks were also tested using [go-wrk](https://github.com/tsliwowicz/go-wrk)<sup>2</sup>

<sup>1</sup>_The robaho version has been submitted to the Tech Empower benchmarks project for 3-party confirmation._<br>
<sup>2</sup>_`go-wrk` does not use http pipelining so, the large number of connections is the limiting factor._

Performance tests against the latest Jetty version were run. The `robaho httpserver` outperformed the Jetty http2 by 5x in both http1 and http2.

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
finished in 5.20s, 192421.22 req/s, 6.79MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000000 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 35.29MB (37003264) total, 7.63MB (8002384) headers (space savings 90.12%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:      142us     43.73ms      7.20ms      3.96ms    70.90%
time for connect:      176us      7.70ms      3.96ms      2.34ms    62.50%
time to 1st byte:    10.48ms     20.63ms     13.65ms      2.93ms    75.00%
req/s           :   12026.57    12200.62    12070.81       46.69    93.75%
```

Jetty 11 http1
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: http/1.1
finished in 3.86s, 258839.63 req/s, 33.82MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000000 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 130.65MB (137000000) total, 86.78MB (91000000) headers (space savings 0.00%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:     1.52ms    194.72ms     60.42ms     21.40ms    74.16%
time for connect:      172us      4.07ms      2.13ms      1.21ms    62.50%
time to 1st byte:     4.70ms     10.80ms      6.66ms      1.96ms    87.50%
req/s           :   16178.98    16976.90    16456.91      175.54    81.25%
```

robaho http2
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: h2c
finished in 1.08s, 927732.43 req/s, 38.93MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1000000 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 41.96MB (44000480) total, 5.72MB (6000000) headers (space savings 76.92%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:      226us     84.23ms     15.51ms      9.23ms    77.11%
time for connect:      521us      5.57ms      3.13ms      1.57ms    62.50%
time to 1st byte:     6.46ms     17.15ms     10.12ms      3.82ms    81.25%
req/s           :   58012.46    66943.10    60509.05     2819.65    87.50%
```

robaho http1
```
starting benchmark...
spawning thread #0: 16 total client(s). 1000000 total requests
Application protocol: http/1.1
finished in 784.26ms, 1275080.84 req/s, 105.79MB/s
requests: 1000000 total, 1000000 started, 1000000 done, 1000000 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 1001125 2xx, 0 3xx, 0 4xx, 0 5xx
traffic: 82.97MB (87000000) total, 46.73MB (49000000) headers (space savings 0.00%), 10.49MB (11000000) data
                     min         max         mean         sd        +/- sd
time for request:      763us     26.87ms     12.34ms      2.71ms    74.28%
time for connect:      104us      4.32ms      2.23ms      1.30ms    62.50%
time to 1st byte:     4.91ms     16.21ms     10.36ms      4.49ms    43.75%
req/s           :   79744.46    81149.46    80228.21      355.56    75.00%
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

The counts can be reset using `/__stats?reset`. The `requests/sec` is calculated from the previous statistics request. 

## maven

```xml
<dependency>
  <groupId>io.github.robaho</groupId>
  <artifactId>httpserver</artifactId>
  <version>1.0.14</version>
</dependency>
```
## enable Http2

Http2 support is enabled via Java system properties.

Use `-Drobaho.net.httpserver.http2OverSSL=true` to enable Http2 only via SSL connections.

Use `-Drobaho.net.httpserver.http2OverNonSSL=true` to enable Http2 on Non-SSL connections (which requires prior knowledge). The Http2 upgrade mechanism was deprecated in RFC 9113 so it is not supported.

See the additional Http2 options in `ServerConfig.java`

The http2 implementation passes all specification tests in [h2spec](https://github.com/summerwind/h2spec)

## Http2 performance notes

Http2 performance has not been fully optimized - there is room for improvement.

The http2 version is about 20% slower than http1. I expect this to be the case with most http2 implementations due to the complexity.


