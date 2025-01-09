package robaho.net.httpserver;

import java.util.concurrent.atomic.AtomicLong;

class ServerStats {
    final AtomicLong connectionCount = new AtomicLong();
    final AtomicLong requestCount = new AtomicLong();
    final AtomicLong handleExceptionCount = new AtomicLong();
    final AtomicLong socketExceptionCount = new AtomicLong();
    final AtomicLong idleCloseCount = new AtomicLong();
    final AtomicLong replyErrorCount = new AtomicLong();
    final AtomicLong maxConnectionsExceededCount = new AtomicLong();

    private volatile long lastStatsTime = System.currentTimeMillis();

    public String stats() {
        long now = System.currentTimeMillis();
        double secs = (now-lastStatsTime)/1000.0;
        lastStatsTime = now;

        long _requests = requestCount.getAndSet(0);

        return
                "Connections Since: "+connectionCount.getAndSet(0)+"\n" +
                "Requests Since: "+_requests+"\n" +
                "Requests/sec: "+(long)(_requests/secs)+"\n"+
                "Total Handler Exceptions: "+handleExceptionCount.get()+"\n"+
                "Total Socket Exceptions: "+socketExceptionCount.get()+"\n"+
                "Total Max Connections Exceeded: "+maxConnectionsExceededCount.get()+"\n"+
                "Total Idle Closes: "+idleCloseCount.get()+"\n"+
                "Total Reply Errors: "+replyErrorCount.get()+"\n";
    }
}