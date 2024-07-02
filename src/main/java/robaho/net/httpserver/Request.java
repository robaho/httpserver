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

    static final char CR = 13;
    static final char LF = 10;

    private String startLine;
    private final InputStream is;
    private final OutputStream os;

    Request(InputStream rawInputStream, OutputStream rawout) throws IOException {
        is = rawInputStream;
        os = rawout;
        do {
            startLine = readLine();
            /* skip blank lines */
        } while ("".equals(startLine));
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

    // efficient building of trimmed strings
    static class StrBuilder {
        private char buffer[] = new char[128];
        private int count=0;
        public void append(int c) {
            if(count==0 && c==' ') return;
            if(count==buffer.length) {
                char tmp[] = new char[buffer.length*2];
                System.arraycopy(buffer,0,tmp,0,count);
                buffer=tmp;
            }
            buffer[count++]=(char)c;
        }
        @Override
        public String toString() {
            while(count>0 && buffer[count-1]==' ') count--;
            return new String(buffer,0,count);
        }
        public boolean isEmpty() {
            return count==0;
        }
        public void clear() {
            count=0;
        }
    }
    
    /**
     * read a line from the stream returning as a String.
     * Not used for reading headers.
     */
    private String readLine() throws IOException {
        StringBuilder lineBuf = new StringBuilder();

        boolean gotCR = false;
        while (true) {
            int c;
            
            try {
                c = is.read();
            } catch(IOException e) {
                if(lineBuf.length()==0) return null;
                throw e;
            }

            if (c == -1) {
                return null;
            }
            if (gotCR) {
                if (c == LF) {
                    return new String(lineBuf);
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

        StrBuilder key = new StrBuilder();
        StrBuilder value = new StrBuilder();

        PushbackStream pbs = new PushbackStream(is);

        boolean inKey = true;
        boolean prevCR = false;
        boolean sol = true;
        int c;

        while((c=pbs.read())!=-1) {
            if(c==CR) { prevCR = true; }
            else if(c==LF && prevCR) {
                if(key.isEmpty() && value.isEmpty()) break;
                if(sol) {
                    hdrs.add(key.toString(),value.toString());
                    break;
                }
                prevCR=false;
                sol=true;
            } else {
                if(sol && (c==' ' || c=='\t')) {
                    pbs.skipWhitespace();
                    inKey=false;
                    sol=false;
                } else {
                    if(sol) {
                        if(!key.isEmpty() || !value.isEmpty()) {
                            hdrs.add(key.toString(),value.toString());
                            key.clear();
                            value.clear();
                        }
                        inKey=true;
                        sol=false;
                    }
                    if(c==':' && inKey) {
                        inKey=false;
                        pbs.skipWhitespace();
                    } else {
                        (inKey ? key : value).append(c);
                    }
                }
            }
        }
        return hdrs;
    }
}
