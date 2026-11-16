package de.danoeh.antennapod.net.adremoval;

import android.content.Context;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.FeedDatabaseWriter;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

public class ProcessedFeedSubscription {
    public static synchronized Feed subscribe(Context context, Feed source, String processedUrl)
            throws IOException, ExecutionException, InterruptedException {
        Feed processed = null;
        for (Feed existing : DBReader.getFeedList()) {
            if (processedUrl.equals(existing.getDownloadUrl())) {
                processed = existing;
                break;
            }
        }
        if (processed == null) {
            Feed pending = new Feed(0, null, source.getTitle(), source.getLink(), source.getDescription(), null,
                    source.getAuthor(), source.getLanguage(), source.getType(), processedUrl,
                    source.getImageUrl(), null, processedUrl, 0);
            pending.setState(Feed.STATE_NOT_SUBSCRIBED);
            processed = FeedDatabaseWriter.updateFeed(context, pending, false);
        }
        if (processed == null || processed.getId() == 0) {
            throw new IOException();
        }
        if (processed.getState() != Feed.STATE_SUBSCRIBED) {
            DBWriter.setFeedState(context, processed, Feed.STATE_SUBSCRIBED).get();
        }
        return processed;
    }
}
