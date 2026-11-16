package de.danoeh.antennapod.net.adremoval;

import android.app.Application;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class MinusPodClientTest {
    private static final String SOURCE = "https://publisher.example/podcast.xml";
    private MockWebServer server;
    private MinusPodClient client;
    private OkHttpClient httpClient;
    private String processed;

    @Before
    public void setUp() throws Exception {
        HeldCertificate certificate = new HeldCertificate.Builder().addSubjectAlternativeName("localhost").build();
        HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder().heldCertificate(certificate).build();
        HandshakeCertificates clientCertificates = new HandshakeCertificates.Builder()
                .addTrustedCertificate(certificate.certificate()).build();
        server = new MockWebServer();
        server.useHttps(serverCertificates.sslSocketFactory(), false);
        server.start();
        httpClient = new OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager()).build();
        client = new MinusPodClient(server.url("/podcasts").toString(), httpClient);
        processed = server.url("/podcasts/clean-show?key=feed-access").toString();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
        httpClient.connectionPool().evictAll();
        httpClient.dispatcher().executorService().shutdown();
    }

    private void reply(int status, String body) {
        server.enqueue(new MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    private String feed() throws Exception {
        return new JSONObject().put("slug", "clean-show").put("sourceUrl", SOURCE)
                .put("feedUrl", processed).toString();
    }

    private RecordedRequest takeRequest() throws Exception {
        RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    @Test
    public void createsProcessedFeedUnderServerPrefix() throws Exception {
        reply(200, "{\"feeds\":[]}");
        reply(201, feed());
        assertEquals(processed, client.subscribe(SOURCE, null));
        assertEquals("/podcasts/api/v1/feeds", takeRequest().getPath());
        RecordedRequest create = takeRequest();
        assertEquals("POST", create.getMethod());
        JSONObject body = new JSONObject(create.getBody().readUtf8());
        assertEquals(SOURCE, body.getString("sourceUrl"));
        assertTrue(body.getBoolean("autoProcessOverride"));
        assertTrue(body.getBoolean("onlyExposeProcessedEpisodes"));
        assertTrue(body.getString("slug").startsWith("antennapod-"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void reusesExistingFeedAndEnablesProcessedEpisodesOnly() throws Exception {
        reply(200, "{\"feeds\":[" + feed() + "]}");
        reply(200, "{}");
        assertEquals(processed, client.subscribe(SOURCE, null));
        takeRequest();
        RecordedRequest update = takeRequest();
        assertEquals("PATCH", update.getMethod());
        assertEquals("/podcasts/api/v1/feeds/clean-show", update.getPath());
        assertTrue(new JSONObject(update.getBody().readUtf8()).getBoolean("onlyExposeProcessedEpisodes"));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    public void loginUsesSessionAndCsrfWithoutSendingPasswordInUrl() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"authenticated\":true}")
                .addHeader("Set-Cookie", "session=test-session; Path=/podcasts/; Secure; HttpOnly")
                .addHeader("Set-Cookie", "minuspod_csrf=test-csrf; Path=/podcasts/; Secure"));
        reply(200, "{\"feeds\":[]}");
        reply(201, feed());
        assertEquals(processed, client.subscribe(SOURCE, "test-password"));
        RecordedRequest login = takeRequest();
        assertEquals("/podcasts/api/v1/auth/login", login.getPath());
        assertEquals("test-password", new JSONObject(login.getBody().readUtf8()).getString("password"));
        assertNull(login.getHeader("Cookie"));
        RecordedRequest list = takeRequest();
        assertTrue(list.getHeader("Cookie").contains("session=test-session"));
        RecordedRequest create = takeRequest();
        assertEquals("test-csrf", create.getHeader("X-CSRF-Token"));
        assertFalse(create.getBody().readUtf8().contains("test-password"));
    }

    @Test
    public void unauthorizedDoesNotCreateOrAutomaticallyRetry() throws Exception {
        reply(401, "{}");
        MinusPodClient.ServerException error = assertThrows(MinusPodClient.ServerException.class,
                () -> client.subscribe(SOURCE, null));
        assertEquals(401, error.status);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void failedLoginIsNotRetried() throws Exception {
        reply(401, "{}");
        assertThrows(MinusPodClient.ServerException.class, () -> client.subscribe(SOURCE, "incorrect"));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void conflictReconcilesExistingFeedWithoutAnotherCreate() throws Exception {
        reply(200, "{\"feeds\":[]}");
        reply(409, "{}");
        reply(200, "{\"feeds\":[" + feed() + "]}");
        reply(200, "{}");
        assertEquals(processed, client.subscribe(SOURCE, null));
        assertEquals("GET", takeRequest().getMethod());
        assertEquals("POST", takeRequest().getMethod());
        assertEquals("GET", takeRequest().getMethod());
        assertEquals("PATCH", takeRequest().getMethod());
        assertEquals(4, server.getRequestCount());
    }

    @Test
    public void rejectsForeignProcessedFeed() throws Exception {
        reply(200, "{\"feeds\":[]}");
        reply(201, "{\"feedUrl\":\"https://unexpected.example/feed\"}");
        assertThrows(IOException.class, () -> client.subscribe(SOURCE, null));
    }

    @Test
    public void doesNotFollowApiRedirects() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", "https://unexpected.example/"));
        assertThrows(MinusPodClient.ServerException.class, () -> client.subscribe(SOURCE, null));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void rejectsMalformedResponse() throws Exception {
        reply(200, "not-json");
        assertThrows(IOException.class, () -> client.subscribe(SOURCE, null));
        assertEquals(1, server.getRequestCount());
    }

    @Test
    public void rejectsUnsafeSettings() {
        for (String value : new String[]{"http://example.com", "https://user:pass@example.com",
                "https://example.com?token=secret", "https://example.com#fragment", "not-a-url"}) {
            assertThrows(IllegalArgumentException.class, () -> MinusPodClient.parseServerUrl(value));
        }
        assertEquals("https://example.com/prefix/", MinusPodClient.parseServerUrl(" https://example.com/prefix ").toString());
    }
}
