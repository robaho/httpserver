package robaho.net.httpserver.websockets;

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

import java.io.IOException;
import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.sun.net.httpserver.HttpExchange;

public abstract class WebSocket {

    Logger logger = Logger.getLogger(WebSocket.class.getName());

    private final InputStream in;
    private final OutputStream out;

    private OpCode continuousOpCode = null;

    private final List<WebSocketFrame> continuousFrames = new LinkedList<WebSocketFrame>();

    private volatile State state = State.UNCONNECTED;

    private Lock lock = new ReentrantLock();

    private final URI uri;

    protected WebSocket(HttpExchange exchange) {
        this.uri = exchange.getRequestURI();

        logger.info("connecting websocket "+uri);

        this.state = State.CONNECTING;
        this.in = exchange.getRequestBody();
        this.out = exchange.getResponseBody();
    }

    public boolean isOpen() {
        return state == State.OPEN;
    }

    protected void onOpen() throws WebSocketException {
    }

    protected abstract void onClose(CloseCode code, String reason, boolean initiatedByRemote);

    protected abstract void onMessage(WebSocketFrame message) throws WebSocketException;

    protected abstract void onPong(WebSocketFrame pong) throws WebSocketException;

    protected void onException(IOException exception) {
        if(state!=State.CLOSING && state!=State.CLOSED) {
            logger.log(Level.FINER, "exception on websocket", exception);
        }
    }

    protected void onFrameReceived(WebSocketFrame frame) {
        logger.log(Level.FINER, () -> "frame received: " + frame);
    }

    /**
     * Debug method. <b>Do not Override unless for debug purposes!</b><br>
     * This method is called before actually sending the frame.
     * 
     * @param frame
     *              The sent WebSocket Frame.
     */
    protected void onFrameSent(WebSocketFrame frame) {
        logger.log(Level.FINER, () -> "frame sent: " + frame);
    }

    public void close(CloseCode code, String reason, boolean initiatedByRemote) throws IOException {
        logger.info("closing websocket "+uri);

        State oldState = this.state;
        this.state = State.CLOSING;
        if (oldState == State.OPEN) {
            sendFrame(new CloseFrame(code, reason));
        } else {
            doClose(code, reason, initiatedByRemote);
        }
    }

    void doClose(CloseCode code, String reason, boolean initiatedByRemote) {
        if (this.state == State.CLOSED) {
            return;
        }
        if (this.in != null) {
            try {
                this.in.close();
            } catch (IOException expected) {
            }
        }
        if (this.out != null) {
            try {
                this.out.close();
            } catch (IOException expected) {
            }
        }
        this.state = State.CLOSED;
        onClose(code, reason, initiatedByRemote);
    }

    // --------------------------------IO--------------------------------------

    private void handleCloseFrame(WebSocketFrame frame) throws IOException {
        CloseCode code = CloseCode.NormalClosure;
        String reason = "";
        if (frame instanceof CloseFrame) {
            code = ((CloseFrame) frame).getCloseCode();
            reason = ((CloseFrame) frame).getCloseReason();
        }
        logger.finest("handleCloseFrame: "+uri+", code="+code+", reason="+reason+", state "+this.state);
        if (this.state == State.CLOSING) {
            // Answer for my requested close
            doClose(code, reason, false);
        } else {
            close(code, reason, true);
        }
    }

    private void handleFrameFragment(WebSocketFrame frame) throws IOException {
        if (frame.getOpCode() != OpCode.Continuation) {
            // First
            if (this.continuousOpCode != null) {
                throw new WebSocketException(CloseCode.ProtocolError,
                        "Previous continuous frame sequence not completed.");
            }
            this.continuousOpCode = frame.getOpCode();
            this.continuousFrames.clear();
            this.continuousFrames.add(frame);
        } else if (frame.isFin()) {
            // Last
            if (this.continuousOpCode == null) {
                throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
            }
            this.continuousFrames.add(frame);
            onMessage(new WebSocketFrame(this.continuousOpCode, this.continuousFrames));
            this.continuousOpCode = null;
            this.continuousFrames.clear();
        } else if (this.continuousOpCode == null) {
            // Unexpected
            throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence was not started.");
        } else {
            // Intermediate
            this.continuousFrames.add(frame);
        }
    }

    private void handleWebsocketFrame(WebSocketFrame frame) throws IOException {
        onFrameReceived(frame);
        if (frame.getOpCode() == OpCode.Close) {
            handleCloseFrame(frame);
        } else if (frame.getOpCode() == OpCode.Ping) {
            sendFrame(new WebSocketFrame(OpCode.Pong, true, frame.getBinaryPayload()));
        } else if (frame.getOpCode() == OpCode.Pong) {
            onPong(frame);
        } else if (!frame.isFin() || frame.getOpCode() == OpCode.Continuation) {
            handleFrameFragment(frame);
        } else if (this.continuousOpCode != null) {
            throw new WebSocketException(CloseCode.ProtocolError, "Continuous frame sequence not completed.");
        } else if (frame.getOpCode() == OpCode.Text || frame.getOpCode() == OpCode.Binary) {
            onMessage(frame);
        } else {
            throw new WebSocketException(CloseCode.ProtocolError, "Non control or continuous frame expected.");
        }
    }

    // --------------------------------Close-----------------------------------

    public void ping(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Ping, true, payload));
    }

    void readWebsocket() {
        try {
            state = State.OPEN;
            logger.fine("websocket open "+uri);
            onOpen();
            while (this.state == State.OPEN) {
                handleWebsocketFrame(WebSocketFrame.read(in));
            }
        } catch (EOFException e) {
            onException(e);
            doClose(CloseCode.AbnormalClosure, e.toString(), false);
        } catch (CharacterCodingException e) {
            onException(e);
            doClose(CloseCode.InvalidFramePayloadData, e.toString(), false);
        } catch (IOException e) {
            onException(e);
            if (e instanceof WebSocketException wse) {
                doClose(wse.getCode(), wse.getReason(), false);
            } else {
                doClose(CloseCode.AbnormalClosure, e.toString(), false);
            }
        } finally {
            doClose(CloseCode.InternalServerError, "Handler terminated without closing the connection.", false);
            logger.finest("readWebsocket() exiting "+uri);
        }
    }

    public void send(byte[] payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Binary, true, payload));
    }

    public void send(String payload) throws IOException {
        sendFrame(new WebSocketFrame(OpCode.Text, true, payload));
    }

    public void sendFrame(WebSocketFrame frame) throws IOException {
        lock.lock();
        try {
            onFrameSent(frame);
            frame.write(this.out);
        } finally {
            lock.unlock();
        }
    }
}
