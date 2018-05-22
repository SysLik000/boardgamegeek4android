package com.boardgamegeek.model.persister;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import com.boardgamegeek.entities.CollectionItemEntity;
import com.boardgamegeek.provider.BggContract;
import com.boardgamegeek.provider.BggContract.Collection;
import com.boardgamegeek.provider.BggContract.Games;
import com.boardgamegeek.provider.BggContract.Thumbnails;
import com.boardgamegeek.util.CursorUtils;
import com.boardgamegeek.util.FileUtils;
import com.boardgamegeek.util.PreferencesUtils;
import com.boardgamegeek.util.ResolverUtils;
import com.boardgamegeek.util.SelectionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import hugo.weaving.DebugLog;
import timber.log.Timber;

public class CollectionPersister {
	private static final int NOT_DIRTY = 0;
	private final ContentResolver resolver;
	private long timestamp;
	private final List<String> statusesToSync;

	public static class Builder {
		private final Context context;
		private boolean validStatusesOnly;

		@DebugLog
		public Builder(Context context) {
			this.context = context;
		}

		@DebugLog
		public Builder validStatusesOnly() {
			validStatusesOnly = true;
			return this;
		}

		@DebugLog
		public CollectionPersister build() {
			List<String> statuses = null;
			if (validStatusesOnly) {
				statuses = new ArrayList<>();
				final Set<String> syncStatuses = PreferencesUtils.getSyncStatuses(context);
				if (syncStatuses != null) {
					statuses.addAll(syncStatuses);
				}
			}
			return new CollectionPersister(context, statuses);
		}
	}

	@DebugLog
	private CollectionPersister(Context context, List<String> statusesToSync) {
		this.statusesToSync = statusesToSync;
		resolver = context.getContentResolver();
		timestamp = System.currentTimeMillis();
	}

	public long getTimestamp() {
		return timestamp;
	}

	public void resetTimestamp() {
		timestamp = System.currentTimeMillis();
	}

	/**
	 * Remove all collection items belonging to a game, except the ones in the specified list.
	 *
	 * @param gameId                 delete collection items with this game ID.
	 * @param protectedCollectionIds list of collection IDs not to delete.
	 * @return the number or rows deleted.
	 */
	@DebugLog
	public int delete(int gameId, @Nullable List<Integer> protectedCollectionIds) {
		// determine the collection IDs that are no longer in the collection
		List<Integer> collectionIdsToDelete = ResolverUtils.queryInts(resolver,
			Collection.CONTENT_URI,
			Collection.COLLECTION_ID,
			String.format("collection.%s=?", Collection.GAME_ID),
			new String[] { String.valueOf(gameId) });
		if (protectedCollectionIds != null) {
			collectionIdsToDelete.removeAll(protectedCollectionIds);
		}
		// remove them
		if (collectionIdsToDelete.size() > 0) {
			for (Integer collectionId : collectionIdsToDelete) {
				resolver.delete(Collection.CONTENT_URI,
					Collection.COLLECTION_ID + "=?",
					new String[] { String.valueOf(collectionId) });
			}
		}

		return collectionIdsToDelete.size();
	}

	public int saveItem(CollectionItemEntity item, boolean includeStats, boolean includePrivateInfo, boolean isBrief) {
		if (isItemStatusSetToSync(item)) {
			SyncCandidate candidate = SyncCandidate.find(resolver, item.getCollectionId(), item.getGameId());
			if (candidate.getDirtyTimestamp() != NOT_DIRTY) {
				Timber.i("Local copy of the collection item is dirty, skipping sync.");
			} else {
				upsertGame(item, includeStats, isBrief);
				upsertItem(item, candidate, includeStats, includePrivateInfo, isBrief);
				Timber.i("Saved collection item '%s' [ID=%s, collection ID=%s]", item.getGameName(), item.getGameId(), item.getCollectionId());
				return item.getCollectionId();
			}
		} else {
			Timber.i("Skipped collection item '%s' [ID=%s, collection ID=%s] - collection status not synced", item.getGameName(), item.getGameId(), item.getCollectionId());
		}
		return BggContract.INVALID_ID;
	}

