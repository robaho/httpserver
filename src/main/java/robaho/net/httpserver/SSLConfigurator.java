package robaho.net.httpserver;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;

class SSLConfigurator {
    static void configure(SSLSocket s,HttpsConfigurator cfg) {
        s.setUseClientMode(false);
        if(cfg==null) return;
        InetSocketAddress remoteAddress = (InetSocketAddress)s.getRemoteSocketAddress();
        Parameters params = new Parameters (cfg, remoteAddress);
        cfg.configure(params);
        SSLParameters sslParams = params.getSSLParameters();
        if (sslParams != null) {
            s.setSSLParameters(sslParams);
        }
    }
    static class Parameters extends HttpsParameters {
        InetSocketAddress addr;
        HttpsConfigurator cfg;

        Parameters (HttpsConfigurator cfg, InetSocketAddress addr) {
            this.addr = addr;
            this.cfg = cfg;
        }
        @Override
        public InetSocketAddress getClientAddress () {
            return addr;
        }
        @Override
        public HttpsConfigurator getHttpsConfigurator() {
            return cfg;
        }
        SSLParameters params;
        @Override
        public void setSSLParameters (SSLParameters p) {
            params = p;
        }
        SSLParameters getSSLParameters () {
            return params;
        }
    }
}