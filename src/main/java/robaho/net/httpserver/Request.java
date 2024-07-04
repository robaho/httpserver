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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.net.httpserver.Headers;

/**
 */
class Request {

    static final char CR = 13;
    static final char LF = 10;

    private final InputStream is;
    private final OutputStream os;

    private final StringBuilder requestLine = new StringBuilder(64);

    Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
        is = rawInputStream;
        os = rawout;

        readRequestLine();
    }

    public InputStream inputStream() {
        return is;
    }

    public OutputStream outputStream() {
        return os;
    }

    static class PushbackStream {
        private final InputStream is;
        private int pushback = -1;
        private boolean eof=false;

        public PushbackStream(InputStream is) {
            this.is = is;
        }
        public int read() throws IOException {
            if(pushback!=-1) {
                try {
                    return pushback;
                } finally {
                    pushback=-1;
                }
            }
            if(eof) return -1;
            return is.read();
        }
        public void skipWhitespace() throws IOException {
            int c;
            for(c=read();c==' ' || c=='\t';c=read()){}
            if(c==-1) eof=true; else pushback=c;
        }
    }

    /**
     * read a line from the stream returning as a String.
     * Not used for reading headers.
     */
    private boolean readLine(StringBuilder lineBuf) throws IOException {
        boolean gotCR = false;
        while (true) {
            int c;
            
            try {
                c = is.read();
            } catch(IOException e) {
                if(lineBuf.isEmpty()) return false;
                throw e;
            }

            if (c == -1) {
                lineBuf.setLength(0);
                return false;
            }
            if (gotCR) {
                if (c == LF) {
                    return true;
                } else {
                    gotCR = false;
                    lineBuf.append(CR);
                    lineBuf.append((char)c);
                }
            } else {
                if (c == CR) {
                    gotCR = true;
                } else {
                    lineBuf.append((char)c);
                }
            }
        }
    }

    /**
     * read the request line into the buffer
     */
    private void readRequestLine() throws IOException {
        while(readLine(requestLine)) {
            if(requestLine.length()>0) return;
        }
    }

    /** return trimmed value from StringBuilder and reset to empty */
    private static String trimmed(BufferedBuilder bb) {
        return bb.trimmed();
    }

    /**
     * @returns the request line or the empty string if not found
     */
    public StringBuilder requestLine() {
        return requestLine;
    }

    Headers hdrs = null;

    private static final class BufferedBuilder {
        private byte[] buffer;
        private int count;
        BufferedBuilder(int capacity) {
            buffer = new byte[capacity];
        }
        boolean isEmpty() {
            return count==0;
        }
        void append(char c) {
            if(count==buffer.length) {
                buffer = Arrays.copyOf(buffer, buffer.length*2);
            }
            buffer[count++]=(byte)c;
        }
        public String trimmed() {
            int start=0;
            while(start<count && buffer[start]==' ') start++;
            int end=count;
            while(end>0 && buffer[end-1]==' ') end--;
            count=0;
            return new String(buffer,start,end-start,StandardCharsets.ISO_8859_1);
        }
    }

    @SuppressWarnings("fallthrough")
    Headers headers() throws IOException {
        if (hdrs != null) {
            return hdrs;
        }
        hdrs = new Headers();

        BufferedBuilder key = new BufferedBuilder(32);
        BufferedBuilder value = new BufferedBuilder(128);

        PushbackStream pbs = new PushbackStream(is);

        BufferedBuilder current = key;
        boolean prevCR = false;
        boolean sol = true;
        int c;

        while((c=pbs.read())!=-1) {
            if(c==CR) { prevCR = true; }
            else if(c==LF && prevCR) {
                if(key.isEmpty() && value.isEmpty()) break;
                if(sol) {
                    hdrs.add(trimmed(key),trimmed(value));
                    break;
                }
                prevCR=false;
                sol=true;
            } else {
                if(sol && (c==' ' || c=='\t')) {
                    pbs.skipWhitespace();
                    current=value;
                    sol=false;
                } else {
                    if(sol) {
                        if(!key.isEmpty() || !value.isEmpty()) {
                            hdrs.add(trimmed(key),trimmed(value));
                        }
                        current=key;
                        sol=false;
                    }
                    if(c==':' && current==key) {
                        current=value;
                        pbs.skipWhitespace();
                    } else {
                        current.append((char)c);
                    }
                }
            }
        }
        return hdrs;
    }
}
