package de.danoeh.antennapod.net.adremoval;

import android.app.Application;
import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.net.download.serviceinterface.FeedUpdateManager;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueue;
import de.danoeh.antennapod.net.sync.serviceinterface.SynchronizationQueueStub;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;
import de.danoeh.antennapod.storage.database.PodDBAdapter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Date;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 35)
public class ProcessedFeedSubscriptionTest {
    private static final String PROCESSED_URL = "https://server.example/clean-show?key=feed-access";
    private Context context;
    private Feed refreshed;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        UserPreferences.init(context);
        PodDBAdapter.init(context);
        PodDBAdapter.deleteDatabase();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        adapter.close();
        SynchronizationQueue.setInstance(new SynchronizationQueueStub());
        FeedUpdateManager.setInstance(new FeedUpdateManager() {
            @Override
            public void restartUpdateAlarm(Context context, boolean replace) {
            }

            @Override
            public void runOnce(Context context) {
            }

            @Override
            public void runOnce(Context context, Feed feed) {
            }

            @Override
            public void runOnce(Context context, Feed feed, boolean nextPage) {
            }

            @Override
            public void runOnceOrAsk(Context context) {
            }

            @Override
            public void runOnceOrAsk(Context context, Feed feed) {
                refreshed = feed;
            }
        });
    }

    private Feed source() {
        Feed source = new Feed(0, null, "Podcast", "https://publisher.example", "Description", null,
                "Author", "en", Feed.TYPE_RSS2, "publisher-guid", null, null,
                "https://publisher.example/feed", 0);
        source.setState(Feed.STATE_NOT_SUBSCRIBED);
        FeedItem item = new FeedItem();
        item.setTitle("Original episode");
        item.setPubDate(new Date());
        item.setItemIdentifier("original-episode");
        item.setFeed(source);
        source.getItems().add(item);
        return FeedDatabaseWriter.updateFeed(context, source, false);
    }

    @Test
    public void createsSeparateSubscriptionWithoutOriginalEpisodes() throws Exception {
        Feed source = source();
        Feed processed = ProcessedFeedSubscription.subscribe(context, source, PROCESSED_URL);
        assertNotEquals(source.getId(), processed.getId());
        assertEquals(PROCESSED_URL, processed.getDownloadUrl());
        assertEquals(Feed.STATE_SUBSCRIBED, processed.getState());
        assertTrue(processed.getItems().isEmpty());
        assertTrue(processed.getPreferences().getKeepUpdated());
        assertEquals(processed.getId(), refreshed.getId());
        Feed original = DBReader.getFeed(source.getId(), false, 0, Integer.MAX_VALUE);
        assertEquals(Feed.STATE_NOT_SUBSCRIBED, original.getState());
        assertEquals(1, original.getItems().size());
    }

    @Test
    public void repeatedSubscriptionKeepsExistingFeedAndEpisodes() throws Exception {
        Feed source = source();
        Feed first = ProcessedFeedSubscription.subscribe(context, source, PROCESSED_URL);
        FeedItem episode = new FeedItem();
        episode.setTitle("Processed episode");
        episode.setPubDate(new Date());
        episode.setItemIdentifier("processed-episode");
        episode.setFeed(first);
        first.getItems().add(episode);
        FeedDatabaseWriter.updateFeed(context, first, false);
        Feed second = ProcessedFeedSubscription.subscribe(context, source, PROCESSED_URL);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, DBReader.getFeed(second.getId(), false, 0, Integer.MAX_VALUE).getItems().size());
        assertEquals(2, DBReader.getFeedList().size());
    }
}
