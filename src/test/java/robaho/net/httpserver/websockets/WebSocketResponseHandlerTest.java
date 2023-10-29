package robaho.net.httpserver.websockets;

import static org.testng.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import robaho.net.httpserver.Code;
import robaho.net.httpserver.StubHttpExchange;

/*
 * #%L
 * NanoHttpd-Websocket
 * %%
 * Copyright (C) 2012 - 2015 nanohttpd
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

public class WebSocketResponseHandlerTest {

    Headers headers;
    WebSocketHandler handler;
    HttpExchange exchange;

    @BeforeMethod
    public void setUp() {
        this.headers = new Headers();
        this.headers.add("upgrade", "websocket");
        this.headers.add("connection", "Upgrade");
        this.headers.add("sec-websocket-key", "x3JJHMbDL1EzLkh9GBhXDw==");
        this.headers.add("sec-websocket-protocol", "chat, superchat");
        this.headers.add("sec-websocket-version", "13");

        handler = new TestWebsocketHandler();
        exchange = new TestHttpExchange(headers, new Headers());
    }

    private static class TestWebsocketHandler extends WebSocketHandler {
        @Override
        protected WebSocket openWebSocket(HttpExchange exchange) {
            return new TestWebSocket(exchange);
        }

        private static class TestWebSocket extends WebSocket {
            TestWebSocket(HttpExchange exchange) {
                super(exchange);
            }

            protected void onClose(CloseCode code, String reason, boolean initiatedByRemote) {
            }

            @Override
            protected void onMessage(WebSocketFrame message) throws WebSocketException {
            }

            @Override
            protected void onPong(WebSocketFrame pong) throws WebSocketException {
            }
        }

    }

    private static class TestHttpExchange extends StubHttpExchange {
        private final Headers request, response;

        private InputStream in = new ByteArrayInputStream(new byte[0]);
        private OutputStream out = new ByteArrayOutputStream();
        private int responseCode;

        TestHttpExchange(Headers request, Headers response) {
            this.request = request;
            this.response = response;
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
        public void sendResponseHeaders(int rCode, long responseLength) {
            responseCode = rCode;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

    }

    private void testResponseHeader(String key, String expected) {
        String value = exchange.getResponseHeaders().getFirst(key);
        if (expected == null && value == null) {
            return;
        }
        if (expected == null && value != null) {
            Assert.fail(key + " should not have a value " + value);
        }
        assertEquals(value, expected);
    }

    @Test
    public void testConnectionHeaderHandlesKeepAlive_FixingFirefoxConnectIssue() throws IOException {
        this.headers.set("connection", "keep-alive, Upgrade");
        handler.handle(exchange);
    }

    @Test
    public void testHandshakeReturnsResponseWithExpectedHeaders() throws IOException {
        handler.handle(exchange);

        testResponseHeader(Util.HEADER_WEBSOCKET_ACCEPT, "HSmrc0sMlYUkAGmm5OPpG2HaGWk=");
        testResponseHeader(Util.HEADER_WEBSOCKET_PROTOCOL, "chat");
    }

    @Test
    public void testMissingKeyReturnsErrorResponse() throws IOException {
        this.headers.remove("sec-websocket-key");

        handler.handle(exchange);

        assertEquals(Code.HTTP_BAD_REQUEST, exchange.getResponseCode());
    }

    @Test
    public void testWrongConnectionHeaderReturnsNullResponse() throws IOException {
        this.headers.set("connection", "Junk");
        handler.handle(exchange);
        testResponseHeader(Util.HEADER_UPGRADE, null);
    }

    @Test
    public void testWrongUpgradeHeaderReturnsNullResponse() throws IOException {
        this.headers.set("upgrade", "not a websocket");
        handler.handle(exchange);
        testResponseHeader(Util.HEADER_UPGRADE, null);
    }

    @Test
    public void testWrongWebsocketVersionReturnsErrorResponse() throws IOException {
        this.headers.set("sec-websocket-version", "12");
        handler.handle(exchange);
        assertEquals(Code.HTTP_BAD_REQUEST, exchange.getResponseCode());
    }

    @Test
    public void testSetMaskingKeyThrowsExceptionMaskingKeyLengthIsNotFour() {
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Text, true, new byte[0]);
        for (int maskingKeyLength = 0; maskingKeyLength < 10; maskingKeyLength++) {
            if (maskingKeyLength == 4)
                continue;
            try {
                webSocketFrame.setMaskingKey(new byte[maskingKeyLength]);
                Assert.fail("IllegalArgumentException expected but not thrown");
            } catch (IllegalArgumentException e) {

            }
        }
    }

    @Test
    public void testIsMasked() {
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Text, true, new byte[0]);
        Assert.assertFalse(webSocketFrame.isMasked(), "isMasked should return true if masking key is not set.");

        webSocketFrame.setMaskingKey(new byte[4]);
        Assert.assertTrue(webSocketFrame.isMasked(), "isMasked should return true if correct masking key is set.");

    }

    @Test
    public void testWriteWhenNotMasked() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Text, true, "payload".getBytes());
        webSocketFrame.write(byteArrayOutputStream);
        byte[] writtenBytes = byteArrayOutputStream.toByteArray();
        Assert.assertEquals(9, writtenBytes.length);
        Assert.assertEquals(-127, writtenBytes[0], "Header byte incorrect.");
        Assert.assertEquals(7, writtenBytes[1], "Payload length byte incorrect.");
        Assert.assertEquals(new byte[] {
                -127,
                7,
                112,
                97,
                121,
                108,
                111,
                97,
                100
        }, writtenBytes);
    }

    @Test
    public void testWriteWhenMasked() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Binary, true, "payload".getBytes());
        webSocketFrame.setMaskingKey(new byte[] {
                12,
                45,
                33,
                32
        });
        webSocketFrame.write(byteArrayOutputStream);
        byte[] writtenBytes = byteArrayOutputStream.toByteArray();
        Assert.assertEquals(13, writtenBytes.length);
        Assert.assertEquals(-126, writtenBytes[0], "Header byte incorrect.");
        Assert.assertEquals(-121, writtenBytes[1], "Payload length byte incorrect.");
        Assert.assertEquals(new byte[] {
                -126,
                -121,
                12,
                45,
                33,
                32,
                124,
                76,
                88,
                76,
                99,
                76,
                69
        }, writtenBytes);
    }

    @Test
    public void testWriteWhenNotMaskedPayloadLengthGreaterThan125() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Ping, true, new byte[257]);
        webSocketFrame.write(byteArrayOutputStream);
        byte[] writtenBytes = byteArrayOutputStream.toByteArray();
        Assert.assertEquals(261, writtenBytes.length);
        Assert.assertEquals(-119, writtenBytes[0], "Header byte incorrect.");
        Assert.assertEquals(new byte[] {
                126,
                1,
                1
        }, new byte[] {
                writtenBytes[1],
                writtenBytes[2],
                writtenBytes[3]
        }, "Payload length bytes incorrect.");
    }

    @Test
    public void testWriteWhenMaskedPayloadLengthGreaterThan125() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Ping, false, new byte[257]);
        webSocketFrame.setMaskingKey(new byte[] {
                19,
                25,
                79,
                11
        });
        webSocketFrame.write(byteArrayOutputStream);
        byte[] writtenBytes = byteArrayOutputStream.toByteArray();
        Assert.assertEquals(265, writtenBytes.length);
        Assert.assertEquals(9, writtenBytes[0], "Header byte incorrect.");
        Assert.assertEquals(new byte[] {
                -2,
                1,
                1
        }, new byte[] {
                writtenBytes[1],
                writtenBytes[2],
                writtenBytes[3]
        }, "Payload length bytes incorrect.");
    }

    @Test
    public void testWriteWhenNotMaskedPayloadLengthGreaterThan65535() throws IOException {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        WebSocketFrame webSocketFrame = new WebSocketFrame(OpCode.Ping, true, new byte[65536]);
        webSocketFrame.write(byteArrayOutputStream);
        byte[] writtenBytes = byteArrayOutputStream.toByteArray();
        Assert.assertEquals(65546, writtenBytes.length);
        Assert.assertEquals(-119, writtenBytes[0], "Header byte incorrect.");
        Assert.assertEquals(new byte[] {
                127,
                0,
                0,
                0,
                0,
                0,
                1,
                0,
                0
        }, Arrays.copyOfRange(writtenBytes, 1, 10), "Payload length bytes incorrect.");
    }
}
