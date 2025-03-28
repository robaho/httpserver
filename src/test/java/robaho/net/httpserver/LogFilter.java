package robaho.net.httpserver;
/*
 * Copyright (c) 2005, Oracle and/or its affiliates. All rights reserved.
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

import java.io.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.sun.net.httpserver.*;

public class LogFilter extends Filter {

    PrintStream ps;
    DateTimeFormatter df;

    public LogFilter(File file) throws IOException {
        ps = new PrintStream(new BufferedOutputStream(new FileOutputStream(file)));
        df = DateTimeFormatter.ISO_DATE_TIME;
    }

    /**
     * The filter's implementation, which is invoked by the serve r
     */
    public void doFilter(HttpExchange t, Filter.Chain chain) throws IOException {
        chain.doFilter(t);
        StringBuilder sb = new StringBuilder();
        df.formatTo(LocalDateTime.now(),sb);
        sb.append(" ");
        sb.append(t.getRequestMethod());
        sb.append(" ");
        sb.append(t.getRequestURI());
        sb.append(" ");
        sb.append(t.getResponseCode());
        sb.append(" ");
        sb.append(t.getRemoteAddress());
        ps.println(sb.toString());
    }

    public void init(HttpContext ctx) {
    }

    public String description() {
        return "Request logger";
    }

    public void destroy(HttpContext c) {
    }
}
