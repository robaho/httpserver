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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

final class ContextList {

    private final List<HttpContextImpl> list = new CopyOnWriteArrayList<>();
    private final Map<CacheKey,HttpContextImpl> cache = new ConcurrentHashMap<>();
    private record CacheKey(String protocol,String path){}

    synchronized void add(HttpContextImpl ctx) {
        assert ctx.getPath() != null;
        if (contains(ctx)) {
            throw new IllegalArgumentException("cannot add context to list");
        }
        list.add(ctx);
    }

    boolean contains(HttpContextImpl ctx) {
        return findContext(ctx.getProtocol(), ctx.getPath(), true) != null;
    }

    int size() {
        return list.size();
    }

    /*
     * initially contexts are located only by protocol:path.
     * Context with longest prefix matches (currently case-sensitive)
     */
    HttpContextImpl findContext(String protocol, String path) {
        CacheKey key = new CacheKey(protocol,path);
        var ctx = cache.get(key);
        if(ctx!=null) return ctx;
        ctx = findContext(protocol, path, false);
        // only cache exact matches, otherwise path parameters will 
        // explode memory
        if(ctx!=null && ctx.getPath().equals(path)) {
            cache.put(key,ctx);
        }
        return ctx;
    }

    HttpContextImpl findContext(String protocol, String path, boolean exact) {
        String _protocol = protocol.toLowerCase(Locale.ROOT);
        String longest = "";
        HttpContextImpl lc = null;
        for (HttpContextImpl ctx : list) {
            if (!ctx.getProtocol().equals(_protocol)) {
                continue;
            }
            String cpath = ctx.getPath();
            if (exact && !cpath.equals(path)) {
                continue;
            } else if (!exact && !path.startsWith(cpath)) {
                continue;
            }
            if (cpath.length() > longest.length()) {
                longest = cpath;
                lc = ctx;
            }
        }
        return lc;
    }

    synchronized void remove(String protocol, String path)
            throws IllegalArgumentException {
        HttpContextImpl ctx = findContext(protocol, path, true);
        if (ctx == null) {
            throw new IllegalArgumentException("cannot remove element from list");
        }
        list.remove(ctx);
    }

    synchronized void remove(HttpContextImpl context)
            throws IllegalArgumentException {
        for (HttpContextImpl ctx : list) {
            if (ctx.equals(context)) {
                list.remove(ctx);
                return;
            }
        }
        throw new IllegalArgumentException("no such context in list");
    }
}