	@DebugLog
	private boolean isItemStatusSetToSync(CollectionItemEntity item) {
		if (statusesToSync == null) return true; // null means we should always sync
		if (isStatusSetToSync(item.getOwn(), "own")) return true;
		if (isStatusSetToSync(item.getPreviouslyOwned(), "prevowned")) return true;
		if (isStatusSetToSync(item.getForTrade(), "fortrade")) return true;
		if (isStatusSetToSync(item.getWant(), "want")) return true;
		if (isStatusSetToSync(item.getWantToPlay(), "wanttoplay")) return true;
		if (isStatusSetToSync(item.getWantToBuy(), "wanttobuy")) return true;
		if (isStatusSetToSync(item.getWishList(), "wishlist")) return true;
		if (isStatusSetToSync(item.getPreOrdered(), "preordered")) return true;
		//noinspection RedundantIfStatement
		if (item.getNumberOfPlays() > 0 && statusesToSync.contains("played")) return true;
		return false;
	}

	private boolean isStatusSetToSync(boolean status, String setting) {
		return status && statusesToSync.contains(setting);
	}

	@DebugLog
	private ContentValues toGameValues(CollectionItemEntity item, boolean includeStats, boolean isBrief) {
		ContentValues values = new ContentValues();
		values.put(Games.UPDATED_LIST, timestamp);
		values.put(Games.GAME_ID, item.getGameId());
		values.put(Games.GAME_NAME, item.getGameName());
		values.put(Games.GAME_SORT_NAME, item.getSortName());
		if (!isBrief) {
			values.put(Games.NUM_PLAYS, item.getNumberOfPlays());
		}
		if (includeStats) {
			values.put(Games.MIN_PLAYERS, item.getMinNumberOfPlayers());
			values.put(Games.MAX_PLAYERS, item.getMaxNumberOfPlayers());
			values.put(Games.PLAYING_TIME, item.getPlayingTime());
			values.put(Games.MIN_PLAYING_TIME, item.getMinPlayingTime());
			values.put(Games.MAX_PLAYING_TIME, item.getMaxPlayingTime());
			values.put(Games.STATS_NUMBER_OWNED, item.getNumberOwned());
		}
		return values;
	}

