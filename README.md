# httpserver

A zero-dependency implementation of the JDK com.sun.net.httpserver.HttpServer specification with a few significant enhancements.

It adds websocket support using modified source from nanohttpd.

It has basic server-side proxy support using [ProxyHandler](https://github.com/robaho/httpserver/blob/main/src/main/java/robaho/net/httpserver/extras/ProxyHandler.java).

ProxyHandler also supports tunneling proxies using CONNECT for https.

All async functionality has been removed. All synchronized blocks were removed in favor of other Java concurrency concepts.

The end result is an implementation that easily integrates with Virtual Threads available in JDK 21 - simply set a virtual thread based ExecutorService.

Improved performance by more than **3x** over the JDK implementation, using http pipelining, optimized String parsing, etc.

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

This version performs more than **3x** better than the JDK version when tested using the [Tech Empower Benchmarks](https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Java/httpserver) on an identical hardware/work setup with the same JDK 21 version.<sup>1</sup> Early results with JDK-24 and the improved virtual threads scheduling shows even greater performance improvement.

The frameworks were also tested using [go-wrk](https://github.com/robaho/go-wrk)<sup>2</sup>

<sup>1</sup>_Currently working on submitting the robaho version to the Tech Empower benchmarks project for 3-party confirmation._<br>
<sup>2</sup>_`go-wrk` does not use http pipelining so, the large number of connections is the limiting factor. `go-wrk` was tested using the Tech Empower server process._


**robaho tech empower**
```
robertengels@macmini go-wrk % wrk -H 'Host: imac' -H 'Accept: text/plain,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7' -H 'Connection: keep-alive' --latency -d 60 -c 64 --timeout 8 -t 2 http://imac:8080/plaintext -s ~/pipeline.lua -- 16
Running 1m test @ http://imac:8080/plaintext
  2 threads and 64 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency   658.83us  665.90us  19.01ms   16.14%
    Req/Sec   294.57k    28.40k  329.21k    85.67%
  Latency Distribution
     50%    0.00us
     75%    0.00us
     90%    0.00us
     99%    0.00us
  35179762 requests in 1.00m, 4.65GB read
Requests/sec: 586136.93
Transfer/sec:     79.38MB
```

**jdk 21 tech empower**
```
robertengels@macmini go-wrk % wrk -H 'Host: imac' -H 'Accept: text/plain,text/html;q=0.9,application/xhtml+xml;q=0.9,application/xml;q=0.8,*/*;q=0.7' -H 'Connection: keep-alive' --latency -d 60 -c 64 --timeout 8 -t 2 http://imac:8080/plaintext -s ~/pipeline.lua -- 16
Running 1m test @ http://imac:8080/plaintext
  2 threads and 64 connections
  Thread Stats   Avg      Stdev     Max   +/- Stdev
    Latency     3.10ms    6.16ms 407.66ms   65.94%
    Req/Sec    86.84k    10.05k  119.40k    71.00%
  Latency Distribution
     50%    5.17ms
     75%    0.00us
     90%    0.00us
     99%    0.00us
  10371476 requests in 1.00m, 1.30GB read
Requests/sec: 172781.10
Transfer/sec:     22.24MB

```

**robaho go-wrk**
```
robertengels@macmini go-wrk % ./go-wrk -c=1024 -d=30 -T=100000 http://imac:8080/plaintext
Running 30s test @ http://imac:8080/plaintext
  1024 goroutine(s) running concurrently
2263637 requests in 29.998871972s, 269.85MB read
Requests/sec:		75457.40
Transfer/sec:		9.00MB
Overall Requests/sec:	74447.26
Overall Transfer/sec:	8.87MB
Fastest Request:	527µs
Avg Req Time:		13.57ms
Slowest Request:	123.979ms
Number of Errors:	0
10%:			5.856ms
50%:			6.873ms
75%:			7.302ms
99%:			7.699ms
99.9%:			7.713ms
99.9999%:		7.715ms
99.99999%:		7.715ms
stddev:			3.532ms
```

**jdk 21 go-wrk**<sup>3</sup>
```
robertengels@macmini go-wrk % ./go-wrk -c=1024 -d=30 -T=100000 http://imac:8080/plaintext
Running 30s test @ http://imac:8080/plaintext
  1024 goroutine(s) running concurrently
1574549 requests in 13.563570087s, 177.19MB read
Requests/sec:		116086.62
Transfer/sec:		13.06MB
Overall Requests/sec:	33459.36
Overall Transfer/sec:	3.77MB
Fastest Request:	842µs
Avg Req Time:		8.82ms
Slowest Request:	19.511295s
Number of Errors:	739
Error Counts:		operation timed out=739
10%:			1.597ms
50%:			2.254ms
75%:			2.48ms
99%:			2.626ms
99.9%:			2.631ms
99.9999%:		2.631ms
99.99999%:		2.631ms
stddev:			187.376ms
```
<sup>3</sup>_Note the failures/timeouts when using the JDK version (which also skews the statistics a bit)._

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
  <version>1.0.6</version>
</dependency>
```
## future work

There is no http2 support.
