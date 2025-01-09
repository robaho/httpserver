package robaho.net.httpserver.http2;

import java.util.concurrent.atomic.AtomicLong;

public class HTTP2Stats {
    public final AtomicLong activeStreams = new AtomicLong();
    public final AtomicLong bytesSent = new AtomicLong();
    public final AtomicLong framesSent = new AtomicLong();
    public final AtomicLong flushes = new AtomicLong();
    public final AtomicLong sslConnections = new AtomicLong();
    public final AtomicLong nonsslConnections = new AtomicLong();
    public final AtomicLong totalStreams = new AtomicLong();
    public final AtomicLong pauses = new AtomicLong();
    public final AtomicLong pingsSent = new AtomicLong();

    private volatile long lastStatsTime = System.currentTimeMillis();

    public String stats() {
        long now = System.currentTimeMillis();
        double secs = (now-lastStatsTime)/1000.0;
        lastStatsTime = now;

        long _bytes = bytesSent.getAndSet(0);
        long _frames = framesSent.getAndSet(0);

        return
                "Http2 SSL Connections Since: "+sslConnections.getAndSet(0)+"\n" +
                "Http2 Non-SSL Connections Since: "+nonsslConnections.getAndSet(0)+"\n" +
                "Http2 Streams Since: "+totalStreams.getAndSet(0)+"\n" +
                "Http2 Active Streams: "+activeStreams.get()+"\n" +
                "Http2 Frames Sent/sec: "+(long)(_frames/(secs))+"\n"+
                "Http2 Bytes Sent/sec: "+(long)(_bytes/(secs))+"\n"+
                "Http2 Avg Frame Size: "+(long)(_frames==0 ? 0 : _bytes/_frames)+"\n"+
                "Http2 Flushes/sec: "+(long)(flushes.getAndSet(0)/(secs))+"\n"+
                "Http2 Pauses/sec: "+(long)(pauses.getAndSet(0)/(secs))+"\n"+
                "Http2 Pings Sent Since: "+pingsSent.getAndSet(0)+"\n";

    }
}
