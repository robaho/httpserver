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

import com.sun.net.httpserver.Headers;

/**
 */
class Request {

    static final int BUF_LEN = 2048;
    static final byte CR = 13;
    static final byte LF = 10;

    private String startLine;
    private InputStream is;
    private OutputStream os;

    Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
        is = rawInputStream;
        os = rawout;
        do {
            startLine = readLine();
            /* skip blank lines */
        } while ("".equals(startLine));
    }

    char[] buf = new char[BUF_LEN];
    int pos;
    StringBuffer lineBuf;

    public InputStream inputStream() {
        return is;
    }

    public OutputStream outputStream() {
        return os;
    }

    /**
     * read a line from the stream returning as a String.
     * Not used for reading headers.
     */

    public String readLine() throws IOException {
        boolean gotCR = false, gotLF = false;
        pos = 0;
        lineBuf = new StringBuffer();
        while (!gotLF) {
            int c;
            
            try {
                c = is.read();
            } catch(IOException e) {
                if(pos==0) return null;
                throw e;
            }

            if (c == -1) {
                return null;
            }
            if (gotCR) {
                if (c == LF) {
                    gotLF = true;
                } else {
                    gotCR = false;
                    consume(CR);
                    consume(c);
                }
            } else {
                if (c == CR) {
                    gotCR = true;
                } else {
                    consume(c);
                }
            }
        }
        lineBuf.append(buf, 0, pos);
        return new String(lineBuf);
    }

    private void consume(int c) {
        if (pos == BUF_LEN) {
            lineBuf.append(buf);
            pos = 0;
        }
        buf[pos++] = (char) c;
    }

    /**
     * returns the request line (first line of a request)
     */
    public String requestLine() {
        return startLine;
    }

    Headers hdrs = null;

    @SuppressWarnings("fallthrough")
    Headers headers() throws IOException {
        if (hdrs != null) {
            return hdrs;
        }
        hdrs = new Headers();

        char s[] = new char[10];
        int len = 0;

        int firstc = is.read();

        // check for empty headers
        if (firstc == CR || firstc == LF) {
            int c = is.read();
            if (c == CR || c == LF) {
                return hdrs;
            }
            s[0] = (char) firstc;
            len = 1;
            firstc = c;
        }

        while (firstc != LF && firstc != CR && firstc >= 0) {
            int keyend = -1;
            int c;
            boolean inKey = firstc > ' ';
            s[len++] = (char) firstc;
            parseloop: {
                while ((c = is.read()) >= 0) {
                    switch (c) {
                        /* fallthrough */
                        case ':':
                            if (inKey && len > 0)
                                keyend = len;
                            inKey = false;
                            break;
                        case '\t':
                            c = ' ';
                        case ' ':
                            inKey = false;
                            break;
                        case CR:
                        case LF:
                            firstc = is.read();
                            if (c == CR && firstc == LF) {
                                firstc = is.read();
                                if (firstc == CR)
                                    firstc = is.read();
                            }
                            if (firstc == LF || firstc == CR || firstc > ' ')
                                break parseloop;
                            /* continuation */
                            c = ' ';
                            break;
                    }
                    if (len >= s.length) {
                        char ns[] = new char[s.length * 2];
                        System.arraycopy(s, 0, ns, 0, len);
                        s = ns;
                    }
                    s[len++] = (char) c;
                }
                firstc = -1;
            }
            while (len > 0 && s[len - 1] <= ' ')
                len--;
            String k;
            if (keyend <= 0) {
                k = null;
                keyend = 0;
            } else {
                k = String.copyValueOf(s, 0, keyend);
                if (keyend < len && s[keyend] == ':')
                    keyend++;
                while (keyend < len && s[keyend] <= ' ')
                    keyend++;
            }
            String v;
            if (keyend >= len)
                v = new String();
            else
                v = String.copyValueOf(s, keyend, len - keyend);

            if (hdrs.size() >= ServerConfig.getMaxReqHeaders()) {
                throw new IOException("maximum number of headers exceeded");
            }
            if (k == null) { // Headers disallows null keys, use empty string
                k = ""; // instead to represent invalid key
            }
            hdrs.add(k, v);
            len = 0;
        }
        return hdrs;
    }
}
