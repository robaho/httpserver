package robaho.net.httpserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;

import robaho.net.httpserver.http2.HTTP2Stream;

public class Http2ExchangeImpl extends HttpExchange {
    private final Headers request;
    private final Headers response;
    private final InputStream in;
    private final OutputStream out;
    private final URI uri;
    private final String method;
    private final HttpContext ctx;
    private final HTTP2Stream stream;
    private HttpPrincipal principal;
    private int responseCode;

    public Http2ExchangeImpl(HTTP2Stream stream, URI uri, String method, HttpContext ctx, Headers request, Headers response, InputStream in, OutputStream out) {
        this.request = request;
        this.response = response;
        this.stream = stream;
        this.in = in;
        this.out = out;
        this.uri = uri;
        this.method = method;
        this.ctx = ctx;
    }

    @Override
    public Headers getRequestHeaders() {
        return request;
    }

    @Override
    public Headers getResponseHeaders() {
        return response;
    }

    @Override
    public InputStream getRequestBody() {
        return in;
    }

    @Override
    public OutputStream getResponseBody() {
        return out;
    }

    @Override
    public URI getRequestURI() {
        return uri;
    }

    @Override
    public String getRequestMethod() {
        return method;
    }

    @Override
    public HttpContext getHttpContext() {
        return ctx;
    }

    @Override
    public void close() {
        stream.close();
    }

    @Override
    public void sendResponseHeaders(int rCode, long responseLength) throws IOException {
        if(responseLength>0) {
            response.set("Content-length", Long.toString(responseLength));
        } else if(responseLength==0) {
            // no chunked encoding so just ignore
        } else {
            // -1 means no data will be sent, so should set end of stream
            // response.set("Content-Length", Long.toString(responseLength));
        }
        response.set(":status",Long.toString(rCode));
        responseCode = rCode;
        stream.writeResponseHeaders(responseLength==-1);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return stream.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return stream.getLocalAddress();
    }

    @Override
    public int getResponseCode() {
        return responseCode;
    }

    @Override
    public String getProtocol() {
        return "HTTP/2";
    }

    @Override
    public Object getAttribute(String name) {
        return ctx.getAttributes().get(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        ctx.getAttributes().put(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return principal;
    }
}
