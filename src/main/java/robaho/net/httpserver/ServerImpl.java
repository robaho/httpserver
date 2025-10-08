/*
 * Copyright (c) 2005, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package robaho.net.httpserver;

import static java.nio.charset.StandardCharsets.ISO_8859_1;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.LogRecord;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;

import robaho.net.httpserver.http2.HTTP2Connection;
import robaho.net.httpserver.http2.HTTP2ErrorCode;
import robaho.net.httpserver.http2.HTTP2Exception;
import robaho.net.httpserver.http2.HTTP2Stats;
import robaho.net.httpserver.http2.HTTP2Stream;

/**
 * Provides implementation for both HTTP and HTTPS
 */
class ServerImpl {

    private final String protocol;
    private final boolean https;
    private Executor executor;
    private HttpsConfigurator httpsConfig;
    private final ContextList contexts;
    private final ServerSocket socket;

    private final Set<HttpConnection> allConnections = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean finished = false;
    private boolean bound = false;
    private boolean started = false;
    private final HttpServer wrapper;

    // schedule for the timer task that's responsible for idle connection management
    static final long IDLE_TIMER_TASK_SCHEDULE = ServerConfig.getIdleTimerScheduleMillis();
    static final int MAX_CONNECTIONS = ServerConfig.getMaxConnections();
    static final int MAX_IDLE_CONNECTIONS = ServerConfig.getMaxIdleConnections();
    // schedule for the timer task that's responsible for request/response timeout
    // management
    static final long MAX_REQ_TIME = getTimeMillis(ServerConfig.getMaxReqTime());
    static final long MAX_RSP_TIME = getTimeMillis(ServerConfig.getMaxRspTime());
    // the maximum idle duration for a connection which is currently idle but has
    // served
    // some request in the past
    static final long IDLE_INTERVAL = ServerConfig.getIdleIntervalMillis();
    // the maximum idle duration for a newly accepted connection which hasn't yet
    // received
    // the first byte of data on that connection
    static final long NEWLY_ACCEPTED_CONN_IDLE_INTERVAL;

    static {
        // the idle duration of a newly accepted connection is considered to be the
        // least of the
        // configured idle interval and the configured max request time (if any).
        NEWLY_ACCEPTED_CONN_IDLE_INTERVAL = MAX_REQ_TIME > 0
                ? Math.min(IDLE_INTERVAL, MAX_REQ_TIME)
                : IDLE_INTERVAL;
    }

    private Timer timer;
    private Logger logger;
    private Thread dispatcherThread;

    // statistics
    private final ServerStats stats = new ServerStats();
    private final HTTP2Stats http2Stats = new HTTP2Stats();

    ServerImpl(HttpServer wrapper, String protocol, InetSocketAddress addr, int backlog) throws IOException {

        this.protocol = protocol;
        this.wrapper = wrapper;

        socket = new ServerSocket();

        this.logger = System.getLogger("robaho.net.httpserver."+System.identityHashCode(this));
        java.util.logging.Logger.getLogger(this.logger.getName()).setFilter((LogRecord record) -> {
            record.setMessage("["+protocol+":"+socket.getLocalPort()+"] "+record.getMessage());
            return true;
        });

        https = protocol.equalsIgnoreCase("https");
        contexts = new ContextList();
        if (addr != null) {
            socket.bind(addr, backlog);
            bound = true;
            logger.log(Level.INFO,"server bound to "+socket.getLocalSocketAddress() + " with backlog "+backlog);
        }
        dispatcher = new Dispatcher();
        timer = new Timer("connection-cleaner", true);
        timer.schedule(new ConnectionCleanerTask(), IDLE_TIMER_TASK_SCHEDULE, IDLE_TIMER_TASK_SCHEDULE);
        timer.schedule(ActivityTimer.createTask(),750,750);
        timer.schedule(Http2Exchange.createTask(),1000,1000);
        logger.log(Level.DEBUG, "HttpServer created " + protocol + " " + addr);
        if(Boolean.getBoolean("robaho.net.httpserver.EnableStats")) {
            createContext("/__stats",new StatsHandler());
        }
        if(Boolean.getBoolean("robaho.net.httpserver.EnableDebug")) {
            createContext("/__debug",new DebugHandler());
        }
    }

