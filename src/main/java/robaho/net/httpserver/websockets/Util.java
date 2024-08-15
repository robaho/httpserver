package robaho.net.httpserver.websockets;

import java.io.IOException;

/*
 * #%L
 * NanoHttpd-Websocket
 * %%
 * Copyright (C) 2012 - 2016 nanohttpd
 * %%
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * 3. Neither the name of the nanohttpd nor the names of its contributors
 *    may be used to endorse or promote products derived from this software without
 *    specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

import com.sun.net.httpserver.HttpExchange;

public class Util {

    public static final String HEADER_UPGRADE = "Upgrade";

    public static final String HEADER_UPGRADE_VALUE = "websocket";

    public static final String HEADER_CONNECTION = "Connection";

    public static final String HEADER_CONNECTION_VALUE = "Upgrade";

    public static final String HEADER_WEBSOCKET_VERSION = "sec-websocket-version";

    public static final String HEADER_WEBSOCKET_VERSION_VALUE = "13";

    public static final String HEADER_WEBSOCKET_KEY = "sec-websocket-key";

    public static final String HEADER_WEBSOCKET_ACCEPT = "sec-websocket-accept";

    public static final String HEADER_WEBSOCKET_PROTOCOL = "sec-websocket-protocol";

    private final static String WEBSOCKET_KEY_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static String encodeBase64(byte[] buf) {
        return Base64.getEncoder().encodeToString(buf);
    }

    public static String makeAcceptKey(String key) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        String text = key + Util.WEBSOCKET_KEY_MAGIC;
        md.update(text.getBytes(), 0, text.length());
        byte[] sha1hash = md.digest();
        return encodeBase64(sha1hash);
    }

    private static final String CONTENT_TYPE = "Content-type";

    public static void sendResponseHeaders(HttpExchange exchange, int code, String reason) throws IOException {
        var bytes = reason.getBytes();
        exchange.getRequestHeaders().set(CONTENT_TYPE, "text/plain");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.getRequestBody().close();
    }
}