	@DebugLog
	private ContentValues toCollectionValues(CollectionItemEntity item, boolean includeStats, boolean includePrivateInfo, boolean isBrief) {
		ContentValues values = new ContentValues();
		if (!isBrief && includePrivateInfo && includeStats) {
			values.put(Collection.UPDATED, timestamp);
		}
		values.put(Collection.UPDATED_LIST, timestamp);
		values.put(Collection.GAME_ID, item.getGameId());
		if (item.getCollectionId() != BggContract.INVALID_ID) {
			values.put(Collection.COLLECTION_ID, item.getCollectionId());
		}
		values.put(Collection.COLLECTION_NAME, item.getCollectionName());
		values.put(Collection.COLLECTION_SORT_NAME, item.getSortName());
		values.put(Collection.STATUS_OWN, item.getOwn());
		values.put(Collection.STATUS_PREVIOUSLY_OWNED, item.getPreviouslyOwned());
		values.put(Collection.STATUS_FOR_TRADE, item.getForTrade());
		values.put(Collection.STATUS_WANT, item.getWant());
		values.put(Collection.STATUS_WANT_TO_PLAY, item.getWantToPlay());
		values.put(Collection.STATUS_WANT_TO_BUY, item.getWantToBuy());
		values.put(Collection.STATUS_WISHLIST, item.getWishList());
		values.put(Collection.STATUS_WISHLIST_PRIORITY, item.getWishListPriority());
		values.put(Collection.STATUS_PREORDERED, item.getPreOrdered());
		values.put(Collection.LAST_MODIFIED, item.getLastModifiedDate());
		if (!isBrief) {
			values.put(Collection.COLLECTION_YEAR_PUBLISHED, item.getYearPublished());
			values.put(Collection.COLLECTION_IMAGE_URL, item.getImageUrl());
			values.put(Collection.COLLECTION_THUMBNAIL_URL, item.getThumbnailUrl());
			values.put(Collection.COMMENT, item.getComment());
			values.put(Collection.CONDITION, item.getConditionText());
			values.put(Collection.WANTPARTS_LIST, item.getWantPartsList());
			values.put(Collection.HASPARTS_LIST, item.getHasPartsList());
			values.put(Collection.WISHLIST_COMMENT, item.getWishListComment());
			if (includePrivateInfo) {
				values.put(Collection.PRIVATE_INFO_PRICE_PAID_CURRENCY, item.getPricePaidCurrency());
				values.put(Collection.PRIVATE_INFO_PRICE_PAID, item.getPricePaid());
				values.put(Collection.PRIVATE_INFO_CURRENT_VALUE_CURRENCY, item.getCurrentValueCurrency());
				values.put(Collection.PRIVATE_INFO_CURRENT_VALUE, item.getCurrentValue());
				values.put(Collection.PRIVATE_INFO_QUANTITY, item.getQuantity());
				values.put(Collection.PRIVATE_INFO_ACQUISITION_DATE, item.getAcquisitionDate());
				values.put(Collection.PRIVATE_INFO_ACQUIRED_FROM, item.getAcquiredFrom());
				values.put(Collection.PRIVATE_INFO_COMMENT, item.getPrivateComment());
			}
		}
		if (includeStats) {
			values.put(Collection.RATING, item.getRating());
		}
		return values;
	}

	@DebugLog
	private void upsertGame(CollectionItemEntity item, boolean includeStats, boolean isBrief) {
		ContentValues values = toGameValues(item, includeStats, isBrief);
		Uri uri = Games.buildGameUri(item.getGameId());
		if (ResolverUtils.rowExists(resolver, uri)) {
			values.remove(Games.GAME_ID);
			resolver.update(uri, values, null, null);
		} else {
			resolver.insert(Games.CONTENT_URI, values);
		}
	}

	@DebugLog
	private void upsertItem(CollectionItemEntity item, SyncCandidate candidate, boolean includeStats, boolean includePrivateInfo, boolean isBrief) {
		ContentValues values = toCollectionValues(item, includeStats, includePrivateInfo, isBrief);
		if (candidate.getInternalId() != BggContract.INVALID_ID) {
			removeDirtyValues(values, candidate);
			Uri uri = Collection.buildUri(candidate.getInternalId());
			if (!isBrief) {
				maybeDeleteThumbnail(values, uri);
			}
			resolver.update(uri, values, null, null);
		} else {
			resolver.insert(Collection.CONTENT_URI, values);
		}
	}

