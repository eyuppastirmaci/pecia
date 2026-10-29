package dev.eyuppastirmaci.pecia.semantic;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Chooses the proxy for model downloads the way command-line tools usually do. JVM proxy properties ({@code
 * https.proxyHost}) win when set; otherwise {@code https_proxy} or {@code HTTPS_PROXY} names an {@code
 * http://host:port} proxy, and {@code no_proxy} or {@code NO_PROXY} lists hosts to reach directly. {@code HTTP_PROXY}
 * is ignored because model downloads use only HTTPS.
 */
final class ProxyEnvironment {

    private ProxyEnvironment() {}

    /**
     * Returns the proxy selector for the given environment and system properties.
     *
     * @throws IllegalArgumentException if the proxy variable is not an {@code http://host:port} URL without credentials
     */
    static ProxySelector selector(Map<String, String> environment, Function<String, String> properties) {
        String jvmProxy = properties.apply("https.proxyHost");

        if (jvmProxy != null && !jvmProxy.isEmpty()) {
            return ProxySelector.getDefault();
        }

        String variable = firstSet(environment, "https_proxy", "HTTPS_PROXY");

        if (variable == null) {
            return ProxySelector.getDefault();
        }

        InetSocketAddress proxy = parse(variable, environment.get(variable));
        String noProxy = firstSet(environment, "no_proxy", "NO_PROXY");
        List<String> directHosts = noProxy == null ? List.of() : directHosts(environment.get(noProxy));

        return new EnvironmentSelector(new Proxy(Proxy.Type.HTTP, proxy), directHosts);
    }

    /** Returns the first variable that is set to a non-empty value; lowercase names come first, as in curl. */
    private static String firstSet(Map<String, String> environment, String... names) {
        for (String name : names) {
            String value = environment.get(name);

            if (value != null && !value.isBlank()) {
                return name;
            }
        }

        return null;
    }

    private static InetSocketAddress parse(String variable, String value) {
        URI uri;

        try {
            uri = new URI(value.strip());
        } catch (URISyntaxException e) {
            throw invalid(variable, value, e);
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException(
                    variable + " must not contain credentials; authenticated proxies are" + " not supported");
        }

        String path = uri.getPath();

        if (!"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getPort() < 1
                || (path != null && !path.isEmpty() && !path.equals("/"))
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw invalid(variable, value, null);
        }

        // Unresolved, so the client resolves the proxy host itself when it connects.
        return InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort());
    }

    private static IllegalArgumentException invalid(String variable, String value, Throwable cause) {
        return new IllegalArgumentException(variable + " must look like http://host:port, got " + value, cause);
    }

    private static List<String> directHosts(String value) {
        return Arrays.stream(value.split(","))
                .map(entry -> entry.strip().toLowerCase(Locale.ROOT))
                .map(entry -> entry.startsWith(".") ? entry.substring(1) : entry)
                .filter(entry -> !entry.isEmpty())
                .toList();
    }

    /** Proxies every request except to hosts that match a direct entry exactly or as a domain suffix. */
    private static final class EnvironmentSelector extends ProxySelector {

        private final Proxy proxy;
        private final List<String> directHosts;

        EnvironmentSelector(Proxy proxy, List<String> directHosts) {
            this.proxy = proxy;
            this.directHosts = directHosts;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);

            return direct(host) ? List.of(Proxy.NO_PROXY) : List.of(proxy);
        }

        private boolean direct(String host) {
            return directHosts.stream()
                    .anyMatch(entry -> entry.equals("*") || host.equals(entry) || host.endsWith("." + entry));
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // Nothing to fall back to: the environment names a single proxy.
        }
    }
}