    private class StatsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            var output = 
                (
                "Active Connections: "+allConnections.size()+"\n" +
                stats.stats()+
                http2Stats.stats()
                ).getBytes();

            exchange.sendResponseHeaders(200,output.length);
            exchange.getResponseBody().write(output);
            exchange.getResponseBody().close();
        }
    }

    /** log state to assist debugging */
    private class DebugHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            logger.log(Level.INFO,"logging debug state, requestor "+exchange.getRemoteAddress());
            for(var connection : allConnections) {
                connection.debug();
            }
            Http2Exchange.debug();
            exchange.sendResponseHeaders(200,-1);
        }
    }


    public void bind(InetSocketAddress addr, int backlog) throws IOException {
        if (bound) {
            throw new BindException("HttpServer already bound");
        }
        if (addr == null) {
            throw new NullPointerException("null address");
        }
        socket.bind(addr, backlog);
        logger.log(Level.INFO,"server bound to "+socket.getLocalSocketAddress()+ " with backlog "+backlog);
        bound = true;
    }

    public void start() {
        if (!bound || started || finished) {
            throw new IllegalStateException("server in wrong state");
        }
        if (executor == null) {
            executor = new DefaultExecutor();
        }
        logger.log(Level.INFO, "using " + executor + " as executor");
        dispatcherThread = new Thread(null, dispatcher, "HTTP-Dispatcher", 0, false);
        started = true;
        dispatcherThread.start();
    }

    public void setExecutor(Executor executor) {
        if (started) {
            throw new IllegalStateException("server already started");
        }
        this.executor = executor;
    }

    private static class DefaultExecutor implements Executor {
        private final ExecutorService executor = Executors.newCachedThreadPool();

        @Override
        public void execute(Runnable task) {
            executor.execute(task);
        }
        public void shutdown() {
            executor.shutdown();
        }
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setHttpsConfigurator(HttpsConfigurator config) {
        if (config == null) {
            throw new NullPointerException("null HttpsConfigurator");
        }
        if (started) {
            throw new IllegalStateException("server already started");
        }
        this.httpsConfig = config;
    }

    public HttpsConfigurator getHttpsConfigurator() {
        return httpsConfig;
    }

    public final boolean isFinishing() {
        return finished;
    }

    public void stop(int delay) {
        if (delay < 0) {
            throw new IllegalArgumentException("negative delay parameter");
        }
        logger.log(Level.INFO, "server shutting down: " + protocol);
        finished = true;
        try {
            socket.close();
        } catch (IOException e) {
        }
        if (executor instanceof DefaultExecutor de) {
            // since we created it, shut it done when stopping because it is private
            de.shutdown();
        }
        long latest = System.currentTimeMillis() + delay * 1000;
        while (System.currentTimeMillis() < latest) {
            delay();
        }
        for (HttpConnection c : allConnections) {
            c.close();
        }
        allConnections.clear();
        timer.cancel();

        if (dispatcherThread != null && dispatcherThread != Thread.currentThread()) {
            try {
                dispatcherThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.log(Level.TRACE, "ServerImpl.stop: ", e);
            }
        }
    }

    Dispatcher dispatcher;

    public HttpContextImpl createContext(String path, HttpHandler handler) {
        if (handler == null || path == null) {
            throw new NullPointerException("null handler, or path parameter");
        }
        HttpContextImpl context = new HttpContextImpl(protocol, path, handler, this);
        contexts.add(context);
        logger.log(Level.DEBUG, "context created: " + path);
        return context;
    }

    public HttpContextImpl createContext(String path) {
        if (path == null) {
            throw new NullPointerException("null path parameter");
        }
        HttpContextImpl context = new HttpContextImpl(protocol, path, null, this);
        contexts.add(context);
        logger.log(Level.DEBUG, "context created: " + path);
        return context;
    }

    public void removeContext(String path) throws IllegalArgumentException {
        if (path == null) {
            throw new NullPointerException("null path parameter");
        }
        contexts.remove(protocol, path);
        logger.log(Level.DEBUG, "context removed: " + path);
    }

    public void removeContext(HttpContext context) throws IllegalArgumentException {
        if (!(context instanceof HttpContextImpl)) {
            throw new IllegalArgumentException("wrong HttpContext type");
        }
        contexts.remove((HttpContextImpl) context);
        logger.log(Level.DEBUG, "context removed: " + context.getPath());
    }

    @SuppressWarnings("removal")
    public InetSocketAddress getAddress() {
        return AccessController.doPrivileged(
                new PrivilegedAction<InetSocketAddress>() {
                    public InetSocketAddress run() {
                return (InetSocketAddress) socket.getLocalSocketAddress();
            }
        });
    }

    /**
     * The Dispatcher is responsible for accepting any connections and then
     * using those connections to process incoming requests.
     */
    class Dispatcher implements Runnable {
        public void run() {
            while (true) {
                try {
                    Socket s = socket.accept();
                    try {
                        executor.execute(() -> {
                        try {
                            acceptConnection(s);
                        } catch (IOException t) {
                            logger.log(Level.WARNING, "unable to accept connection", t);
                            try {
                                s.close();
                            } catch (IOException ex) {
                            }
                        }
                    });
                    } catch (RejectedExecutionException e) {
                        s.close();
                    }
                } catch (IOException e) {
                    if (!isFinishing()) {
                        logger.log(Level.ERROR, "socket accept failed", e);
                    }
                    return;
                }
            }
        }
        private void acceptConnection(Socket s) throws IOException {
            if(logger.isLoggable(Level.TRACE)) {
                logger.log(Level.TRACE, "accepted connection: " + s.toString());
            }
            stats.connectionCount.incrementAndGet();
            if (MAX_CONNECTIONS > 0 && allConnections.size() >= MAX_CONNECTIONS) {
                // we've hit max limit of current open connections, so we go
                // ahead and close this connection without processing it
                try {
                    stats.maxConnectionsExceededCount.incrementAndGet();
                    logger.log(Level.WARNING, "closing accepted connection due to too many connections");
                    s.close();
                } catch (IOException ignore) {
                }
                return;
            }

            if (ServerConfig.noDelay()) {
                s.setTcpNoDelay(true);
            }

            boolean http2 = false;

            if (https) {
                // for some reason, creating an SSLServerSocket and setting the default parameters would
                // not work, so upgrade to a SSLSocket after connection
                SSLSocketFactory ssf = httpsConfig.getSSLContext().getSocketFactory();
                SSLSocket sslSocket = (SSLSocket) ssf.createSocket(s, null, false);
                SSLConfigurator.configure(sslSocket,httpsConfig);

                sslSocket.setHandshakeApplicationProtocolSelector((_sslSocket, protocols) -> {
                    if (protocols.contains("h2") && ServerConfig.http2OverSSL()) {
                        return "h2";
                    } else {
                        return "http/1.1";
                    }
                });
                // the following forces the SSL handshake to complete in order to determine the negotiated protocol
                var session = sslSocket.getSession();
                if ("h2".equals(sslSocket.getApplicationProtocol())) {
                    logger.log(Level.DEBUG, () -> "http2 connection "+sslSocket.toString());
                    http2 = true;
                } else {
                    logger.log(Level.DEBUG, () -> "http/1.1 connection "+sslSocket.toString());
                }
                s = sslSocket;
            }

            HttpConnection c = new HttpConnection(s);
            try {
                allConnections.add(c);

                if (http2) {
                    Http2Exchange t = new Http2Exchange(protocol, c);
                    t.run();
                } else {
                    Exchange t = new Exchange(protocol, c);
                    t.run();
                }
            } catch (Throwable t) {
                logger.log(Level.WARNING, "Dispatcher Exception", t);
                stats.handleExceptionCount.incrementAndGet();
                closeConnection(c);
            }
        }
    }

    Logger getLogger() {
        return logger;
    }

    private void closeConnection(HttpConnection conn) {
        logger.log(Level.TRACE, () -> "closing connection: " + conn.toString());
        conn.close();
        allConnections.remove(conn);
    }

    /* used to link to 2 or more Filter.Chains together */
    class LinkHandler implements HttpHandler {

        Filter.Chain nextChain;

        LinkHandler(Filter.Chain nextChain) {
            this.nextChain = nextChain;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            nextChain.doFilter(exchange);
        }
    }

    class Http2Exchange implements Runnable,HTTP2Connection.StreamHandler {
        final HttpConnection connection;
        final HTTP2Connection http2;
        final String protocol;

        private static final Set<Http2Exchange> allHttp2Exchanges = Collections.newSetFromMap(new ConcurrentHashMap<>());
        static void debug() {
            for(var exchange : allHttp2Exchanges) {
                exchange.http2.debug();
            }
        }

        Http2Exchange(String protocol, HttpConnection conn) throws IOException {
            this.connection = conn;
            this.protocol = protocol;

            if(protocol.equals("https2")) {
                http2Stats.sslConnections.incrementAndGet();
            } else {
                http2Stats.nonsslConnections.incrementAndGet();
            }

            http2 = new HTTP2Connection(conn,http2Stats,connection.getInputStream(), connection.getOutputStream(), this);
        }

        static TimerTask createTask() {
            return new TimerTask() {
                @Override
                public void run() {
                    long now = System.currentTimeMillis();
                    for(var exchange : allHttp2Exchanges) {
                        if(exchange.connection.lastActivityTime + 1000 < now) {
                            try {
                                exchange.http2.sendPing();
                            } catch (IOException ex) {
                            }
                        }
                    }
                }
        };
    }

        @Override
        public void run() {
            allHttp2Exchanges.add(this);

            try {
                if(!http2.hasProperPreface()) {
                    http2.sendGoAway(HTTP2ErrorCode.PROTOCOL_ERROR);
                    logger.log(Level.WARNING, "ServerImpl HTTP2 preface failed");
                    return;
                }
                http2.sendMySettings();
                http2.handle();
            } catch (HTTP2Exception ex) {
                logger.log(Level.WARNING, "ServerImpl http2 protocol exception "+http2,ex);
            } catch (IOException ex) {
                stats.socketExceptionCount.incrementAndGet();
                logger.log(Level.DEBUG, "end of stream "+http2);
            } catch (Exception ex) {
                logger.log(Level.WARNING, "ServerImpl unexpected exception handling http2 connection "+http2, ex);
            } finally {
                try {
                    logger.log(Level.DEBUG, "closing HTTP2 connection and streams "+http2);
                    closeConnection(connection);
                    http2.close();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "error closing http2 connection "+http2, t);
                }
                allHttp2Exchanges.remove(this);
            }
        }

        @Override
        public Executor getExecutor() {
            return ServerImpl.this.executor;
        }

        @Override
        public void handleStream(HTTP2Stream stream,InputStream in, OutputStream out) throws IOException {
            connection.requestCount.incrementAndGet();
            stats.requestCount.incrementAndGet();

            http2Stats.totalStreams.incrementAndGet();

            var request = stream.getRequestHeaders();
            var response = stream.getResponseHeaders();

            String scheme = https ? "https" : "http";
            String authority = request.getFirst(":authority");
            String path = request.getFirst(":path");
            String query = request.getFirst(":query");

            logger.log(Level.TRACE, () -> "http2 stream started "+stream.toString());

            if (authority == null || path == null) {
                throw new IOException("Invalid HTTP/2 headers: missing :authority or :path");
            }

            request.set("Host",authority);

            URI uri;
            try {
                uri = new URI(scheme,authority,path,query,null);
            } catch (URISyntaxException ex) {
                throw new IOException("invalid uri",ex);
            }

            String method = request.getFirst(":method");
            if (method == null) {
                throw new IOException("Invalid HTTP/2 headers: missing :method");
            }

            // Validate Transfer-Encoding headers for HTTP/2
            List<String> transferEncoding = request.get("Transfer-encoding");
            if (transferEncoding != null && !transferEncoding.isEmpty()) {
                if (transferEncoding.size() > 1 || !transferEncoding.get(0).equalsIgnoreCase("chunked")) {
                    throw new IOException("Unsupported Transfer-Encoding value");
                }
            }

            String uriPath = Optional.ofNullable(uri.getPath()).orElse("/");
            HttpContextImpl ctx = contexts.findContext(protocol, uriPath);
            if (ctx == null) {
                logger.log(Level.DEBUG, "No context found for request "+uriPath+", rejecting as not found");
                response.set(":status","404");
                stream.writeResponseHeaders(true);
                out.close();
                return;
            }

            logger.log(Level.TRACE,() -> "http2 request on "+connection+" "+method+" for "+uri);

            final List<Filter> sf = ctx.getSystemFilters();
            final List<Filter> uf = ctx.getFilters();

            final Filter.Chain sc = new Filter.Chain(sf, ctx.getHandler());
            final Filter.Chain uc = new Filter.Chain(uf, new LinkHandler(sc));

            try {
                if (https) {
                    uc.doFilter(new Http2ExchangeImpl(stream,uri,method,ctx,request,response,in,out));
                } else {
                    uc.doFilter(new Http2ExchangeImpl(stream,uri,method,ctx,request,response,in,out));
                }
            } catch (IOException e) {
            } catch (Exception e) {
                logger.log(Level.WARNING, "Dispatcher Exception on "+stream, e);
                stats.handleExceptionCount.incrementAndGet();
            }
        }
    }

    /* per exchange task */
    class Exchange implements Runnable {
        final HttpConnection connection;
        InputStream rawin;
        OutputStream rawout;
        String protocol;
        ExchangeImpl tx;
        HttpContextImpl ctx;

        Exchange(String protocol, HttpConnection conn) throws IOException {
            this.connection = conn;
            this.protocol = protocol;
        }

        @Override
        public void run() {
            this.rawin = connection.getInputStream();
            this.rawout = connection.getOutputStream();

            logger.log(Level.TRACE, () -> "exchange started "+connection.toString());

            while (!connection.closed) {
                try {
                    runPerRequest();
                    if (connection.closed) {
                        break;
                    }
                } catch (IOException e) {
                    // these are common with clients breaking connections etc
                    logger.log(Level.TRACE, "ServerImpl IOException", e);
                    stats.socketExceptionCount.incrementAndGet();
                    closeConnection(connection);
                    break;
                } catch (Exception e) {
                    logger.log(Level.WARNING, "ServerImpl unexpected exception", e);
                    // the following seems to be a better handling - to return a server internal error rather than simply
                    // closing the connection, but test cases fail
                    //
                    // if (!connection.closed) {
                    //     if (!tx.sentHeaders) {
                    //         reject(500, "internal error", e.toString());
                    //     }
                    // }
                    if (tx == null || !tx.sentHeaders || !tx.closed) {
                        closeConnection(connection);
                        break;
                    }
                } catch (Throwable t) {
                    closeConnection(connection);
                    logger.log(Level.ERROR, "ServerImpl critical error", t);
                    throw t;
                }
            }
            logger.log(Level.TRACE, () -> "exchange finished "+connection.toString());
        }

        private void runPerRequest() throws IOException {
            /* context will be null for new connections */

            logger.log(Level.TRACE,"reading request");

            connection.inRequest = false;

            Request req = new Request(rawin, rawout);
            final String requestLine = req.requestLine();

            if("PRI * HTTP/2.0".equals(requestLine) && ServerConfig.http2OverNonSSL()) {
                logger.log(Level.DEBUG,"found http2 request on non-SSL assuming prior knowledge");
                Http2Exchange exchange = new Http2Exchange(protocol, connection);
                exchange.run();
                return;
            }

            connection.inRequest = true;

            if (requestLine == null || "".equals(requestLine)) {
                /* connection closed */
                logger.log(Level.DEBUG, "no request line: closing");
                closeConnection(connection);
                return;
            }
            connection.requestCount.incrementAndGet();
            stats.requestCount.incrementAndGet();

            logger.log(Level.DEBUG, () -> "Exchange request line: "+ requestLine);
            int space = requestLine.indexOf(" ");
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String method = requestLine.substring(0, space);
            int start = space + 1;
            space = requestLine.indexOf(" ", start);
            if (space == -1) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "Bad request line");
                return;
            }
            String uriStr = requestLine.substring(start, space);
            URI uri;
            try {
                uri = new URI(uriStr);
            } catch (URISyntaxException e3) {
                reject(Code.HTTP_BAD_REQUEST,
                        requestLine, "URISyntaxException thrown");
                return;
            }
            start = space + 1;
            String version = requestLine.substring(start);
            Headers headers = req.headers();

            /* check key for illegal characters, impossible since Headers class validates on mutation */
            // for (var k : headers.keySet()) {
            //     if (!isValidName(k)) {
            //         reject(Code.HTTP_BAD_REQUEST, requestLine,
            //                 "Header key contains illegal characters");
            //         return;
            //     }
            // }
            /* checks for unsupported combinations of lengths and encodings */
            if (headers.containsKey("Content-length")
                    && (headers.containsKey("Transfer-encoding") || headers.get("Content-length").size() > 1)) {
                reject(Code.HTTP_BAD_REQUEST, requestLine,
                        "Conflicting or malformed headers detected");
                return;
            }
            long clen = 0L;
            String headerValue = null;
            List<String> teValueList = headers.get("Transfer-encoding");
            if (teValueList != null && !teValueList.isEmpty()) {
                headerValue = teValueList.get(0);
            }
            if (headerValue != null) {
                if (headerValue.equalsIgnoreCase("chunked") && teValueList.size() == 1) {
                    clen = -1L;
                } else {
                    reject(Code.HTTP_NOT_IMPLEMENTED,
                            requestLine, "Unsupported Transfer-Encoding value");
                    return;
                }
            } else {
                headerValue = headers.getFirst("Content-length");
                if (headerValue != null) {
                    try {
                        clen = Long.parseLong(headerValue);
                    } catch (NumberFormatException e2) {
                        reject(Code.HTTP_BAD_REQUEST,
                                requestLine, "NumberFormatException thrown");
                        return;
                    }
                    if (clen < 0) {
                        reject(Code.HTTP_BAD_REQUEST, requestLine,
                                "Illegal Content-length value");
                        return;
                    }
                }
            }
            logger.log(Level.TRACE,() -> "protocol "+protocol+" uri "+uri+" headers "+headers);
            String uriPath = Optional.ofNullable(uri.getPath()).orElse("/");
            ctx = contexts.findContext(protocol, uriPath);
            if (ctx == null) {
                reject(Code.HTTP_NOT_FOUND,
                        requestLine, "No context found for request");
                return;
            }
            connection.setContext(ctx);
            if (ctx.getHandler() == null) {
                reject(Code.HTTP_INTERNAL_ERROR,
                        requestLine, "No handler for context");
                return;
            }
            tx = new ExchangeImpl(method, uri, req, clen, connection);
            String chdr = headers.getFirst("Connection");
            Headers rheaders = tx.getResponseHeaders();

            if (chdr != null && chdr.equalsIgnoreCase("close")) {
                tx.close = true;
            }
            if (version.equalsIgnoreCase("http/1.0")) {
                tx.http10 = true;
                if (chdr == null) {
                    tx.close = true;
                } else if (chdr.equalsIgnoreCase("keep-alive")) {
                    rheaders.set("Connection", "keep-alive");
                    int idleSeconds = (int) (ServerConfig.getIdleIntervalMillis() / 1000);
                    String val = "timeout=" + idleSeconds;
                    rheaders.set("Keep-alive", val);
                }
            }

            if(tx.close) {
                rheaders.set("Connection", "close");
            }

            /*
                * check if client sent an Expect 100 Continue.
                * In that case, need to send an interim response.
                * In future API may be modified to allow app to
                * be involved in this process.
             */
            String exp = headers.getFirst("Expect");
            if (exp != null && exp.equalsIgnoreCase("100-continue")) {
                logReply(100, requestLine, null);
                sendReply(
                        Code.HTTP_CONTINUE, false, null);
            }
            /*
                * uf is the list of filters seen/set by the user.
                * sf is the list of filters established internally
                * and which are not visible to the user. uc and sc
                * are the corresponding Filter.Chains.
                * They are linked together by a LinkHandler
                * so that they can both be invoked in one call.
             */
            final List<Filter> sf = ctx.getSystemFilters();
            final List<Filter> uf = ctx.getFilters();

            final Filter.Chain sc = new Filter.Chain(sf, ctx.getHandler());
            final Filter.Chain uc = new Filter.Chain(uf, new LinkHandler(sc));

            /* set up the two stream references */
            tx.getRequestBody();
            tx.getResponseBody();
            if (https) {
                uc.doFilter(new HttpsExchangeImpl(tx));
            } else {
                uc.doFilter(new HttpExchangeImpl(tx));
            }
            if (tx.close) {
                closeConnection(connection);
            } else {
                // logger.log(Level.INFO,"flushing response");
                // tx.getResponseBody().flush();
                tx = null;
            }
        }

        void reject(int code, String requestStr, String message) {
            logReply(code, requestStr, message);
            sendReply(
                    code, true, "<h1>" + code + Code.msg(code) + "</h1>" + message);
        }

        void sendReply(
                int code, boolean closeNow, String text) {
            try {
                StringBuilder builder = new StringBuilder(512);
                builder.append("HTTP/1.1 ")
                        .append(code).append(Code.msg(code)).append("\r\n");

                var informational = (code >= 100 && code < 200);

                if (text != null && text.length() != 0) {
                    builder.append("Content-length: ")
                            .append(text.length()).append("\r\n")
                            .append("Content-type: text/html\r\n");
                } else {
                    if (!informational) {
                        // no body for 1xx responses
                        builder.append("Content-length: 0\r\n");
                    } 
                    text = "";
                }
                if (closeNow) {
                    builder.append("Connection: close\r\n");
                }
                builder.append("\r\n").append(text);
                rawout.write(builder.toString().getBytes(ISO_8859_1));
                rawout.flush();
                if (closeNow) {
                    closeConnection(connection);
                }
            } catch (IOException e) {
                logger.log(Level.TRACE, "ServerImpl.sendReply", e);
                stats.replyErrorCount.incrementAndGet();
                closeConnection(connection);
            }
        }

    }

    void logReply(int code, String requestStr, String text) {
        if (!logger.isLoggable(Level.DEBUG)) {
            return;
        }
        CharSequence r;
        if (requestStr.length() > 80) {
            r = requestStr.substring(0, 80) + "<TRUNCATED>";
        } else {
            r = requestStr;
        }
        logger.log(Level.DEBUG, () -> "reply "+ r + " [" + code + Code.msg(code) + "] (" + (text!=null ? text : "") + ")");
    }

    void delay() {
        Thread.yield();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
        }
    }

    HttpServer getWrapper() {
        return wrapper;
    }

    /**
     * Responsible for closing connections that have been idle or exceed other
     * limits
     */
    class ConnectionCleanerTask extends TimerTask {

        @Override
        public void run() {
            long now = ActivityTimer.now();

            for (var c : allConnections) {
                if (c.drainingAt != 0 && now- c.drainingAt >= IDLE_INTERVAL / 2) {
                    closeConnection(c);
                }
                if (now- c.lastActivityTime >= IDLE_INTERVAL && !c.inRequest) {
                    logger.log(Level.DEBUG, "closing idle connection");
                    stats.idleCloseCount.incrementAndGet();
                    closeConnection(c);
                    // idle.add(c);
                } else if (c.noActivity && (now - c.lastActivityTime >= NEWLY_ACCEPTED_CONN_IDLE_INTERVAL)) {
                    logger.log(Level.WARNING, "closing newly accepted idle connection");
                    closeConnection(c);
                } else if (MAX_REQ_TIME != -1 && c.inRequest && (now - c.lastActivityTime >= MAX_REQ_TIME)) {
                    logger.log(Level.WARNING, "closing connection due to request processing time");
                    closeConnection(c);
                }
                // TODO is MAX_RSP_TIME needed?
            }
            // close idle connections if over limit
            // idle.stream().limit(Math.max(idle.size() - MAX_IDLE_CONNECTIONS, 0)).forEach(c -> closeConnection(c));
        }
    }

    /**
     * Converts and returns the passed {@code secs} as milli seconds. If the
     * passed {@code secs} is negative or zero or if the conversion from seconds
     * to milli seconds results in a negative number, then this method returns
     * -1.
     */
    private static long getTimeMillis(long secs) {
        if (secs <= 0) {
            return -1;
        }
        final long milli = secs * 1000;
        // this handles potential numeric overflow that may have happened during
        // conversion
        return milli > 0 ? milli : -1;
    }
}
