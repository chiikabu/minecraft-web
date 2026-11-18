// minecraft-web by chiikabu (https://github.com/chiikabu)

package org.teavm.classlib.java.net;

import java.net.InetAddress;
import java.net.PasswordAuthentication;
import java.net.URL;

public abstract class TAuthenticator {
    public enum RequestorType { PROXY, SERVER }
    private static volatile TAuthenticator defaultAuthenticator;
    private String host;
    private InetAddress site;
    private int port;
    private String protocol;
    private String prompt;
    private String scheme;
    private URL url;
    private RequestorType requestorType;

    public TAuthenticator() { }
    public static synchronized void setDefault(TAuthenticator authenticator) { defaultAuthenticator = authenticator; }
    public static TAuthenticator getDefault() { return defaultAuthenticator; }

    public static PasswordAuthentication requestPasswordAuthentication(InetAddress site, int port,
            String protocol, String prompt, String scheme) {
        return requestPasswordAuthentication(null, site, port, protocol, prompt, scheme);
    }

    public static PasswordAuthentication requestPasswordAuthentication(String host, InetAddress site,
            int port, String protocol, String prompt, String scheme) {
        return requestPasswordAuthentication(host, site, port, protocol, prompt, scheme, null, RequestorType.SERVER);
    }

    public static PasswordAuthentication requestPasswordAuthentication(String host, InetAddress site,
            int port, String protocol, String prompt, String scheme, URL url, RequestorType type) {
        TAuthenticator selected = defaultAuthenticator;
        return selected == null ? null : selected.request(host, site, port, protocol, prompt, scheme, url, type);
    }

    public static PasswordAuthentication requestPasswordAuthentication(TAuthenticator authenticator,
            String host, InetAddress site, int port, String protocol, String prompt, String scheme,
            URL url, RequestorType type) {
        TAuthenticator selected = authenticator == null ? defaultAuthenticator : authenticator;
        return selected == null ? null : selected.requestPasswordAuthenticationInstance(
                host, site, port, protocol, prompt, scheme, url, type);
    }

    public PasswordAuthentication requestPasswordAuthenticationInstance(String host, InetAddress site,
            int port, String protocol, String prompt, String scheme, URL url, RequestorType type) {
        return request(host, site, port, protocol, prompt, scheme, url, type);
    }

    private synchronized PasswordAuthentication request(String host, InetAddress site, int port,
            String protocol, String prompt, String scheme, URL url, RequestorType type) {
        this.host = host;
        this.site = site;
        this.port = port;
        this.protocol = protocol;
        this.prompt = prompt;
        this.scheme = scheme;
        this.url = url;
        this.requestorType = type;
        return getPasswordAuthentication();
    }

    protected final String getRequestingHost() { return host; }
    protected final InetAddress getRequestingSite() { return site; }
    protected final int getRequestingPort() { return port; }
    protected final String getRequestingProtocol() { return protocol; }
    protected final String getRequestingPrompt() { return prompt; }
    protected final String getRequestingScheme() { return scheme; }
    protected URL getRequestingURL() { return url; }
    protected RequestorType getRequestorType() { return requestorType; }
    protected PasswordAuthentication getPasswordAuthentication() { return null; }
}
