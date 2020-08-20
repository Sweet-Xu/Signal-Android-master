/*
 * Copyright (C) 2011 Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms.database;

import android.content.Context;
import android.database.Cursor;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.sqlcipher.database.SQLiteDatabase;
import net.sqlcipher.database.SQLiteQueryBuilder;

import org.thoughtcrime.securesms.database.MessagingDatabase.SyncMessageId;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.whispersystems.libsignal.util.Pair;

import java.io.Closeable;
import java.util.HashSet;
import java.util.Set;

public class MmsSmsDatabase extends Database {

  @SuppressWarnings("unused")
  private static final String TAG = MmsSmsDatabase.class.getSimpleName();

  public static final String TRANSPORT     = "transport_type";
  public static final String MMS_TRANSPORT = "mms";
  public static final String SMS_TRANSPORT = "sms";

  private static final String[] PROJECTION = {MmsSmsColumns.ID,
                                              MmsSmsColumns.UNIQUE_ROW_ID,
                                              SmsDatabase.BODY,
                                              SmsDatabase.TYPE,
                                              MmsSmsColumns.THREAD_ID,
                                              SmsDatabase.RECIPIENT_ID,
                                              SmsDatabase.ADDRESS_DEVICE_ID,
                                              SmsDatabase.SUBJECT,
                                              MmsSmsColumns.NORMALIZED_DATE_SENT,
                                              MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                                              MmsSmsColumns.DATE_SERVER,
                                              MmsDatabase.MESSAGE_TYPE,
                                              MmsDatabase.MESSAGE_BOX,
                                              SmsDatabase.STATUS,
                                              MmsSmsColumns.UNIDENTIFIED,
                                              MmsSmsColumns.REACTIONS,
                                              MmsDatabase.PART_COUNT,
                                              MmsDatabase.CONTENT_LOCATION,
                                              MmsDatabase.TRANSACTION_ID,
                                              MmsDatabase.MESSAGE_SIZE,
                                              MmsDatabase.EXPIRY,
                                              MmsDatabase.STATUS,
                                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT,
                                              MmsSmsColumns.READ_RECEIPT_COUNT,
                                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                                              MmsDatabase.NETWORK_FAILURE,
                                              MmsSmsColumns.SUBSCRIPTION_ID,
                                              MmsSmsColumns.EXPIRES_IN,
                                              MmsSmsColumns.EXPIRE_STARTED,
                                              MmsSmsColumns.NOTIFIED,
                                              TRANSPORT,
                                              AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                                              MmsDatabase.QUOTE_ID,
                                              MmsDatabase.QUOTE_AUTHOR,
                                              MmsDatabase.QUOTE_BODY,
                                              MmsDatabase.QUOTE_MISSING,
                                              MmsDatabase.QUOTE_ATTACHMENT,
                                              MmsDatabase.QUOTE_MENTIONS,
                                              MmsDatabase.SHARED_CONTACTS,
                                              MmsDatabase.LINK_PREVIEWS,
                                              MmsDatabase.VIEW_ONCE,
                                              MmsSmsColumns.READ,
                                              MmsSmsColumns.REACTIONS,
                                              MmsSmsColumns.REACTIONS_UNREAD,
                                              MmsSmsColumns.REACTIONS_LAST_SEEN,
                                              MmsSmsColumns.REMOTE_DELETED,
                                              MmsDatabase.MENTIONS_SELF};

  public MmsSmsDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  /**
   * @return The user that added you to the group, otherwise null.
   */
  public @Nullable RecipientId getGroupAddedBy(long threadId) {
    long lastQuitChecked = System.currentTimeMillis();
    Pair<RecipientId, Long> pair;

    do {
      pair = getGroupAddedBy(threadId, lastQuitChecked);
      if (pair.first() != null) {
        return pair.first();
      } else {
        lastQuitChecked = pair.second();
      }

    } while (pair.second() != -1);

    return null;
  }

  private @NonNull Pair<RecipientId, Long> getGroupAddedBy(long threadId, long lastQuitChecked) {
    MmsDatabase mmsDatabase = DatabaseFactory.getMmsDatabase(context);
    SmsDatabase smsDatabase = DatabaseFactory.getSmsDatabase(context);
    long        latestQuit  = mmsDatabase.getLatestGroupQuitTimestamp(threadId, lastQuitChecked);
    RecipientId id          = smsDatabase.getOldestGroupUpdateSender(threadId, latestQuit);

    return new Pair<>(id, latestQuit);
  }

  public int getMessagePositionOnOrAfterTimestamp(long threadId, long timestamp) {
    String[] projection = new String[] { "COUNT(*)" };
    String   selection  = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " >= " + timestamp;

    try (Cursor cursor = queryTables(projection, selection, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return cursor.getInt(0);
      }
    }
    return 0;
  }

  public @Nullable MessageRecord getMessageFor(long timestamp, RecipientId author) {
    MmsSmsDatabase db = DatabaseFactory.getMmsSmsDatabase(context);

    try (Cursor cursor = queryTables(PROJECTION, MmsSmsColumns.NORMALIZED_DATE_SENT + " = " + timestamp, null, null)) {
      MmsSmsDatabase.Reader reader = db.readerFor(cursor);

      MessageRecord messageRecord;

      while ((messageRecord = reader.getNext()) != null) {
        if ((Recipient.resolved(author).isLocalNumber() && messageRecord.isOutgoing()) ||
            (!Recipient.resolved(author).isLocalNumber() && messageRecord.getIndividualRecipient().getId().equals(author)))
        {
          return messageRecord;
        }
      }
    }

    return null;
  }

  public Cursor getConversation(long threadId, long offset, long limit) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;
    String limitStr  = limit > 0 || offset > 0 ? offset + ", " + limit : null;

    Cursor cursor = queryTables(PROJECTION, selection, order, limitStr);
    setNotifyConversationListeners(cursor, threadId);

    return cursor;
  }

  public Cursor getConversation(long threadId) {
    return getConversation(threadId, 0, 0);
  }

  public Cursor getIdentityConflictMessagesForThread(long threadId) {
    String order           = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " ASC";
    String selection       = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.MISMATCHED_IDENTITIES + " IS NOT NULL";

    Cursor cursor = queryTables(PROJECTION, selection, order, null);
    setNotifyConversationListeners(cursor, threadId);

    return cursor;
  }

  public Cursor getConversationSnippet(long threadId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND (" + SmsDatabase.TYPE + " IS NULL OR " + SmsDatabase.TYPE + " != " + SmsDatabase.Types.PROFILE_CHANGE_TYPE + ")";

    return  queryTables(PROJECTION, selection, order, "1");
  }

  public Cursor getUnread() {
    String order           = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " ASC";
    String selection       = MmsSmsColumns.NOTIFIED + " = 0 AND (" + MmsSmsColumns.READ + " = 0 OR " + MmsSmsColumns.REACTIONS_UNREAD + " = 1)";

    return queryTables(PROJECTION, selection, order, null);
  }

  public int getUnreadCount(long threadId) {
    String selection = MmsSmsColumns.READ + " = 0 AND " + MmsSmsColumns.NOTIFIED + " = 0 AND " + MmsSmsColumns.THREAD_ID + " = " + threadId;
    Cursor cursor    = queryTables(PROJECTION, selection, null, null);

    try {
      return cursor != null ? cursor.getCount() : 0;
    } finally {
      if (cursor != null) cursor.close();;
    }
  }

  public boolean checkMessageExists(@NonNull MessageRecord messageRecord) {
    if (messageRecord.isMms()) {
      try (Cursor mms = DatabaseFactory.getMmsDatabase(context).getMessage(messageRecord.getId())) {
        return mms != null && mms.getCount() > 0;
      }
    } else {
      try (Cursor sms = DatabaseFactory.getSmsDatabase(context).getMessageCursor(messageRecord.getId())) {
        return sms != null && sms.getCount() > 0;
      }
    }
  }

  public int getSecureConversationCount(long threadId) {
    if (threadId == -1) {
      return 0;
    }

    int count = DatabaseFactory.getSmsDatabase(context).getSecureMessageCount(threadId);
    count    += DatabaseFactory.getMmsDatabase(context).getSecureMessageCount(threadId);

    return count;
  }

  public int getOutgoingSecureConversationCount(long threadId) {
    if (threadId == -1L) {
      return 0;
    }

    int count = DatabaseFactory.getSmsDatabase(context).getOutgoingSecureMessageCount(threadId);
    count    += DatabaseFactory.getMmsDatabase(context).getOutgoingSecureMessageCount(threadId);

    return count;
  }

  public int getConversationCount(long threadId) {
    int count = DatabaseFactory.getSmsDatabase(context).getMessageCountForThread(threadId);
    count    += DatabaseFactory.getMmsDatabase(context).getMessageCountForThread(threadId);

    return count;
  }

  public int getConversationCount(long threadId, long beforeTime) {
    return DatabaseFactory.getSmsDatabase(context).getMessageCountForThread(threadId, beforeTime) +
           DatabaseFactory.getMmsDatabase(context).getMessageCountForThread(threadId, beforeTime);
  }

  public int getConversationCountForThreadSummary(long threadId) {
    int count = DatabaseFactory.getSmsDatabase(context).getMessageCountForThreadSummary(threadId);
    count    += DatabaseFactory.getMmsDatabase(context).getMessageCountForThread(threadId);

    return count;
  }

  public int getInsecureSentCount(long threadId) {
    int count  = DatabaseFactory.getSmsDatabase(context).getInsecureMessagesSentForThread(threadId);
    count     += DatabaseFactory.getMmsDatabase(context).getInsecureMessagesSentForThread(threadId);

    return count;
  }

  public int getInsecureMessageCountForInsights() {
    int count = DatabaseFactory.getSmsDatabase(context).getInsecureMessageCountForInsights();
    count    += DatabaseFactory.getMmsDatabase(context).getInsecureMessageCountForInsights();

    return count;
  }

  public int getSecureMessageCountForInsights() {
    int count = DatabaseFactory.getSmsDatabase(context).getSecureMessageCountForInsights();
    count    += DatabaseFactory.getMmsDatabase(context).getSecureMessageCountForInsights();

    return count;
  }

  public long getThreadForMessageId(long messageId) {
    long id = DatabaseFactory.getSmsDatabase(context).getThreadIdForMessage(messageId);

    if (id == -1) return DatabaseFactory.getMmsDatabase(context).getThreadIdForMessage(messageId);
    else          return id;
  }

  public void incrementDeliveryReceiptCount(SyncMessageId syncMessageId, long timestamp) {
    DatabaseFactory.getSmsDatabase(context).incrementReceiptCount(syncMessageId, true);
    DatabaseFactory.getMmsDatabase(context).incrementReceiptCount(syncMessageId, timestamp, true);
  }

  public boolean incrementReadReceiptCount(SyncMessageId syncMessageId, long timestamp) {
    boolean handled  = false;

    handled |= DatabaseFactory.getSmsDatabase(context).incrementReceiptCount(syncMessageId, false);
    handled |= DatabaseFactory.getMmsDatabase(context).incrementReceiptCount(syncMessageId, timestamp, false);

    return handled;
  }

  public int getQuotedMessagePosition(long threadId, long quoteId, @NonNull RecipientId recipientId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(new String[]{ MmsSmsColumns.NORMALIZED_DATE_SENT, MmsSmsColumns.RECIPIENT_ID}, selection, order, null)) {
      boolean isOwnNumber = Recipient.resolved(recipientId).isLocalNumber();

      while (cursor != null && cursor.moveToNext()) {
        boolean quoteIdMatches     = cursor.getLong(0) == quoteId;
        boolean recipientIdMatches = recipientId.equals(RecipientId.from(cursor.getLong(1)));

        if (quoteIdMatches && (recipientIdMatches || isOwnNumber)) {
          return cursor.getPosition();
        }
      }
    }
    return -1;
  }

  public int getMessagePositionInConversation(long threadId, long receivedTimestamp, @NonNull RecipientId recipientId) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId;

    try (Cursor cursor = queryTables(new String[]{ MmsSmsColumns.NORMALIZED_DATE_RECEIVED, MmsSmsColumns.RECIPIENT_ID}, selection, order, null)) {
      boolean isOwnNumber = Recipient.resolved(recipientId).isLocalNumber();

      while (cursor != null && cursor.moveToNext()) {
        boolean timestampMatches   = cursor.getLong(0) == receivedTimestamp;
        boolean recipientIdMatches = recipientId.equals(RecipientId.from(cursor.getLong(1)));

        if (timestampMatches && (recipientIdMatches || isOwnNumber)) {
          return cursor.getPosition();
        }
      }
    }
    return -1;
  }

  boolean hasReceivedAnyCallsSince(long threadId, long timestamp) {
    return DatabaseFactory.getSmsDatabase(context).hasReceivedAnyCallsSince(threadId, timestamp);
  }

  /**
   * Retrieves the position of the message with the provided timestamp in the query results you'd
   * get from calling {@link #getConversation(long)}.
   *
   * Note: This could give back incorrect results in the situation where multiple messages have the
   * same received timestamp. However, because this was designed to determine where to scroll to,
   * you'll still wind up in about the right spot.
   */
  public int getMessagePositionInConversation(long threadId, long receivedTimestamp) {
    String order     = MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " DESC";
    String selection = MmsSmsColumns.THREAD_ID + " = " + threadId + " AND " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED + " > " + receivedTimestamp;

    try (Cursor cursor = queryTables(new String[]{ "COUNT(*)" }, selection, order, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return cursor.getInt(0);
      }
    }
    return -1;
  }

  private Cursor queryTables(String[] projection, String selection, String order, String limit) {
    String[] mmsProjection = {MmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT,
                              MmsDatabase.DATE_RECEIVED + " AS " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                              MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID + " AS " + MmsSmsColumns.ID,
                              "'MMS::' || " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID
                                  + " || '::' || " + MmsDatabase.DATE_SENT
                                  + " AS " + MmsSmsColumns.UNIQUE_ROW_ID,
                              "json_group_array(json_object(" +
                                  "'" + AttachmentDatabase.ROW_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.ROW_ID + ", " +
                                  "'" + AttachmentDatabase.UNIQUE_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UNIQUE_ID + ", " +
                                  "'" + AttachmentDatabase.MMS_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + "," +
                                  "'" + AttachmentDatabase.SIZE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.SIZE + ", " +
                                  "'" + AttachmentDatabase.FILE_NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FILE_NAME + ", " +
                                  "'" + AttachmentDatabase.DATA + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DATA + ", " +
                                  "'" + AttachmentDatabase.THUMBNAIL + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.THUMBNAIL + ", " +
                                  "'" + AttachmentDatabase.CONTENT_TYPE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_TYPE + ", " +
                                  "'" + AttachmentDatabase.CDN_NUMBER + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CDN_NUMBER + ", " +
                                  "'" + AttachmentDatabase.CONTENT_LOCATION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_LOCATION + ", " +
                                  "'" + AttachmentDatabase.FAST_PREFLIGHT_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.FAST_PREFLIGHT_ID + ", " +
                                  "'" + AttachmentDatabase.VOICE_NOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VOICE_NOTE + ", " +
                                  "'" + AttachmentDatabase.BORDERLESS + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.BORDERLESS + ", " +
                                  "'" + AttachmentDatabase.WIDTH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.WIDTH + ", " +
                                  "'" + AttachmentDatabase.HEIGHT + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.HEIGHT + ", " +
                                  "'" + AttachmentDatabase.QUOTE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.QUOTE + ", " +
                                  "'" + AttachmentDatabase.CONTENT_DISPOSITION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CONTENT_DISPOSITION + ", " +
                                  "'" + AttachmentDatabase.NAME + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.NAME + ", " +
                                  "'" + AttachmentDatabase.TRANSFER_STATE + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFER_STATE + ", " +
                                  "'" + AttachmentDatabase.CAPTION + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.CAPTION + ", " +
                                  "'" + AttachmentDatabase.STICKER_PACK_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_ID + ", " +
                                  "'" + AttachmentDatabase.STICKER_PACK_KEY + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_PACK_KEY + ", " +
                                  "'" + AttachmentDatabase.STICKER_ID + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.STICKER_ID + ", " +
                                  "'" + AttachmentDatabase.VISUAL_HASH + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.VISUAL_HASH + ", " +
                                  "'" + AttachmentDatabase.TRANSFORM_PROPERTIES + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.TRANSFORM_PROPERTIES + ", " +
                                  "'" + AttachmentDatabase.DISPLAY_ORDER + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.DISPLAY_ORDER + ", " +
                                  "'" + AttachmentDatabase.UPLOAD_TIMESTAMP + "', " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.UPLOAD_TIMESTAMP +
                                  ")) AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                              SmsDatabase.BODY, MmsSmsColumns.READ, MmsSmsColumns.THREAD_ID,
                              SmsDatabase.TYPE, SmsDatabase.RECIPIENT_ID, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT, MmsDatabase.MESSAGE_TYPE,
                              MmsDatabase.MESSAGE_BOX, SmsDatabase.STATUS, MmsDatabase.PART_COUNT,
                              MmsDatabase.CONTENT_LOCATION, MmsDatabase.TRANSACTION_ID,
                              MmsDatabase.MESSAGE_SIZE, MmsDatabase.EXPIRY, MmsDatabase.STATUS,
                              MmsDatabase.UNIDENTIFIED,
                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT, MmsSmsColumns.READ_RECEIPT_COUNT,
                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                              MmsSmsColumns.SUBSCRIPTION_ID, MmsSmsColumns.EXPIRES_IN, MmsSmsColumns.EXPIRE_STARTED,
                              MmsSmsColumns.NOTIFIED,
                              MmsDatabase.NETWORK_FAILURE, TRANSPORT,
                              MmsDatabase.QUOTE_ID,
                              MmsDatabase.QUOTE_AUTHOR,
                              MmsDatabase.QUOTE_BODY,
                              MmsDatabase.QUOTE_MISSING,
                              MmsDatabase.QUOTE_ATTACHMENT,
                              MmsDatabase.QUOTE_MENTIONS,
                              MmsDatabase.SHARED_CONTACTS,
                              MmsDatabase.LINK_PREVIEWS,
                              MmsDatabase.VIEW_ONCE,
                              MmsDatabase.REACTIONS,
                              MmsSmsColumns.REACTIONS_UNREAD,
                              MmsSmsColumns.REACTIONS_LAST_SEEN,
                              MmsSmsColumns.DATE_SERVER,
                              MmsSmsColumns.REMOTE_DELETED,
                              MmsDatabase.MENTIONS_SELF };

    String[] smsProjection = {SmsDatabase.DATE_SENT + " AS " + MmsSmsColumns.NORMALIZED_DATE_SENT,
                              SmsDatabase.DATE_RECEIVED + " AS " + MmsSmsColumns.NORMALIZED_DATE_RECEIVED,
                              MmsSmsColumns.ID,
                              "'SMS::' || " + MmsSmsColumns.ID
                                  + " || '::' || " + SmsDatabase.DATE_SENT
                                  + " AS " + MmsSmsColumns.UNIQUE_ROW_ID,
                              "NULL AS " + AttachmentDatabase.ATTACHMENT_JSON_ALIAS,
                              SmsDatabase.BODY, MmsSmsColumns.READ, MmsSmsColumns.THREAD_ID,
                              SmsDatabase.TYPE, SmsDatabase.RECIPIENT_ID, SmsDatabase.ADDRESS_DEVICE_ID, SmsDatabase.SUBJECT, MmsDatabase.MESSAGE_TYPE,
                              MmsDatabase.MESSAGE_BOX, SmsDatabase.STATUS, MmsDatabase.PART_COUNT,
                              MmsDatabase.CONTENT_LOCATION, MmsDatabase.TRANSACTION_ID,
                              MmsDatabase.MESSAGE_SIZE, MmsDatabase.EXPIRY, MmsDatabase.STATUS,
                              MmsDatabase.UNIDENTIFIED,
                              MmsSmsColumns.DELIVERY_RECEIPT_COUNT, MmsSmsColumns.READ_RECEIPT_COUNT,
                              MmsSmsColumns.MISMATCHED_IDENTITIES,
                              MmsSmsColumns.SUBSCRIPTION_ID, MmsSmsColumns.EXPIRES_IN, MmsSmsColumns.EXPIRE_STARTED,
                              MmsSmsColumns.NOTIFIED,
                              MmsDatabase.NETWORK_FAILURE, TRANSPORT,
                              MmsDatabase.QUOTE_ID,
                              MmsDatabase.QUOTE_AUTHOR,
                              MmsDatabase.QUOTE_BODY,
                              MmsDatabase.QUOTE_MISSING,
                              MmsDatabase.QUOTE_ATTACHMENT,
                              MmsDatabase.QUOTE_MENTIONS,
                              MmsDatabase.SHARED_CONTACTS,
                              MmsDatabase.LINK_PREVIEWS,
                              MmsDatabase.VIEW_ONCE,
                              MmsDatabase.REACTIONS,
                              MmsSmsColumns.REACTIONS_UNREAD,
                              MmsSmsColumns.REACTIONS_LAST_SEEN,
                              MmsSmsColumns.DATE_SERVER,
                              MmsSmsColumns.REMOTE_DELETED,
                              MmsDatabase.MENTIONS_SELF };

    SQLiteQueryBuilder mmsQueryBuilder = new SQLiteQueryBuilder();
    SQLiteQueryBuilder smsQueryBuilder = new SQLiteQueryBuilder();

    mmsQueryBuilder.setDistinct(true);
    smsQueryBuilder.setDistinct(true);

    smsQueryBuilder.setTables(SmsDatabase.TABLE_NAME);
    mmsQueryBuilder.setTables(MmsDatabase.TABLE_NAME + " LEFT OUTER JOIN " +
                              AttachmentDatabase.TABLE_NAME +
                              " ON " + AttachmentDatabase.TABLE_NAME + "." + AttachmentDatabase.MMS_ID + " = " + MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID);


    Set<String> mmsColumnsPresent = new HashSet<>();
    mmsColumnsPresent.add(MmsSmsColumns.ID);
    mmsColumnsPresent.add(MmsSmsColumns.READ);
    mmsColumnsPresent.add(MmsSmsColumns.THREAD_ID);
    mmsColumnsPresent.add(MmsSmsColumns.BODY);
    mmsColumnsPresent.add(MmsSmsColumns.RECIPIENT_ID);
    mmsColumnsPresent.add(MmsSmsColumns.ADDRESS_DEVICE_ID);
    mmsColumnsPresent.add(MmsSmsColumns.DELIVERY_RECEIPT_COUNT);
    mmsColumnsPresent.add(MmsSmsColumns.READ_RECEIPT_COUNT);
    mmsColumnsPresent.add(MmsSmsColumns.MISMATCHED_IDENTITIES);
    mmsColumnsPresent.add(MmsSmsColumns.SUBSCRIPTION_ID);
    mmsColumnsPresent.add(MmsSmsColumns.EXPIRES_IN);
    mmsColumnsPresent.add(MmsSmsColumns.EXPIRE_STARTED);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_TYPE);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_BOX);
    mmsColumnsPresent.add(MmsDatabase.DATE_SENT);
    mmsColumnsPresent.add(MmsDatabase.DATE_RECEIVED);
    mmsColumnsPresent.add(MmsDatabase.DATE_SERVER);
    mmsColumnsPresent.add(MmsDatabase.PART_COUNT);
    mmsColumnsPresent.add(MmsDatabase.CONTENT_LOCATION);
    mmsColumnsPresent.add(MmsDatabase.TRANSACTION_ID);
    mmsColumnsPresent.add(MmsDatabase.MESSAGE_SIZE);
    mmsColumnsPresent.add(MmsDatabase.EXPIRY);
    mmsColumnsPresent.add(MmsDatabase.NOTIFIED);
    mmsColumnsPresent.add(MmsDatabase.STATUS);
    mmsColumnsPresent.add(MmsDatabase.UNIDENTIFIED);
    mmsColumnsPresent.add(MmsDatabase.NETWORK_FAILURE);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_ID);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_AUTHOR);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_BODY);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_MISSING);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_ATTACHMENT);
    mmsColumnsPresent.add(MmsDatabase.QUOTE_MENTIONS);
    mmsColumnsPresent.add(MmsDatabase.SHARED_CONTACTS);
    mmsColumnsPresent.add(MmsDatabase.LINK_PREVIEWS);
    mmsColumnsPresent.add(MmsDatabase.VIEW_ONCE);
    mmsColumnsPresent.add(MmsDatabase.REACTIONS);
    mmsColumnsPresent.add(MmsDatabase.REACTIONS_UNREAD);
    mmsColumnsPresent.add(MmsDatabase.REACTIONS_LAST_SEEN);
    mmsColumnsPresent.add(MmsDatabase.REMOTE_DELETED);
    mmsColumnsPresent.add(MmsDatabase.MENTIONS_SELF);

    Set<String> smsColumnsPresent = new HashSet<>();
    smsColumnsPresent.add(MmsSmsColumns.ID);
    smsColumnsPresent.add(MmsSmsColumns.BODY);
    smsColumnsPresent.add(MmsSmsColumns.RECIPIENT_ID);
    smsColumnsPresent.add(MmsSmsColumns.ADDRESS_DEVICE_ID);
    smsColumnsPresent.add(MmsSmsColumns.READ);
    smsColumnsPresent.add(MmsSmsColumns.THREAD_ID);
    smsColumnsPresent.add(MmsSmsColumns.DELIVERY_RECEIPT_COUNT);
    smsColumnsPresent.add(MmsSmsColumns.READ_RECEIPT_COUNT);
    smsColumnsPresent.add(MmsSmsColumns.MISMATCHED_IDENTITIES);
    smsColumnsPresent.add(MmsSmsColumns.SUBSCRIPTION_ID);
    smsColumnsPresent.add(MmsSmsColumns.EXPIRES_IN);
    smsColumnsPresent.add(MmsSmsColumns.EXPIRE_STARTED);
    smsColumnsPresent.add(MmsSmsColumns.NOTIFIED);
    smsColumnsPresent.add(SmsDatabase.TYPE);
    smsColumnsPresent.add(SmsDatabase.SUBJECT);
    smsColumnsPresent.add(SmsDatabase.DATE_SENT);
    smsColumnsPresent.add(SmsDatabase.DATE_RECEIVED);
    smsColumnsPresent.add(SmsDatabase.DATE_SERVER);
    smsColumnsPresent.add(SmsDatabase.STATUS);
    smsColumnsPresent.add(SmsDatabase.UNIDENTIFIED);
    smsColumnsPresent.add(SmsDatabase.REACTIONS);
    smsColumnsPresent.add(SmsDatabase.REACTIONS_UNREAD);
    smsColumnsPresent.add(SmsDatabase.REACTIONS_LAST_SEEN);
    smsColumnsPresent.add(MmsDatabase.REMOTE_DELETED);

    @SuppressWarnings("deprecation")
    String mmsSubQuery = mmsQueryBuilder.buildUnionSubQuery(TRANSPORT, mmsProjection, mmsColumnsPresent, 4, MMS_TRANSPORT, selection, null, MmsDatabase.TABLE_NAME + "." + MmsDatabase.ID, null);
    @SuppressWarnings("deprecation")
    String smsSubQuery = smsQueryBuilder.buildUnionSubQuery(TRANSPORT, smsProjection, smsColumnsPresent, 4, SMS_TRANSPORT, selection, null, null, null);

    SQLiteQueryBuilder unionQueryBuilder = new SQLiteQueryBuilder();
    String unionQuery = unionQueryBuilder.buildUnionQuery(new String[] {smsSubQuery, mmsSubQuery}, order, limit);

    SQLiteQueryBuilder outerQueryBuilder = new SQLiteQueryBuilder();
    outerQueryBuilder.setTables("(" + unionQuery + ")");

    @SuppressWarnings("deprecation")
    String query      = outerQueryBuilder.buildQuery(projection, null, null, null, null, null, null);

    SQLiteDatabase db = databaseHelper.getReadableDatabase();
    return db.rawQuery(query, null);
  }

  public Reader readerFor(@NonNull Cursor cursor) {
    return new Reader(cursor);
  }

  public class Reader implements Closeable {

    private final Cursor                 cursor;
    private       SmsDatabase.Reader     smsReader;
    private       MmsDatabase.Reader     mmsReader;

    public Reader(Cursor cursor) {
      this.cursor = cursor;
    }

    private SmsDatabase.Reader getSmsReader() {
      if (smsReader == null) {
        smsReader = DatabaseFactory.getSmsDatabase(context).readerFor(cursor);
      }

      return smsReader;
    }

    private MmsDatabase.Reader getMmsReader() {
      if (mmsReader == null) {
        mmsReader = DatabaseFactory.getMmsDatabase(context).readerFor(cursor);
      }

      return mmsReader;
    }

    public MessageRecord getNext() {
      if (cursor == null || !cursor.moveToNext())
        return null;

      return getCurrent();
    }

    public MessageRecord getCurrent() {
      String type = cursor.getString(cursor.getColumnIndexOrThrow(TRANSPORT));

      if      (MmsSmsDatabase.MMS_TRANSPORT.equals(type)) return getMmsReader().getCurrent();
      else if (MmsSmsDatabase.SMS_TRANSPORT.equals(type)) return getSmsReader().getCurrent();
      else                                                throw new AssertionError("Bad type: " + type);
    }

    @Override
    public void close() {
      cursor.close();
    }
  }
}
