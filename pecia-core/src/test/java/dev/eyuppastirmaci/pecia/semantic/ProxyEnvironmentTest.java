package dev.eyuppastirmaci.pecia.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ProxyEnvironmentTest {

    private static final Function<String, String> NO_PROPERTIES = name -> null;
    private static final URI MODEL = URI.create("https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2");

    @Test
    void withoutProxyVariablesTheJvmDefaultApplies() {
        assertSame(ProxySelector.getDefault(), ProxyEnvironment.selector(Map.of(), NO_PROPERTIES));
    }

    @Test
    void jvmProxyPropertiesWinOverTheEnvironment() {
        ProxySelector selector = ProxyEnvironment.selector(
                Map.of("HTTPS_PROXY", "http://env-proxy:8080"),
                name -> name.equals("https.proxyHost") ? "jvm-proxy" : null);

        assertSame(ProxySelector.getDefault(), selector);
    }

    @Test
    void httpsProxyRoutesRequestsThroughAnHttpProxy() {
        Proxy proxy = only(ProxyEnvironment.selector(Map.of("HTTPS_PROXY", "http://proxy.corp:3128"), NO_PROPERTIES));

        assertEquals(Proxy.Type.HTTP, proxy.type());
        assertEquals(InetSocketAddress.createUnresolved("proxy.corp", 3128), proxy.address());
    }

    @Test
    void lowercaseHttpsProxyComesFirst() {
        Proxy proxy = only(ProxyEnvironment.selector(
                Map.of("https_proxy", "http://lower:1", "HTTPS_PROXY", "http://upper:2"), NO_PROPERTIES));

        assertEquals(InetSocketAddress.createUnresolved("lower", 1), proxy.address());
    }

    @Test
    void blankLowercaseValueFallsBackToUppercase() {
        Proxy proxy = only(
                ProxyEnvironment.selector(Map.of("https_proxy", " ", "HTTPS_PROXY", "http://upper:2/"), NO_PROPERTIES));

        assertEquals(InetSocketAddress.createUnresolved("upper", 2), proxy.address());
    }

    @Test
    void httpProxyIsIgnoredBecauseDownloadsUseHttps() {
        assertSame(
                ProxySelector.getDefault(),
                ProxyEnvironment.selector(Map.of("HTTP_PROXY", "http://plain:80"), NO_PROPERTIES));
    }

    @ParameterizedTest(name = "NO_PROXY={0}, host={1} -> direct {2}")
    @CsvSource(
            delimiter = '|',
            value = {
                "huggingface.co|huggingface.co|true",
                ".huggingface.co|huggingface.co|true",
                "hf.co|us.aws.cdn.hf.co|true",
                ".hf.co|us.aws.cdn.hf.co|true",
                "HF.CO|us.aws.cdn.hf.co|true",
                "huggingface.co|nothuggingface.co|false",
                "huggingface.co|us.aws.cdn.hf.co|false",
                " , example.test ,, hf.co |us.aws.cdn.hf.co|true",
                "*|huggingface.co|true"
            })
    void noProxyListsHostsReachedDirectly(String noProxy, String host, boolean direct) {
        ProxySelector selector = ProxyEnvironment.selector(
                Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "NO_PROXY", noProxy), NO_PROPERTIES);

        assertEquals(
                direct ? List.of(Proxy.NO_PROXY) : List.of(proxyAt("proxy.corp", 3128)),
                selector.select(URI.create("https://" + host + "/model.onnx")));
    }

    @Test
    void lowercaseNoProxyComesFirst() {
        ProxySelector selector = ProxyEnvironment.selector(
                Map.of("HTTPS_PROXY", "http://proxy.corp:3128", "no_proxy", "huggingface.co", "NO_PROXY", "other"),
                NO_PROPERTIES);

        assertEquals(List.of(Proxy.NO_PROXY), selector.select(MODEL));
    }

    @Test
    void credentialsAreRejectedWithoutEchoingThem() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProxyEnvironment.selector(Map.of("HTTPS_PROXY", "http://user:secret@proxy:8080"), NO_PROPERTIES));

        assertEquals(
                "HTTPS_PROXY must not contain credentials; authenticated proxies are not supported",
                failure.getMessage());
        assertFalse(failure.getMessage().contains("secret"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://proxy:8443",
                "socks5://proxy:1080",
                "http://proxy",
                "proxy:8080",
                "proxy",
                "http://proxy:8080/path",
                "http://proxy:8080?x=1",
                "http://proxy:8080#x",
                "http://:8080",
                "http:// bad host:8080"
            })
    void otherShapesAreRejectedWithTheVariableName(String value) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> ProxyEnvironment.selector(Map.of("https_proxy", value), NO_PROPERTIES));

        assertEquals("https_proxy must look like http://host:port, got " + value, failure.getMessage());
    }

    private static Proxy only(ProxySelector selector) {
        List<Proxy> proxies = selector.select(MODEL);
        assertEquals(1, proxies.size());

        return proxies.getFirst();
    }

    private static Proxy proxyAt(String host, int port) {
        return new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port));
    }
}
