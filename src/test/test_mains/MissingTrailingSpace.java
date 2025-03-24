/*
 * Copyright (c) 2015, 2019, Oracle and/or its affiliates. All rights reserved.
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
 * @bug 8068795
 * @summary HttpServer missing tailing space for some response codes
 * @run main MissingTrailingSpace
 * @run main/othervm -Djava.net.preferIPv6Addresses=true MissingTrailingSpace
 * @author lev.priima@oracle.com
 */

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jdk.test.lib.RawClient;

public class MissingTrailingSpace {

    private static final int noMsgCode = 207;
    private static final String someContext = "/context";

    public static void main(String[] args) throws Exception {
        InetAddress loopback = InetAddress.getLoopbackAddress();
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 0);
        try {
            server.setExecutor(Executors.newFixedThreadPool(1));
            server.createContext(someContext, new HttpHandler() {
                @Override
                public void handle(HttpExchange msg) {
                    try {
                        try {
                            msg.sendResponseHeaders(noMsgCode, -1);
                        } catch(IOException ioe) {
                            ioe.printStackTrace();
                        }
                    } finally {
                        msg.close();
                    }
                }
            });
            server.start();
            System.out.println("Server started at port "
                               + server.getAddress().getPort());

            RawClient.runRawSocketHttpClient(loopback, server.getAddress().getPort(),someContext,"", -1);
        } finally {
            ((ExecutorService)server.getExecutor()).shutdown();
            server.stop(0);
        }
        System.out.println("Server finished.");
    }

}