	@DebugLog
	private void removeDirtyValues(ContentValues values, SyncCandidate candidate) {
		removeValuesIfDirty(values, candidate.getStatusDirtyTimestamp(),
			Collection.STATUS_OWN,
			Collection.STATUS_PREVIOUSLY_OWNED,
			Collection.STATUS_FOR_TRADE,
			Collection.STATUS_WANT,
			Collection.STATUS_WANT_TO_BUY,
			Collection.STATUS_WISHLIST,
			Collection.STATUS_WANT_TO_PLAY,
			Collection.STATUS_PREORDERED,
			Collection.STATUS_WISHLIST_PRIORITY);
		removeValuesIfDirty(values, candidate.getRatingDirtyTimestamp(), Collection.RATING);
		removeValuesIfDirty(values, candidate.getCommentDirtyTimestamp(), Collection.COMMENT);
		removeValuesIfDirty(values, candidate.getPrivateInfoDirtyTimestamp(),
			Collection.PRIVATE_INFO_ACQUIRED_FROM,
			Collection.PRIVATE_INFO_ACQUISITION_DATE,
			Collection.PRIVATE_INFO_COMMENT,
			Collection.PRIVATE_INFO_CURRENT_VALUE,
			Collection.PRIVATE_INFO_CURRENT_VALUE_CURRENCY,
			Collection.PRIVATE_INFO_PRICE_PAID,
			Collection.PRIVATE_INFO_PRICE_PAID_CURRENCY,
			Collection.PRIVATE_INFO_QUANTITY);
		removeValuesIfDirty(values, candidate.getWishlistCommentDirtyTimestamp(), Collection.WISHLIST_COMMENT);
		removeValuesIfDirty(values, candidate.getTradeConditionDirtyTimestamp(), Collection.CONDITION);
		removeValuesIfDirty(values, candidate.getWantPartsDirtyTimestamp(), Collection.WANTPARTS_LIST);
		removeValuesIfDirty(values, candidate.getHasPartsDirtyTimestamp(), Collection.HASPARTS_LIST);
	}

	@DebugLog
	private void removeValuesIfDirty(ContentValues values, long dirtyFlag, String... columns) {
		if (dirtyFlag != NOT_DIRTY) {
			for (String column : columns) {
				values.remove(column);
			}
		}
	}

	@DebugLog
	private void maybeDeleteThumbnail(ContentValues values, Uri uri) {
		String newThumbnailUrl = values.getAsString(Collection.COLLECTION_THUMBNAIL_URL);
		if (newThumbnailUrl == null) {
			newThumbnailUrl = "";
		}

		String oldThumbnailUrl = ResolverUtils.queryString(resolver, uri, Collection.COLLECTION_THUMBNAIL_URL);
		if (newThumbnailUrl.equals(oldThumbnailUrl)) {
			// nothing to do - thumbnail hasn't changed
			return;
		}

		String thumbnailFileName = FileUtils.getFileNameFromUrl(oldThumbnailUrl);
		if (!TextUtils.isEmpty(thumbnailFileName)) {
			resolver.delete(Thumbnails.buildUri(thumbnailFileName), null, null);
		}
	}

	static class SyncCandidate {
		public static final SyncCandidate NULL = new SyncCandidate() {
			@Override
			public long getInternalId() {
				return BggContract.INVALID_ID;
			}

			@Override
			public long getDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getStatusDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getRatingDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getWishlistCommentDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getTradeConditionDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getWantPartsDirtyTimestamp() {
				return 0;
			}

			@Override
			public long getHasPartsDirtyTimestamp() {
				return 0;
			}
		};

		public static final String[] PROJECTION = {
			Collection._ID,
			Collection.COLLECTION_DIRTY_TIMESTAMP,
			Collection.STATUS_DIRTY_TIMESTAMP,
			Collection.RATING_DIRTY_TIMESTAMP,
			Collection.COMMENT_DIRTY_TIMESTAMP,
			Collection.PRIVATE_INFO_DIRTY_TIMESTAMP,
			Collection.WISHLIST_COMMENT_DIRTY_TIMESTAMP,
			Collection.TRADE_CONDITION_DIRTY_TIMESTAMP,
			Collection.WANT_PARTS_DIRTY_TIMESTAMP,
			Collection.HAS_PARTS_DIRTY_TIMESTAMP
		};

		private long internalId;
		private long dirtyTimestamp;
		private long statusDirtyTimestamp;
		private long ratingDirtyTimestamp;
		private long commentDirtyTimestamp;
		private long privateInfoDirtyTimestamp;
		private long wishlistCommentDirtyTimestamp;
		private long tradeConditionDirtyTimestamp;
		private long wantPartsDirtyTimestamp;
		private long hasPartsDirtyTimestamp;

