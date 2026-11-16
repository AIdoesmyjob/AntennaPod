# Subscribe through an ad-removal server

This fork adds an optional MinusPod integration. Podcast search and discovery remain
unchanged. Under **Settings → Downloads → Ad-removal server**, enter your MinusPod
HTTPS base URL, for example `https://podcasts.example.com` (a path prefix also works).
Leave the setting empty to restore the original subscription flow.

When previewing a podcast, tap **Subscribe**, then choose **Remove ads via server**
or **Original feed**. The server option sends the RSS address to MinusPod, enables
automatic processing and processed-only episode publishing for that feed, and
subscribes the app to the feed URL returned by the server. Existing matching server
feeds are reused. These processing options also apply to existing server feeds.
The server's detection and removal rules still determine which segments are removed.

A server subscription initially has the podcast's title and artwork but no episodes.
Episodes appear on feed refresh as the server publishes them. The app never copies
original episode downloads into the processed subscription. Queueing, downloading,
and playback then use AntennaPod's normal behavior. The source preview remains an
unsubscribed preview; this feature does not migrate existing subscriptions or history.

## Server requirements

- Deploy and configure MinusPod separately, including its transcription and ad
  detection providers. This PR does not deploy an ad-removal server.
- Use HTTPS reachable from the phone, with a certificate the phone trusts.
- Configure MinusPod's `BASE_URL` to return feed URLs on that same public origin.
  Feed and audio access must work without an administrative login; MinusPod's feed
  access keys in returned URLs are supported.
- The integration uses `/api/v1/feeds` (GET/POST), `/api/v1/feeds/{slug}` (PATCH), and
  `/api/v1/auth/login` (POST), with the current MinusPod JSON field names.
- If MinusPod requires a password, the app asks for it for this subscription. The
  password and session cookies stay in memory for the operation. The app stores only
  the server setting and the returned feed URL, including a feed access key if present.
  Treat exported feed URLs as private.
- Feeds requiring separate publisher credentials are not supported by this integration.
  The app does not forward saved publisher usernames or passwords to the server.

## Failures and retries

An unsuccessful server request displays an error and never falls back to subscribing
to the original feed. Requests do not automatically retry writes or follow API
redirects. A timeout may leave a feed registered on the server; another deliberate
subscription attempt looks for that feed before creating one. Conflicting creations
are resolved by a read and a source-URL match, never by adopting an unrelated feed.

## Validation

`MinusPodClientTest` exercises the integration against a local HTTPS test server,
including login cookies and CSRF, duplicate subscriptions, conflict reconciliation,
redirect rejection, malformed responses, and unsafe settings.
`ProcessedFeedSubscriptionTest` verifies database separation from the source preview,
automatic feed refresh, and reusing an existing processed subscription without losing
episodes. These tests do not measure ad-detection accuracy or validate a deployed
MinusPod instance.
