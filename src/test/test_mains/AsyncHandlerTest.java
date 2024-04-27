/*
 * Copyright (c) 2019, 2023, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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

/**
 * @test
 * @summary test that handler can send response asynchronously
 * @library /test/lib
 * @modules java.base/sun.net.www
 * @run main/othervm AsyncHandlerTest
 */

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URL;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import jdk.test.lib.net.URIBuilder;

public class AsyncHandlerTest implements HttpHandler {
    private final HttpServer server;


    public AsyncHandlerTest(HttpServer server) {
        this.server = server;
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        ex.sendResponseHeaders(200, 0L);
        server.getExecutor().execute(() -> {
            try (var os = ex.getResponseBody()) {
                os.write("hello".getBytes());
            } catch(IOException e) {
                e.printStackTrace();
            }
        });
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        try {
            server.createContext("/context", new AsyncHandlerTest(server));
            server.start();

            URL url = URIBuilder.newBuilder()
                    .scheme("http")
                    .loopback()
                    .port(server.getAddress().getPort())
                    .path("/context")
                    .toURLUnchecked();

            HttpURLConnection urlc = (HttpURLConnection) url.openConnection();
            System.out.println("Client: Response code received: " + urlc.getResponseCode());
            try (InputStream is = urlc.getInputStream()) {
                String body = new String(is.readAllBytes());
                if(!"hello".equals(body)) throw new IllegalStateException("incorrect body "+body);
            }
        } finally {
            server.stop(0);
        }
    }
}
