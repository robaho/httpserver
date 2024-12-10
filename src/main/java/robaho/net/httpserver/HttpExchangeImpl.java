/*
 * Copyright (c) 2005, 2006, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.net.*;
import com.sun.net.httpserver.*;

final class HttpExchangeImpl extends HttpExchange {

    private final ExchangeImpl impl;

    HttpExchangeImpl(ExchangeImpl impl) {
        this.impl = impl;
    }

    @Override
    public Headers getRequestHeaders() {
        return impl.getRequestHeaders();
    }

    @Override
    public Headers getResponseHeaders() {
        return impl.getResponseHeaders();
    }

    @Override
    public URI getRequestURI() {
        return impl.getRequestURI();
    }

    @Override
    public String getRequestMethod() {
        return impl.getRequestMethod();
    }

    @Override
    public HttpContextImpl getHttpContext() {
        return impl.getHttpContext();
    }

    @Override
    public void close() {
        impl.close();
    }

    @Override
    public InputStream getRequestBody() {
        return impl.getRequestBody();
    }

    @Override
    public int getResponseCode() {
        return impl.getResponseCode();
    }

    @Override
    public OutputStream getResponseBody() {
        return impl.getResponseBody();
    }

    @Override
    public void sendResponseHeaders(int rCode, long contentLen)
            throws IOException {
        impl.sendResponseHeaders(rCode, contentLen);
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return impl.getRemoteAddress();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return impl.getLocalAddress();
    }

    @Override
    public String getProtocol() {
        return impl.getProtocol();
    }

    @Override
    public Object getAttribute(String name) {
        return impl.getAttribute(name);
    }

    @Override
    public void setAttribute(String name, Object value) {
        impl.setAttribute(name, value);
    }

    @Override
    public void setStreams(InputStream i, OutputStream o) {
        impl.setStreams(i, o);
    }

    @Override
    public HttpPrincipal getPrincipal() {
        return impl.getPrincipal();
    }

    ExchangeImpl getExchangeImpl() {
        return impl;
    }
}