		public static SyncCandidate find(ContentResolver resolver, int collectionId, int gameId) {
			Cursor cursor = null;
			try {
				if (collectionId == BggContract.INVALID_ID) {
					cursor = getCursorFromGameId(resolver, gameId);
					if (cursor != null && cursor.moveToFirst()) {
						return fromCursor(cursor);
					}
				} else {
					cursor = resolver.query(Collection.CONTENT_URI,
						PROJECTION,
						Collection.COLLECTION_ID + "=?",
						new String[] { String.valueOf(collectionId) },
						null);
					if (cursor != null && cursor.moveToFirst()) {
						return fromCursor(cursor);
					}

					if (cursor != null) cursor.close();
					cursor = getCursorFromGameId(resolver, gameId);
					if (cursor != null && cursor.moveToFirst()) {
						return fromCursor(cursor);
					}
				}
			} finally {
				if (cursor != null) cursor.close();
			}
			return NULL;
		}

		private static Cursor getCursorFromGameId(ContentResolver resolver, int gameId) {
			Cursor cursor;
			cursor = resolver.query(Collection.CONTENT_URI,
				PROJECTION,
				"collection." + Collection.GAME_ID + "=? AND " +
					SelectionBuilder.whereNullOrEmpty(Collection.COLLECTION_ID),
				new String[] { String.valueOf(gameId) },
				null);
			return cursor;
		}

		public static SyncCandidate fromCursor(Cursor cursor) {
			SyncCandidate candidate = new SyncCandidate();
			candidate.internalId = CursorUtils.getLong(cursor, Collection._ID, BggContract.INVALID_ID);
			candidate.dirtyTimestamp = CursorUtils.getLong(cursor, Collection.COLLECTION_DIRTY_TIMESTAMP);
			candidate.statusDirtyTimestamp = CursorUtils.getLong(cursor, Collection.STATUS_DIRTY_TIMESTAMP);
			candidate.ratingDirtyTimestamp = CursorUtils.getLong(cursor, Collection.RATING_DIRTY_TIMESTAMP);
			candidate.commentDirtyTimestamp = CursorUtils.getLong(cursor, Collection.COMMENT_DIRTY_TIMESTAMP);
			candidate.privateInfoDirtyTimestamp = CursorUtils.getLong(cursor, Collection.PRIVATE_INFO_DIRTY_TIMESTAMP);
			candidate.wishlistCommentDirtyTimestamp = CursorUtils.getLong(cursor, Collection.WISHLIST_COMMENT_DIRTY_TIMESTAMP);
			candidate.tradeConditionDirtyTimestamp = CursorUtils.getLong(cursor, Collection.TRADE_CONDITION_DIRTY_TIMESTAMP);
			candidate.wantPartsDirtyTimestamp = CursorUtils.getLong(cursor, Collection.WANT_PARTS_DIRTY_TIMESTAMP);
			candidate.hasPartsDirtyTimestamp = CursorUtils.getLong(cursor, Collection.HAS_PARTS_DIRTY_TIMESTAMP);
			return candidate;
		}

		public long getInternalId() {
			return internalId;
		}

		public long getDirtyTimestamp() {
			return dirtyTimestamp;
		}

		public long getStatusDirtyTimestamp() {
			return statusDirtyTimestamp;
		}

		public long getRatingDirtyTimestamp() {
			return ratingDirtyTimestamp;
		}

		public long getCommentDirtyTimestamp() {
			return commentDirtyTimestamp;
		}

		public long getPrivateInfoDirtyTimestamp() {
			return privateInfoDirtyTimestamp;
		}

		public long getWishlistCommentDirtyTimestamp() {
			return wishlistCommentDirtyTimestamp;
		}

		public long getTradeConditionDirtyTimestamp() {
			return tradeConditionDirtyTimestamp;
		}

		public long getWantPartsDirtyTimestamp() {
			return wantPartsDirtyTimestamp;
		}

		public long getHasPartsDirtyTimestamp() {
			return hasPartsDirtyTimestamp;
		}
	}
}
