# httpserver

A zero-dependency implementation of the JDK com.sun.net.httpserver.HttpServer specification with a few significant enhancements.

It adds websocket support using modified source from nanohttpd.

It has basic server-side proxy support using [ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java).

ProxyHandler also supports tunneling proxies using CONNECT for https.

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
  <version>1.0.11</version>
</dependency>
```
## future work

There is no http2 support.
