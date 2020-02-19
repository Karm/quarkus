package io.quarkus.vertx.http.compliance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.util.function.Supplier;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.Header;
import org.apache.http.client.CookieStore;
import org.apache.http.client.config.CookieSpecs;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.vertx.ext.web.Router;

public class HTTPCasesTest {

    @RegisterExtension
    static QuarkusUnitTest test = new QuarkusUnitTest().setArchiveProducer(new Supplier<JavaArchive>() {
        @Override
        public JavaArchive get() {
            return ShrinkWrap.create(JavaArchive.class)
                    .addClasses(MyLittleEndpoint.class, HTTPCasesTest.class);
        }
    });

    @ApplicationScoped
    public static class MyLittleEndpoint {
        public void register(@Observes Router router) {
            router.get("/").handler(rc -> rc.response().end("OK\n"));
            router.head("/").handler(rc -> rc.response().end("OK\n"));
        }
    }

    @TestHTTPResource
    URL url;

    @Test
    public void testContentLength() throws IOException, InterruptedException {

        final CookieStore cookieStore = new BasicCookieStore();

        try (CloseableHttpClient httpClient = HttpClientBuilder.create()
                .setDefaultCookieStore(cookieStore)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setCookieSpec(CookieSpecs.STANDARD).build())
                .build()) {

            HttpGet httpGET = new HttpGet(url.toString());
            int httpGETbytes;
            try (CloseableHttpResponse response = httpClient.execute(httpGET)) {
                assertEquals(200, response.getStatusLine().getStatusCode(),
                        "GET should have given us HTTP 200.");
                httpGETbytes = extractContentLength(response);
            }

            HttpHead httpHEAD = new HttpHead(url.toString());
            int httpHEADbytes;
            try (CloseableHttpResponse response = httpClient.execute(httpHEAD)) {
                assertEquals(200, response.getStatusLine().getStatusCode(),
                        "HEAD should have given us HTTP 200.");
                httpHEADbytes = extractContentLength(response);
            }

            assertEquals(httpGETbytes, httpHEADbytes,
                    "The HEAD method is identical to GET except that the server MUST NOT return a message-body in the response.");

        }
    }

    private int extractContentLength(CloseableHttpResponse response) {
        Header contentLengthHeader = response.getLastHeader("content-length");
        assertNotNull(contentLengthHeader, "content-length header was expected in the response");
        String contentLength = contentLengthHeader.getValue();
        assertTrue(StringUtils.isNotBlank(contentLength), "content-length header value expected in the response");
        return Integer.parseInt(contentLength.trim());
    }
}
