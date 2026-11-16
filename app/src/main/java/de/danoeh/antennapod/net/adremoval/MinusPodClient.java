package de.danoeh.antennapod.net.adremoval;

import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class MinusPodClient {
    public static final String PREF_SERVER_URL = "prefAdRemovalServerUrl";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final HttpUrl server;
    private final OkHttpClient client;
    private final List<Cookie> cookies = new ArrayList<>();

    public MinusPodClient(String serverUrl, OkHttpClient baseClient) {
        server = parseServerUrl(serverUrl);
        client = baseClient.newBuilder()
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .callTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .cookieJar(new CookieJar() {
                    @Override
                    public void saveFromResponse(HttpUrl url, List<Cookie> received) {
                        for (Cookie cookie : received) {
                            Iterator<Cookie> iterator = cookies.iterator();
                            while (iterator.hasNext()) {
                                Cookie old = iterator.next();
                                if (old.name().equals(cookie.name()) && old.domain().equals(cookie.domain())
                                        && old.path().equals(cookie.path())) {
                                    iterator.remove();
                                }
                            }
                            cookies.add(cookie);
                        }
                    }

                    @Override
                    public List<Cookie> loadForRequest(HttpUrl url) {
                        List<Cookie> matching = new ArrayList<>();
                        for (Cookie cookie : cookies) {
                            if (cookie.matches(url) && cookie.expiresAt() > System.currentTimeMillis()) {
                                matching.add(cookie);
                            }
                        }
                        return matching;
                    }
                }).build();
    }

    public static HttpUrl parseServerUrl(String value) {
        HttpUrl url = HttpUrl.parse(value.trim());
        if (url == null || !url.isHttps() || !url.username().isEmpty() || !url.password().isEmpty()
                || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException();
        }
        if (!url.encodedPath().endsWith("/")) {
            url = url.newBuilder().addPathSegment("").build();
        }
        return url;
    }

    public String subscribe(String sourceUrl, String password) throws IOException {
        try {
            if (password != null) {
                request("POST", "auth/login", new JSONObject().put("password", password));
            }
            JSONObject existing = findFeed(sourceUrl);
            if (existing != null) {
                return prepareExisting(existing);
            }
            JSONObject body = processingOptions().put("sourceUrl", sourceUrl)
                    .put("slug", "antennapod-" + UUID.nameUUIDFromBytes(
                            sourceUrl.getBytes(StandardCharsets.UTF_8)));
            JSONObject created;
            try {
                created = request("POST", "feeds", body);
            } catch (ServerException error) {
                if (error.status != 409) {
                    throw error;
                }
                existing = findFeed(sourceUrl);
                if (existing == null) {
                    throw error;
                }
                return prepareExisting(existing);
            }
            return processedUrl(created);
        } catch (JSONException | IllegalArgumentException error) {
            throw new IOException();
        }
    }

    private JSONObject processingOptions() throws JSONException {
        return new JSONObject().put("autoProcessOverride", true).put("onlyExposeProcessedEpisodes", true);
    }

    private JSONObject findFeed(String sourceUrl) throws IOException, JSONException {
        JSONArray feeds = request("GET", "feeds", null).getJSONArray("feeds");
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject feed = feeds.getJSONObject(i);
            if (sourceUrl.equals(feed.optString("sourceUrl")) || sourceUrl.equals(feed.optString("feedUrl"))) {
                return feed;
            }
        }
        return null;
    }

    private String prepareExisting(JSONObject feed) throws IOException, JSONException {
        String url = processedUrl(feed);
        String slug = feed.getString("slug");
        if (!slug.matches("[a-z0-9][a-z0-9-]{0,199}")) {
            throw new IOException();
        }
        request("PATCH", "feeds/" + slug, processingOptions());
        return url;
    }

    private String processedUrl(JSONObject feed) throws IOException, JSONException {
        HttpUrl url = server.resolve(feed.getString("feedUrl"));
        if (url == null || !url.isHttps() || !url.host().equals(server.host()) || url.port() != server.port()
                || !url.username().isEmpty() || !url.password().isEmpty() || url.fragment() != null) {
            throw new IOException();
        }
        return url.toString();
    }

    private JSONObject request(String method, String path, JSONObject body) throws IOException, JSONException {
        HttpUrl url = server.resolve("api/v1/" + path);
        Request.Builder builder = new Request.Builder().url(url).header("Accept", "application/json");
        for (Cookie cookie : client.cookieJar().loadForRequest(url)) {
            if (cookie.name().equals("minuspod_csrf")) {
                builder.header("X-CSRF-Token", cookie.value());
            }
        }
        builder.method(method, body == null ? null : RequestBody.create(body.toString(), JSON));
        try (Response response = client.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new ServerException(response.code());
            }
            if (response.body() == null) {
                throw new IOException();
            }
            return new JSONObject(response.body().string());
        }
    }

    public static class ServerException extends IOException {
        public final int status;

        public ServerException(int status) {
            this.status = status;
        }
    }
}
