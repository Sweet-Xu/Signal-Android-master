package org.thoughtcrime.securesms.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;
import com.google.android.gms.common.util.ArrayUtils;

import net.sqlcipher.database.SQLiteConstraintException;
import net.sqlcipher.database.SQLiteDatabase;

import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.profiles.ProfileKey;
import org.signal.zkgroup.profiles.ProfileKeyCredential;
import org.thoughtcrime.securesms.color.MaterialColor;
import org.thoughtcrime.securesms.contacts.avatars.ContactColors;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.database.IdentityDatabase.IdentityRecord;
import org.thoughtcrime.securesms.database.helpers.SQLCipherOpenHelper;
import org.thoughtcrime.securesms.database.model.ThreadRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.v2.ProfileKeySet;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.jobs.WakeGroupV2Job;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.storage.StorageSyncHelper.RecordUpdate;
import org.thoughtcrime.securesms.storage.StorageSyncModels;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.CursorUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.SqlUtil;
import org.thoughtcrime.securesms.util.StringUtil;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.InvalidKeyException;
import org.whispersystems.libsignal.util.Pair;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.storage.SignalAccountRecord;
import org.whispersystems.signalservice.api.storage.SignalContactRecord;
import org.whispersystems.signalservice.api.storage.SignalGroupV1Record;
import org.whispersystems.signalservice.api.storage.SignalGroupV2Record;
import org.whispersystems.signalservice.api.storage.StorageId;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RecipientDatabase extends Database {

  private static final String TAG = RecipientDatabase.class.getSimpleName();

          static final String TABLE_NAME               = "recipient";
  public  static final String ID                       = "_id";
  private static final String UUID                     = "uuid";
  private static final String USERNAME                 = "username";
  public  static final String PHONE                    = "phone";
  public  static final String EMAIL                    = "email";
          static final String GROUP_ID                 = "group_id";
  private static final String GROUP_TYPE               = "group_type";
  private static final String BLOCKED                  = "blocked";
  private static final String MESSAGE_RINGTONE         = "message_ringtone";
  private static final String MESSAGE_VIBRATE          = "message_vibrate";
  private static final String CALL_RINGTONE            = "call_ringtone";
  private static final String CALL_VIBRATE             = "call_vibrate";
  private static final String NOTIFICATION_CHANNEL     = "notification_channel";
  private static final String MUTE_UNTIL               = "mute_until";
  private static final String COLOR                    = "color";
  private static final String SEEN_INVITE_REMINDER     = "seen_invite_reminder";
  private static final String DEFAULT_SUBSCRIPTION_ID  = "default_subscription_id";
  private static final String MESSAGE_EXPIRATION_TIME  = "message_expiration_time";
  public  static final String REGISTERED               = "registered";
  public  static final String SYSTEM_DISPLAY_NAME      = "system_display_name";
  private static final String SYSTEM_PHOTO_URI         = "system_photo_uri";
  public  static final String SYSTEM_PHONE_TYPE        = "system_phone_type";
  public  static final String SYSTEM_PHONE_LABEL       = "system_phone_label";
  private static final String SYSTEM_CONTACT_URI       = "system_contact_uri";
  private static final String SYSTEM_INFO_PENDING      = "system_info_pending";
  private static final String PROFILE_KEY              = "profile_key";
  private static final String PROFILE_KEY_CREDENTIAL   = "profile_key_credential";
  private static final String SIGNAL_PROFILE_AVATAR    = "signal_profile_avatar";
  private static final String PROFILE_SHARING          = "profile_sharing";
  private static final String LAST_PROFILE_FETCH       = "last_profile_fetch";
  private static final String UNIDENTIFIED_ACCESS_MODE = "unidentified_access_mode";
  private static final String FORCE_SMS_SELECTION      = "force_sms_selection";
  private static final String UUID_CAPABILITY          = "uuid_supported";
  private static final String GROUPS_V2_CAPABILITY     = "gv2_capability";
  private static final String STORAGE_SERVICE_ID       = "storage_service_key";
  private static final String DIRTY                    = "dirty";
  private static final String PROFILE_GIVEN_NAME       = "signal_profile_name";
  private static final String PROFILE_FAMILY_NAME      = "profile_family_name";
  private static final String PROFILE_JOINED_NAME      = "profile_joined_name";
  private static final String MENTION_SETTING          = "mention_setting";

  public  static final String SEARCH_PROFILE_NAME      = "search_signal_profile";
  private static final String SORT_NAME                = "sort_name";
  private static final String IDENTITY_STATUS          = "identity_status";
  private static final String IDENTITY_KEY             = "identity_key";

  private static final String[] RECIPIENT_PROJECTION = new String[] {
      UUID, USERNAME, PHONE, EMAIL, GROUP_ID, GROUP_TYPE,
      BLOCKED, MESSAGE_RINGTONE, CALL_RINGTONE, MESSAGE_VIBRATE, CALL_VIBRATE, MUTE_UNTIL, COLOR, SEEN_INVITE_REMINDER, DEFAULT_SUBSCRIPTION_ID, MESSAGE_EXPIRATION_TIME, REGISTERED,
      PROFILE_KEY, PROFILE_KEY_CREDENTIAL,
      SYSTEM_DISPLAY_NAME, SYSTEM_PHOTO_URI, SYSTEM_PHONE_LABEL, SYSTEM_PHONE_TYPE, SYSTEM_CONTACT_URI,
      PROFILE_GIVEN_NAME, PROFILE_FAMILY_NAME, SIGNAL_PROFILE_AVATAR, PROFILE_SHARING, LAST_PROFILE_FETCH,
      NOTIFICATION_CHANNEL,
      UNIDENTIFIED_ACCESS_MODE,
      FORCE_SMS_SELECTION,
      UUID_CAPABILITY, GROUPS_V2_CAPABILITY,
      STORAGE_SERVICE_ID, DIRTY,
      MENTION_SETTING
  };

  private static final String[] ID_PROJECTION              = new String[]{ID};
  private static final String[] SEARCH_PROJECTION          = new String[]{ID, SYSTEM_DISPLAY_NAME, PHONE, EMAIL, SYSTEM_PHONE_LABEL, SYSTEM_PHONE_TYPE, REGISTERED, "COALESCE(" + nullIfEmpty(PROFILE_JOINED_NAME) + ", " + nullIfEmpty(PROFILE_GIVEN_NAME) + ") AS " + SEARCH_PROFILE_NAME, "COALESCE(" + nullIfEmpty(SYSTEM_DISPLAY_NAME) + ", " + nullIfEmpty(PROFILE_JOINED_NAME) + ", " + nullIfEmpty(PROFILE_GIVEN_NAME) + ", " + nullIfEmpty(USERNAME) + ") AS " + SORT_NAME};
  public  static final String[] SEARCH_PROJECTION_NAMES    = new String[]{ID, SYSTEM_DISPLAY_NAME, PHONE, EMAIL, SYSTEM_PHONE_LABEL, SYSTEM_PHONE_TYPE, REGISTERED, SEARCH_PROFILE_NAME, SORT_NAME};
          static final String[] TYPED_RECIPIENT_PROJECTION = Stream.of(RECIPIENT_PROJECTION)
                                                                   .map(columnName -> TABLE_NAME + "." + columnName)
                                                                   .toList().toArray(new String[0]);

  private static final String[] MENTION_SEARCH_PROJECTION  = new String[]{ID, removeWhitespace("COALESCE(" + nullIfEmpty(SYSTEM_DISPLAY_NAME) + ", " + nullIfEmpty(PROFILE_JOINED_NAME) + ", " + nullIfEmpty(PROFILE_GIVEN_NAME) + ", " + nullIfEmpty(USERNAME) + ", " + nullIfEmpty(PHONE) + ")") + " AS " + SORT_NAME};

  private static final String[] RECIPIENT_FULL_PROJECTION = ArrayUtils.concat(
      new String[] { TABLE_NAME + "." + ID },
      TYPED_RECIPIENT_PROJECTION,
      new String[] {
        IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.VERIFIED + " AS " + IDENTITY_STATUS,
        IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.IDENTITY_KEY + " AS " + IDENTITY_KEY
      });

  public static final String[] CREATE_INDEXS = new String[] {
      "CREATE INDEX IF NOT EXISTS recipient_dirty_index ON " + TABLE_NAME + " (" + DIRTY + ");",
      "CREATE INDEX IF NOT EXISTS recipient_group_type_index ON " + TABLE_NAME + " (" + GROUP_TYPE + ");",
  };

  public enum VibrateState {
    DEFAULT(0), ENABLED(1), DISABLED(2);

    private final int id;

    VibrateState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static VibrateState fromId(int id) {
      return values()[id];
    }

    public static VibrateState fromBoolean(boolean enabled) {
      return enabled ? ENABLED : DISABLED;
    }
  }

  public enum RegisteredState {
    UNKNOWN(0), REGISTERED(1), NOT_REGISTERED(2);

    private final int id;

    RegisteredState(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public static RegisteredState fromId(int id) {
      return values()[id];
    }
  }

  public enum UnidentifiedAccessMode {
    UNKNOWN(0), DISABLED(1), ENABLED(2), UNRESTRICTED(3);

    private final int mode;

    UnidentifiedAccessMode(int mode) {
      this.mode = mode;
    }

    public int getMode() {
      return mode;
    }

    public static UnidentifiedAccessMode fromMode(int mode) {
      return values()[mode];
    }
  }

  public enum InsightsBannerTier {
    NO_TIER(0), TIER_ONE(1), TIER_TWO(2);

    private final int id;

    InsightsBannerTier(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    public boolean seen(InsightsBannerTier tier) {
      return tier.getId() <= id;
    }

    public static InsightsBannerTier fromId(int id) {
      return values()[id];
    }
  }

  public enum DirtyState {
    CLEAN(0), UPDATE(1), INSERT(2), DELETE(3);

    private final int id;

    DirtyState(int id) {
      this.id = id;
    }

    int getId() {
      return id;
    }

    public static DirtyState fromId(int id) {
      return values()[id];
    }
  }

  public enum GroupType {
    NONE(0), MMS(1), SIGNAL_V1(2), SIGNAL_V2(3);

    private final int id;

    GroupType(int id) {
      this.id = id;
    }

    int getId() {
      return id;
    }

    public static GroupType fromId(int id) {
      return values()[id];
    }
  }

  public enum MentionSetting {
    ALWAYS_NOTIFY(0), DO_NOT_NOTIFY(1);

    private final int id;

    MentionSetting(int id) {
      this.id = id;
    }

    int getId() {
      return id;
    }

    public static MentionSetting fromId(int id) {
      return values()[id];
    }
  }

  public static final String CREATE_TABLE =
      "CREATE TABLE " + TABLE_NAME + " (" + ID                       + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                                            UUID                     + " TEXT UNIQUE DEFAULT NULL, " +
                                            USERNAME                 + " TEXT UNIQUE DEFAULT NULL, " +
                                            PHONE                    + " TEXT UNIQUE DEFAULT NULL, " +
                                            EMAIL                    + " TEXT UNIQUE DEFAULT NULL, " +
                                            GROUP_ID                 + " TEXT UNIQUE DEFAULT NULL, " +
                                            GROUP_TYPE               + " INTEGER DEFAULT " + GroupType.NONE.getId() +  ", " +
                                            BLOCKED                  + " INTEGER DEFAULT 0," +
                                            MESSAGE_RINGTONE         + " TEXT DEFAULT NULL, " +
                                            MESSAGE_VIBRATE          + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                                            CALL_RINGTONE            + " TEXT DEFAULT NULL, " +
                                            CALL_VIBRATE             + " INTEGER DEFAULT " + VibrateState.DEFAULT.getId() + ", " +
                                            NOTIFICATION_CHANNEL     + " TEXT DEFAULT NULL, " +
                                            MUTE_UNTIL               + " INTEGER DEFAULT 0, " +
                                            COLOR                    + " TEXT DEFAULT NULL, " +
                                            SEEN_INVITE_REMINDER     + " INTEGER DEFAULT " + InsightsBannerTier.NO_TIER.getId() + ", " +
                                            DEFAULT_SUBSCRIPTION_ID  + " INTEGER DEFAULT -1, " +
                                            MESSAGE_EXPIRATION_TIME  + " INTEGER DEFAULT 0, " +
                                            REGISTERED               + " INTEGER DEFAULT " + RegisteredState.UNKNOWN.getId() + ", " +
                                            SYSTEM_DISPLAY_NAME      + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHOTO_URI         + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHONE_LABEL       + " TEXT DEFAULT NULL, " +
                                            SYSTEM_PHONE_TYPE        + " INTEGER DEFAULT -1, " +
                                            SYSTEM_CONTACT_URI       + " TEXT DEFAULT NULL, " +
                                            SYSTEM_INFO_PENDING      + " INTEGER DEFAULT 0, " +
                                            PROFILE_KEY              + " TEXT DEFAULT NULL, " +
                                            PROFILE_KEY_CREDENTIAL   + " TEXT DEFAULT NULL, " +
                                            PROFILE_GIVEN_NAME       + " TEXT DEFAULT NULL, " +
                                            PROFILE_FAMILY_NAME      + " TEXT DEFAULT NULL, " +
                                            PROFILE_JOINED_NAME      + " TEXT DEFAULT NULL, " +
                                            SIGNAL_PROFILE_AVATAR    + " TEXT DEFAULT NULL, " +
                                            PROFILE_SHARING          + " INTEGER DEFAULT 0, " +
                                            LAST_PROFILE_FETCH       + " INTEGER DEFAULT 0, " +
                                            UNIDENTIFIED_ACCESS_MODE + " INTEGER DEFAULT 0, " +
                                            FORCE_SMS_SELECTION      + " INTEGER DEFAULT 0, " +
                                            UUID_CAPABILITY          + " INTEGER DEFAULT " + Recipient.Capability.UNKNOWN.serialize() + ", " +
                                            GROUPS_V2_CAPABILITY     + " INTEGER DEFAULT " + Recipient.Capability.UNKNOWN.serialize() + ", " +
                                            STORAGE_SERVICE_ID       + " TEXT UNIQUE DEFAULT NULL, " +
                                            DIRTY                    + " INTEGER DEFAULT " + DirtyState.CLEAN.getId() + ", " +
                                            MENTION_SETTING          + " INTEGER DEFAULT " + MentionSetting.ALWAYS_NOTIFY.getId() + ");";

  private static final String INSIGHTS_INVITEE_LIST = "SELECT " + TABLE_NAME + "." + ID +
      " FROM " + TABLE_NAME +
      " INNER JOIN " + ThreadDatabase.TABLE_NAME +
      " ON " + TABLE_NAME + "." + ID + " = " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID +
      " WHERE " +
      TABLE_NAME + "." + GROUP_ID + " IS NULL AND " +
      TABLE_NAME + "." + REGISTERED + " = " + RegisteredState.NOT_REGISTERED.id + " AND " +
      TABLE_NAME + "." + SEEN_INVITE_REMINDER + " < " + InsightsBannerTier.TIER_TWO.id + " AND " +
      ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.HAS_SENT + " AND " +
      ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " > ?" +
      " ORDER BY " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.DATE + " DESC LIMIT 50";

  public RecipientDatabase(Context context, SQLCipherOpenHelper databaseHelper) {
    super(context, databaseHelper);
  }

  public @NonNull boolean containsPhoneOrUuid(@NonNull String id) {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = UUID + " = ? OR " + PHONE + " = ?";
    String[]       args  = new String[]{id, id};

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { ID }, query, args, null, null, null)) {
      return cursor != null && cursor.moveToFirst();
    }
  }

  public @NonNull Optional<RecipientId> getByE164(@NonNull String e164) {
    return getByColumn(PHONE, e164);
  }

  public @NonNull Optional<RecipientId> getByEmail(@NonNull String email) {
    return getByColumn(EMAIL, email);
  }

  public @NonNull Optional<RecipientId> getByGroupId(@NonNull String groupId) {
    return getByColumn(GROUP_ID, groupId);

  }

  public @NonNull Optional<RecipientId> getByUuid(@NonNull UUID uuid) {
    return getByColumn(UUID, uuid.toString());
  }

  public @NonNull Optional<RecipientId> getByUsername(@NonNull String username) {
    return getByColumn(USERNAME, username);
  }

  public @NonNull RecipientId getAndPossiblyMerge(@Nullable UUID uuid, @Nullable String e164, boolean highTrust) {
    if (uuid == null && e164 == null) {
      throw new IllegalArgumentException("Must provide a UUID or E164!");
    }

    if (!FeatureFlags.cds()) {
      highTrust = true;
    }

    RecipientId                    recipientNeedingRefresh = null;
    Pair<RecipientId, RecipientId> remapped                = null;
    boolean                        transactionSuccessful   = false;

    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      Optional<RecipientId> byE164 = e164 != null ? getByE164(e164) : Optional.absent();
      Optional<RecipientId> byUuid = uuid != null ? getByUuid(uuid) : Optional.absent();

      RecipientId finalId;

      if (!byE164.isPresent() && !byUuid.isPresent()) {
        Log.i(TAG, "Discovered a completely new user. Inserting.");
        if (highTrust) {
          long id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(e164, uuid));
          finalId = RecipientId.from(id);
        } else {
          long id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(uuid == null ? e164 : null, uuid));
          finalId = RecipientId.from(id);
        }
      } else if (byE164.isPresent() && !byUuid.isPresent()) {
        if (uuid != null) {
          RecipientSettings e164Settings = getRecipientSettings(byE164.get());
          if (e164Settings.uuid != null) {
            if (highTrust) {
              Log.w(TAG, "Found out about a UUID for a known E164 user, but that user already has a UUID. Likely a case of re-registration. High-trust, so stripping the E164 from the existing account and assigning it to a new entry.");

              removePhoneNumber(byE164.get(), db);
              recipientNeedingRefresh = byE164.get();

              ContentValues insertValues = buildContentValuesForNewUser(e164, uuid);
              insertValues.put(BLOCKED, e164Settings.blocked ? 1 : 0);

              long id = db.insert(TABLE_NAME, null, insertValues);
              finalId = RecipientId.from(id);
            } else {
              Log.w(TAG, "Found out about a UUID for a known E164 user, but that user already has a UUID. Likely a case of re-registration. Low-trust, so making a new user for the UUID.");

              long id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(null, uuid));
              finalId = RecipientId.from(id);
            }
          } else {
            if (highTrust) {
              Log.i(TAG, "Found out about a UUID for a known E164 user. High-trust, so updating.");
              markRegisteredOrThrow(byE164.get(), uuid);
              finalId = byE164.get();
            } else {
              Log.i(TAG, "Found out about a UUID for a known E164 user. Low-trust, so making a new user for the UUID.");
              long id = db.insert(TABLE_NAME, null, buildContentValuesForNewUser(null, uuid));
              finalId = RecipientId.from(id);
            }
          }
        } else {
          finalId = byE164.get();
        }
      } else if (!byE164.isPresent() && byUuid.isPresent()) {
        if (e164 != null) {
          if (highTrust) {
            Log.i(TAG, "Found out about an E164 for a known UUID user. High-trust, so updating.");
            setPhoneNumberOrThrow(byUuid.get(), e164);
            finalId = byUuid.get();
          } else {
            Log.i(TAG, "Found out about an E164 for a known UUID user. Low-trust, so doing nothing.");
            finalId = byUuid.get();
          }
        } else {
          finalId = byUuid.get();
        }
      } else {
        if (byE164.equals(byUuid)) {
          finalId = byUuid.get();
        } else {
          Log.w(TAG, "Hit a conflict between " + byE164.get() + " (E164) and " + byUuid.get() + " (UUID). They map to different recipients.", new Throwable());

          RecipientSettings e164Settings = getRecipientSettings(byE164.get());

          if (e164Settings.getUuid() != null) {
            if (highTrust) {
              Log.w(TAG, "The E164 contact has a different UUID. Likely a case of re-registration. High-trust, so stripping the E164 from the existing account and assigning it to the UUID entry.");

              removePhoneNumber(byE164.get(), db);
              recipientNeedingRefresh = byE164.get();

              setPhoneNumberOrThrow(byUuid.get(), Objects.requireNonNull(e164));

              finalId = byUuid.get();
            } else {
              Log.w(TAG, "The E164 contact has a different UUID. Likely a case of re-registration. Low-trust, so doing nothing.");
              finalId = byUuid.get();
            }
          } else {
            if (highTrust) {
              Log.w(TAG, "We have one contact with just an E164, and another with UUID. High-trust, so merging the two rows together.");
              finalId                 = merge(byUuid.get(), byE164.get());
              recipientNeedingRefresh = byUuid.get();
              remapped                = new Pair<>(byE164.get(), byUuid.get());
            } else {
              Log.w(TAG, "We have one contact with just an E164, and another with UUID. Low-trust, so doing nothing.");
              finalId  = byUuid.get();
            }
          }
        }
      }

      db.setTransactionSuccessful();
      transactionSuccessful = true;
      return finalId;
    } finally {
      db.endTransaction();

      if (transactionSuccessful) {
        if (recipientNeedingRefresh != null) {
          Recipient.live(recipientNeedingRefresh).refresh();
        }

        if (remapped != null) {
          Recipient.live(remapped.first()).refresh(remapped.second());
        }

        if (recipientNeedingRefresh != null || remapped != null) {
          StorageSyncHelper.scheduleSyncForDataChange();
          RecipientId.clearCache();
        }
      }
    }
  }

  private static ContentValues buildContentValuesForNewUser(@Nullable String e164, @Nullable UUID uuid) {
    ContentValues values = new ContentValues();

    values.put(PHONE, e164);

    if (uuid != null) {
      values.put(UUID, uuid.toString().toLowerCase());
      values.put(REGISTERED, RegisteredState.REGISTERED.getId());
      values.put(DIRTY, DirtyState.INSERT.getId());
      values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()));
    }

    return values;
  }


  public @NonNull RecipientId getOrInsertFromUuid(@NonNull UUID uuid) {
    return getOrInsertByColumn(UUID, uuid.toString()).recipientId;
  }

  public @NonNull RecipientId getOrInsertFromE164(@NonNull String e164) {
    return getOrInsertByColumn(PHONE, e164).recipientId;
  }

  public @NonNull RecipientId getOrInsertFromEmail(@NonNull String email) {
    return getOrInsertByColumn(EMAIL, email).recipientId;
  }

  public @NonNull RecipientId getOrInsertFromGroupId(@NonNull GroupId groupId) {
    GetOrInsertResult result = getOrInsertByColumn(GROUP_ID, groupId.toString());

    if (result.neededInsert) {
      ContentValues values = new ContentValues();

      if (groupId.isMms()) {
        values.put(GROUP_TYPE, GroupType.MMS.getId());
      } else {
        if (groupId.isV2()) {
          values.put(GROUP_TYPE, GroupType.SIGNAL_V2.getId());
        } else {
          values.put(GROUP_TYPE, GroupType.SIGNAL_V1.getId());
        }
        values.put(DIRTY, DirtyState.INSERT.getId());
        values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()));
      }

      update(result.recipientId, values);
    }

    return result.recipientId;
  }

  public Cursor getBlocked() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();

    return database.query(TABLE_NAME, ID_PROJECTION, BLOCKED + " = 1",
                          null, null, null, null, null);
  }

  public RecipientReader readerForBlocked(Cursor cursor) {
    return new RecipientReader(cursor);
  }

  public RecipientReader getRecipientsWithNotificationChannels() {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    Cursor         cursor   = database.query(TABLE_NAME, ID_PROJECTION, NOTIFICATION_CHANNEL  + " NOT NULL",
                                             null, null, null, null, null);

    return new RecipientReader(cursor);
  }

  public @NonNull RecipientSettings getRecipientSettings(@NonNull RecipientId id) {
    SQLiteDatabase database = databaseHelper.getReadableDatabase();
    String         table    = TABLE_NAME + " LEFT OUTER JOIN " + IdentityDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + ID + " = " + IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.RECIPIENT_ID;
    String         query    = TABLE_NAME + "." + ID + " = ?";
    String[]       args     = new String[] { id.serialize() };

    try (Cursor cursor = database.query(table, RECIPIENT_FULL_PROJECTION, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToNext()) {
        return getRecipientSettings(context, cursor);
      } else {
        Optional<RecipientId> remapped = RemappedRecords.getInstance().getRecipient(context, id);
        if (remapped.isPresent()) {
          Log.w(TAG, "Missing recipient, but found it in the remapped records.");
          return getRecipientSettings(remapped.get());
        } else {
          throw new MissingRecipientException(id);
        }
      }
    }
  }

  public @NonNull DirtyState getDirtyState(@NonNull RecipientId recipientId) {
    SQLiteDatabase db = databaseHelper.getReadableDatabase();

    try (Cursor cursor = db.query(TABLE_NAME,  new String[] { DIRTY }, ID_WHERE, new String[] { recipientId.serialize() }, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return DirtyState.fromId(cursor.getInt(cursor.getColumnIndexOrThrow(DIRTY)));
      }
    }

    return DirtyState.CLEAN;
  }

  public @Nullable RecipientSettings getRecipientSettingsForSync(@NonNull RecipientId id) {
    String   query = TABLE_NAME + "." + ID + " = ?";
    String[] args  = new String[]{id.serialize()};

    List<RecipientSettings> recipientSettingsForSync = getRecipientSettingsForSync(query, args);

    if (recipientSettingsForSync.isEmpty()) {
      return null;
    }

    if (recipientSettingsForSync.size() > 1) {
      throw new AssertionError();
    }

    return recipientSettingsForSync.get(0);
  }

  public @NonNull List<RecipientSettings> getPendingRecipientSyncUpdates() {
    String   query = TABLE_NAME + "." + DIRTY + " = ? AND " + TABLE_NAME + "." + STORAGE_SERVICE_ID + " NOT NULL AND " + TABLE_NAME + "." + ID + " != ?";
    String[] args  = new String[] { String.valueOf(DirtyState.UPDATE.getId()), Recipient.self().getId().serialize() };

    return getRecipientSettingsForSync(query, args);
  }

  public @NonNull List<RecipientSettings> getPendingRecipientSyncInsertions() {
    String   query = TABLE_NAME + "." + DIRTY + " = ? AND " + TABLE_NAME + "." + STORAGE_SERVICE_ID + " NOT NULL AND " + TABLE_NAME + "." + ID + " != ?";
    String[] args  = new String[] { String.valueOf(DirtyState.INSERT.getId()), Recipient.self().getId().serialize() };

    return getRecipientSettingsForSync(query, args);
  }

  public @NonNull List<RecipientSettings> getPendingRecipientSyncDeletions() {
    String   query = TABLE_NAME + "." + DIRTY + " = ? AND " + TABLE_NAME + "." + STORAGE_SERVICE_ID + " NOT NULL AND " + TABLE_NAME + "." + ID + " != ?";
    String[] args  = new String[] { String.valueOf(DirtyState.DELETE.getId()), Recipient.self().getId().serialize() };

    return getRecipientSettingsForSync(query, args);
  }

  public @Nullable RecipientSettings getByStorageId(@NonNull byte[] storageId) {
    List<RecipientSettings> result = getRecipientSettingsForSync(TABLE_NAME + "." + STORAGE_SERVICE_ID + " = ?", new String[] { Base64.encodeBytes(storageId) });

    if (result.size() > 0) {
      return result.get(0);
    }

    return null;
  }

  public void markNeedsSync(@NonNull RecipientId recipientId) {
    markDirty(recipientId, DirtyState.UPDATE);
  }

  public void applyStorageIdUpdates(@NonNull Map<RecipientId, StorageId> storageIds) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    db.beginTransaction();
    try {
      String query = ID + " = ?";

      for (Map.Entry<RecipientId, StorageId> entry : storageIds.entrySet()) {
        ContentValues values = new ContentValues();
        values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(entry.getValue().getRaw()));
        values.put(DIRTY, DirtyState.CLEAN.getId());

        db.update(TABLE_NAME, values, query, new String[] { entry.getKey().serialize() });
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void applyStorageSyncUpdates(@NonNull Collection<SignalContactRecord>               contactInserts,
                                      @NonNull Collection<RecordUpdate<SignalContactRecord>> contactUpdates,
                                      @NonNull Collection<SignalGroupV1Record>               groupV1Inserts,
                                      @NonNull Collection<RecordUpdate<SignalGroupV1Record>> groupV1Updates,
                                      @NonNull Collection<SignalGroupV2Record>               groupV2Inserts,
                                      @NonNull Collection<RecordUpdate<SignalGroupV2Record>> groupV2Updates)
  {
    SQLiteDatabase   db               = databaseHelper.getWritableDatabase();
    IdentityDatabase identityDatabase = DatabaseFactory.getIdentityDatabase(context);
    ThreadDatabase   threadDatabase   = DatabaseFactory.getThreadDatabase(context);
    Set<RecipientId> needsRefresh     = new HashSet<>();

    db.beginTransaction();

    try {
      for (SignalContactRecord insert : contactInserts) {
        ContentValues values      = getValuesForStorageContact(insert, true);
        long          id          = db.insertWithOnConflict(TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_IGNORE);
        RecipientId   recipientId = null;

        if (id < 0) {
          values = getValuesForStorageContact(insert, false);
          Log.w(TAG, "Failed to insert! It's likely that these were newly-registered users that were missed in the merge. Doing an update instead.");

          if (insert.getAddress().getNumber().isPresent()) {
            try {
              int count = db.update(TABLE_NAME, values, PHONE + " = ?", new String[] { insert.getAddress().getNumber().get() });
              Log.w(TAG, "Updated " + count + " users by E164.");
            } catch (SQLiteConstraintException e) {
              Log.w(TAG,  "[applyStorageSyncUpdates -- Insert] Failed to update the UUID on an existing E164 user. Possibly merging.");
              recipientId = getAndPossiblyMerge(insert.getAddress().getUuid().get(), insert.getAddress().getNumber().get(), true);
              Log.w(TAG,  "[applyStorageSyncUpdates -- Insert] Resulting id: " + recipientId);
            }
          }

          if (recipientId == null && insert.getAddress().getUuid().isPresent()) {
            try {
              int count = db.update(TABLE_NAME, values, UUID + " = ?", new String[] { insert.getAddress().getUuid().get().toString() });
              Log.w(TAG, "Updated " + count + " users by UUID.");
            } catch (SQLiteConstraintException e) {
              Log.w(TAG,  "[applyStorageSyncUpdates -- Insert] Failed to update the E164 on an existing UUID user. Possibly merging.");
              recipientId = getAndPossiblyMerge(insert.getAddress().getUuid().get(), insert.getAddress().getNumber().get(), true);
              Log.w(TAG,  "[applyStorageSyncUpdates -- Insert] Resulting id: " + recipientId);
            }
          }

          if (recipientId == null && insert.getAddress().getNumber().isPresent()) {
            recipientId = getByE164(insert.getAddress().getNumber().get()).orNull();
          }

          if (recipientId == null && insert.getAddress().getUuid().isPresent()) {
            recipientId = getByUuid(insert.getAddress().getUuid().get()).orNull();
          }

          if (recipientId == null) {
            Log.w(TAG, "Failed to recover from a failed insert!");
            continue;
          }
        } else {
          recipientId = RecipientId.from(id);
        }

        if (insert.getIdentityKey().isPresent()) {
          try {
            IdentityKey identityKey = new IdentityKey(insert.getIdentityKey().get(), 0);

            DatabaseFactory.getIdentityDatabase(context).updateIdentityAfterSync(recipientId, identityKey, StorageSyncModels.remoteToLocalIdentityStatus(insert.getIdentityState()));
          } catch (InvalidKeyException e) {
            Log.w(TAG, "Failed to process identity key during insert! Skipping.", e);
          }
        }

        threadDatabase.setArchived(recipientId, insert.isArchived());
        needsRefresh.add(recipientId);
      }

      for (RecordUpdate<SignalContactRecord> update : contactUpdates) {
        ContentValues values = getValuesForStorageContact(update.getNew(), false);

        try {
          int updateCount = db.update(TABLE_NAME, values, STORAGE_SERVICE_ID + " = ?", new String[]{Base64.encodeBytes(update.getOld().getId().getRaw())});
          if (updateCount < 1) {
            throw new AssertionError("Had an update, but it didn't match any rows!");
          }
        } catch (SQLiteConstraintException e) {
          Log.w(TAG,  "[applyStorageSyncUpdates -- Update] Failed to update a user by storageId.");

          RecipientId recipientId = getByColumn(STORAGE_SERVICE_ID, Base64.encodeBytes(update.getOld().getId().getRaw())).get();
          Log.w(TAG,  "[applyStorageSyncUpdates -- Update] Found user " + recipientId + ". Possibly merging.");

          recipientId = getAndPossiblyMerge(update.getNew().getAddress().getUuid().orNull(), update.getNew().getAddress().getNumber().orNull(), true);
          Log.w(TAG,  "[applyStorageSyncUpdates -- Update] Merged into " + recipientId);

          db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(recipientId));
        }

        RecipientId recipientId = getByStorageKeyOrThrow(update.getNew().getId().getRaw());

        if (StorageSyncHelper.profileKeyChanged(update)) {
          clearProfileKeyCredential(recipientId);
        }

        try {
          Optional<IdentityRecord> oldIdentityRecord = identityDatabase.getIdentity(recipientId);

          if (update.getNew().getIdentityKey().isPresent()) {
            IdentityKey identityKey = new IdentityKey(update.getNew().getIdentityKey().get(), 0);
            DatabaseFactory.getIdentityDatabase(context).updateIdentityAfterSync(recipientId, identityKey, StorageSyncModels.remoteToLocalIdentityStatus(update.getNew().getIdentityState()));
          }

          Optional<IdentityRecord> newIdentityRecord = identityDatabase.getIdentity(recipientId);

          if ((newIdentityRecord.isPresent() && newIdentityRecord.get().getVerifiedStatus() == IdentityDatabase.VerifiedStatus.VERIFIED) &&
              (!oldIdentityRecord.isPresent() || oldIdentityRecord.get().getVerifiedStatus() != IdentityDatabase.VerifiedStatus.VERIFIED))
          {
            IdentityUtil.markIdentityVerified(context, Recipient.resolved(recipientId), true, true);
          } else if ((newIdentityRecord.isPresent() && newIdentityRecord.get().getVerifiedStatus() != IdentityDatabase.VerifiedStatus.VERIFIED) &&
                     (oldIdentityRecord.isPresent() && oldIdentityRecord.get().getVerifiedStatus() == IdentityDatabase.VerifiedStatus.VERIFIED))
          {
            IdentityUtil.markIdentityVerified(context, Recipient.resolved(recipientId), false, true);
          }
        } catch (InvalidKeyException e) {
          Log.w(TAG, "Failed to process identity key during update! Skipping.", e);
        }

        threadDatabase.setArchived(recipientId, update.getNew().isArchived());
        needsRefresh.add(recipientId);
      }

      for (SignalGroupV1Record insert : groupV1Inserts) {
        db.insertOrThrow(TABLE_NAME, null, getValuesForStorageGroupV1(insert));

        Recipient recipient = Recipient.externalGroup(context, GroupId.v1orThrow(insert.getGroupId()));

        threadDatabase.setArchived(recipient.getId(), insert.isArchived());
        needsRefresh.add(recipient.getId());
      }

      for (RecordUpdate<SignalGroupV1Record> update : groupV1Updates) {
        ContentValues values      = getValuesForStorageGroupV1(update.getNew());
        int           updateCount = db.update(TABLE_NAME, values, STORAGE_SERVICE_ID + " = ?", new String[]{Base64.encodeBytes(update.getOld().getId().getRaw())});

        if (updateCount < 1) {
          throw new AssertionError("Had an update, but it didn't match any rows!");
        }

        Recipient recipient = Recipient.externalGroup(context, GroupId.v1orThrow(update.getOld().getGroupId()));

        threadDatabase.setArchived(recipient.getId(), update.getNew().isArchived());
        needsRefresh.add(recipient.getId());
      }
      
      for (SignalGroupV2Record insert : groupV2Inserts) {
        db.insertOrThrow(TABLE_NAME, null, getValuesForStorageGroupV2(insert));

        GroupId.V2 groupId   = GroupId.v2(insert.getMasterKey());
        Recipient  recipient = Recipient.externalGroup(context, groupId);

        ApplicationDependencies.getJobManager().add(new WakeGroupV2Job(insert.getMasterKey()));

        threadDatabase.setArchived(recipient.getId(), insert.isArchived());
        needsRefresh.add(recipient.getId());
      }

      for (RecordUpdate<SignalGroupV2Record> update : groupV2Updates) {
        ContentValues values      = getValuesForStorageGroupV2(update.getNew());
        int           updateCount = db.update(TABLE_NAME, values, STORAGE_SERVICE_ID + " = ?", new String[]{Base64.encodeBytes(update.getOld().getId().getRaw())});

        if (updateCount < 1) {
          throw new AssertionError("Had an update, but it didn't match any rows!");
        }

        Recipient recipient = Recipient.externalGroup(context, GroupId.v2(update.getOld().getMasterKey()));

        threadDatabase.setArchived(recipient.getId(), update.getNew().isArchived());
        needsRefresh.add(recipient.getId());
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    for (RecipientId id : needsRefresh) {
      Recipient.live(id).refresh();
    }
  }

  public void applyStorageSyncUpdates(@NonNull StorageId storageId, SignalAccountRecord update) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    ContentValues        values      = new ContentValues();
    ProfileName          profileName = ProfileName.fromParts(update.getGivenName().orNull(), update.getFamilyName().orNull());
    Optional<ProfileKey> localKey    = ProfileKeyUtil.profileKeyOptional(Recipient.self().getProfileKey());
    Optional<ProfileKey> remoteKey   = ProfileKeyUtil.profileKeyOptional(update.getProfileKey().orNull());
    String               profileKey  = remoteKey.or(localKey).transform(ProfileKey::serialize).transform(Base64::encodeBytes).orNull();

    if (!remoteKey.isPresent()) {
      Log.w(TAG, "Got an empty profile key while applying an account record update!");
    }

    values.put(PROFILE_GIVEN_NAME, profileName.getGivenName());
    values.put(PROFILE_FAMILY_NAME, profileName.getFamilyName());
    values.put(PROFILE_JOINED_NAME, profileName.toString());
    values.put(PROFILE_KEY, profileKey);
    values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(update.getId().getRaw()));
    values.put(DIRTY, DirtyState.CLEAN.getId());

    int updateCount = db.update(TABLE_NAME, values, STORAGE_SERVICE_ID + " = ?", new String[]{Base64.encodeBytes(storageId.getRaw())});
    if (updateCount < 1) {
      throw new AssertionError("Account update didn't match any rows!");
    }

    if (!remoteKey.equals(localKey)) {
      ApplicationDependencies.getJobManager().add(new RefreshAttributesJob());
    }

    Recipient.self().live().refresh();
  }

  public void updatePhoneNumbers(@NonNull Map<String, String> mapping) {
    if (mapping.isEmpty()) return;

    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    db.beginTransaction();
    try {
      String query = PHONE + " = ?";

      for (Map.Entry<String, String> entry : mapping.entrySet()) {
        ContentValues values = new ContentValues();
        values.put(PHONE, entry.getValue());

        db.updateWithOnConflict(TABLE_NAME, values, query, new String[] { entry.getKey() }, SQLiteDatabase.CONFLICT_IGNORE);
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  private @NonNull RecipientId getByStorageKeyOrThrow(byte[] storageKey) {
    SQLiteDatabase db    = databaseHelper.getReadableDatabase();
    String         query = STORAGE_SERVICE_ID + " = ?";
    String[]       args  = new String[]{Base64.encodeBytes(storageKey)};

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        long id = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        return RecipientId.from(id);
      } else {
        throw new AssertionError("No recipient with that storage key!");
      }
    }
  }

  private static @NonNull ContentValues getValuesForStorageContact(@NonNull SignalContactRecord contact, boolean isInsert) {
    ContentValues values = new ContentValues();

    if (contact.getAddress().getUuid().isPresent()) {
      values.put(UUID, contact.getAddress().getUuid().get().toString());
    }

    ProfileName profileName = ProfileName.fromParts(contact.getGivenName().orNull(), contact.getFamilyName().orNull());
    String      username    = contact.getUsername().orNull();

    values.put(PHONE, contact.getAddress().getNumber().orNull());
    values.put(PROFILE_GIVEN_NAME, profileName.getGivenName());
    values.put(PROFILE_FAMILY_NAME, profileName.getFamilyName());
    values.put(PROFILE_JOINED_NAME, profileName.toString());
    values.put(PROFILE_KEY, contact.getProfileKey().transform(Base64::encodeBytes).orNull());
    values.put(USERNAME, TextUtils.isEmpty(username) ? null : username);
    values.put(PROFILE_SHARING, contact.isProfileSharingEnabled() ? "1" : "0");
    values.put(BLOCKED, contact.isBlocked() ? "1" : "0");
    values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(contact.getId().getRaw()));
    values.put(DIRTY, DirtyState.CLEAN.getId());

    if (contact.isProfileSharingEnabled() && isInsert) {
      values.put(COLOR, ContactColors.generateFor(profileName.toString()).serialize());
    }

    return values;
  }

  private static @NonNull ContentValues getValuesForStorageGroupV1(@NonNull SignalGroupV1Record groupV1) {
    ContentValues values = new ContentValues();
    values.put(GROUP_ID, GroupId.v1orThrow(groupV1.getGroupId()).toString());
    values.put(GROUP_TYPE, GroupType.SIGNAL_V1.getId());
    values.put(PROFILE_SHARING, groupV1.isProfileSharingEnabled() ? "1" : "0");
    values.put(BLOCKED, groupV1.isBlocked() ? "1" : "0");
    values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(groupV1.getId().getRaw()));
    values.put(DIRTY, DirtyState.CLEAN.getId());
    return values;
  }
  
  private static @NonNull ContentValues getValuesForStorageGroupV2(@NonNull SignalGroupV2Record groupV2) {
    ContentValues values = new ContentValues();
    values.put(GROUP_ID, GroupId.v2(groupV2.getMasterKey()).toString());
    values.put(GROUP_TYPE, GroupType.SIGNAL_V2.getId());
    values.put(PROFILE_SHARING, groupV2.isProfileSharingEnabled() ? "1" : "0");
    values.put(BLOCKED, groupV2.isBlocked() ? "1" : "0");
    values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(groupV2.getId().getRaw()));
    values.put(DIRTY, DirtyState.CLEAN.getId());
    return values;
  }

  private List<RecipientSettings> getRecipientSettingsForSync(@Nullable String query, @Nullable String[] args) {
    SQLiteDatabase          db    = databaseHelper.getReadableDatabase();
    String                  table = TABLE_NAME + " LEFT OUTER JOIN " + IdentityDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + ID + " = " + IdentityDatabase.TABLE_NAME + "." + IdentityDatabase.RECIPIENT_ID
                                               + " LEFT OUTER JOIN " + GroupDatabase.TABLE_NAME + " ON " + TABLE_NAME + "." + GROUP_ID + " = " + GroupDatabase.TABLE_NAME + "." + GroupDatabase.GROUP_ID;
    List<RecipientSettings> out   = new ArrayList<>();

    String[] columns = ArrayUtils.concat(RECIPIENT_FULL_PROJECTION,
      new String[]{GroupDatabase.TABLE_NAME + "." + GroupDatabase.V2_MASTER_KEY });

    try (Cursor cursor = db.query(table, columns, query, args, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        out.add(getRecipientSettings(context, cursor));
      }
    }

    return out;
  }

  /**
   * @return All storage ids for ContactRecords, excluding the ones that need to be deleted.
   */
  public List<StorageId> getContactStorageSyncIds() {
    return new ArrayList<>(getContactStorageSyncIdsMap().values());
  }

  /**
   * @return All storage IDs for ContactRecords, excluding the ones that need to be deleted.
   */
  public @NonNull Map<RecipientId, StorageId> getContactStorageSyncIdsMap() {
    SQLiteDatabase              db    = databaseHelper.getReadableDatabase();
    String                      query = STORAGE_SERVICE_ID + " NOT NULL AND " + DIRTY + " != ? AND " + ID + " != ? AND " + GROUP_TYPE + " != ?";
    String[]                    args  = { String.valueOf(DirtyState.DELETE), Recipient.self().getId().serialize(), String.valueOf(GroupType.SIGNAL_V2.getId()) };
    Map<RecipientId, StorageId> out   = new HashMap<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { ID, STORAGE_SERVICE_ID, GROUP_TYPE }, query, args, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        RecipientId id         = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
        String      encodedKey = cursor.getString(cursor.getColumnIndexOrThrow(STORAGE_SERVICE_ID));
        GroupType   groupType  = GroupType.fromId(cursor.getInt(cursor.getColumnIndexOrThrow(GROUP_TYPE)));
        byte[]      key        = Base64.decodeOrThrow(encodedKey);

        switch (groupType) {
          case NONE      : out.put(id, StorageId.forContact(key)); break;
          case SIGNAL_V1 : out.put(id, StorageId.forGroupV1(key)); break;
          default        : throw new AssertionError();
        }
      }
    }

    for (GroupId.V2 id : DatabaseFactory.getGroupDatabase(context).getAllGroupV2Ids()) {
      Recipient         recipient                = Recipient.externalGroup(context, id);
      RecipientId       recipientId              = recipient.getId();
      RecipientSettings recipientSettingsForSync = getRecipientSettingsForSync(recipientId);

      if (recipientSettingsForSync == null) {
        throw new AssertionError();
      }

      byte[] key = recipientSettingsForSync.storageId;

      if (key == null) {
        throw new AssertionError();
      }

      out.put(recipientId, StorageId.forGroupV2(key));
    }

    return out;
  }

  static @NonNull RecipientSettings getRecipientSettings(@NonNull Context context, @NonNull Cursor cursor) {
    long    id                         = CursorUtil.requireLong(cursor, ID);
    UUID    uuid                       = UuidUtil.parseOrNull(CursorUtil.requireString(cursor, UUID));
    String  username                   = CursorUtil.requireString(cursor, USERNAME);
    String  e164                       = CursorUtil.requireString(cursor, PHONE);
    String  email                      = CursorUtil.requireString(cursor, EMAIL);
    GroupId groupId                    = GroupId.parseNullableOrThrow(CursorUtil.requireString(cursor, GROUP_ID));
    int     groupType                  = CursorUtil.requireInt(cursor, GROUP_TYPE);
    boolean blocked                    = CursorUtil.requireBoolean(cursor, BLOCKED);
    String  messageRingtone            = CursorUtil.requireString(cursor, MESSAGE_RINGTONE);
    String  callRingtone               = CursorUtil.requireString(cursor, CALL_RINGTONE);
    int     messageVibrateState        = CursorUtil.requireInt(cursor, MESSAGE_VIBRATE);
    int     callVibrateState           = CursorUtil.requireInt(cursor, CALL_VIBRATE);
    long    muteUntil                  = cursor.getLong(cursor.getColumnIndexOrThrow(MUTE_UNTIL));
    String  serializedColor            = CursorUtil.requireString(cursor, COLOR);
    int     insightsBannerTier         = CursorUtil.requireInt(cursor, SEEN_INVITE_REMINDER);
    int     defaultSubscriptionId      = CursorUtil.requireInt(cursor, DEFAULT_SUBSCRIPTION_ID);
    int     expireMessages             = CursorUtil.requireInt(cursor, MESSAGE_EXPIRATION_TIME);
    int     registeredState            = CursorUtil.requireInt(cursor, REGISTERED);
    String  profileKeyString           = CursorUtil.requireString(cursor, PROFILE_KEY);
    String  profileKeyCredentialString = CursorUtil.requireString(cursor, PROFILE_KEY_CREDENTIAL);
    String  systemDisplayName          = CursorUtil.requireString(cursor, SYSTEM_DISPLAY_NAME);
    String  systemContactPhoto         = CursorUtil.requireString(cursor, SYSTEM_PHOTO_URI);
    String  systemPhoneLabel           = CursorUtil.requireString(cursor, SYSTEM_PHONE_LABEL);
    String  systemContactUri           = CursorUtil.requireString(cursor, SYSTEM_CONTACT_URI);
    String  profileGivenName           = CursorUtil.requireString(cursor, PROFILE_GIVEN_NAME);
    String  profileFamilyName          = CursorUtil.requireString(cursor, PROFILE_FAMILY_NAME);
    String  signalProfileAvatar        = CursorUtil.requireString(cursor, SIGNAL_PROFILE_AVATAR);
    boolean profileSharing             = CursorUtil.requireBoolean(cursor, PROFILE_SHARING);
    long    lastProfileFetch           = cursor.getLong(cursor.getColumnIndexOrThrow(LAST_PROFILE_FETCH));
    String  notificationChannel        = CursorUtil.requireString(cursor, NOTIFICATION_CHANNEL);
    int     unidentifiedAccessMode     = CursorUtil.requireInt(cursor, UNIDENTIFIED_ACCESS_MODE);
    boolean forceSmsSelection          = CursorUtil.requireBoolean(cursor, FORCE_SMS_SELECTION);
    int     uuidCapabilityValue        = CursorUtil.requireInt(cursor, UUID_CAPABILITY);
    int     groupsV2CapabilityValue    = CursorUtil.requireInt(cursor, GROUPS_V2_CAPABILITY);
    String  storageKeyRaw              = CursorUtil.requireString(cursor, STORAGE_SERVICE_ID);
    int     mentionSettingId           = CursorUtil.requireInt(cursor, MENTION_SETTING);

    Optional<String>  identityKeyRaw    = CursorUtil.getString(cursor, IDENTITY_KEY);
    Optional<Integer> identityStatusRaw = CursorUtil.getInt(cursor, IDENTITY_STATUS);

    int masterKeyIndex = cursor.getColumnIndex(GroupDatabase.V2_MASTER_KEY);
    GroupMasterKey groupMasterKey = null;
    try {
      if (masterKeyIndex != -1) {
        byte[] blob = cursor.getBlob(masterKeyIndex);
        if (blob != null) {
          groupMasterKey = new GroupMasterKey(blob);
        }
      }
    } catch (InvalidInputException e) {
      throw new AssertionError(e);
    }

    MaterialColor color;
    byte[]        profileKey           = null;
    byte[]        profileKeyCredential = null;

    try {
      color = serializedColor == null ? null : MaterialColor.fromSerialized(serializedColor);
    } catch (MaterialColor.UnknownColorException e) {
      Log.w(TAG, e);
      color = null;
    }

    if (profileKeyString != null) {
      try {
        profileKey = Base64.decode(profileKeyString);
      } catch (IOException e) {
        Log.w(TAG, e);
        profileKey = null;
      }

      if (profileKeyCredentialString != null) {
        try {
          profileKeyCredential = Base64.decode(profileKeyCredentialString);
        } catch (IOException e) {
          Log.w(TAG, e);
          profileKeyCredential = null;
        }
      }
    }

    byte[] storageKey  = storageKeyRaw != null ? Base64.decodeOrThrow(storageKeyRaw) : null;
    byte[] identityKey = identityKeyRaw.transform(Base64::decodeOrThrow).orNull();

    IdentityDatabase.VerifiedStatus identityStatus = identityStatusRaw.transform(IdentityDatabase.VerifiedStatus::forState).or(IdentityDatabase.VerifiedStatus.DEFAULT);

    return new RecipientSettings(RecipientId.from(id), uuid, username, e164, email, groupId, groupMasterKey, GroupType.fromId(groupType), blocked, muteUntil,
                                 VibrateState.fromId(messageVibrateState),
                                 VibrateState.fromId(callVibrateState),
                                 Util.uri(messageRingtone), Util.uri(callRingtone),
                                 color, defaultSubscriptionId, expireMessages,
                                 RegisteredState.fromId(registeredState),
                                 profileKey, profileKeyCredential,
                                 systemDisplayName, systemContactPhoto,
                                 systemPhoneLabel, systemContactUri,
                                 ProfileName.fromParts(profileGivenName, profileFamilyName), signalProfileAvatar,
                                 AvatarHelper.hasAvatar(context, RecipientId.from(id)), profileSharing, lastProfileFetch,
                                 notificationChannel, UnidentifiedAccessMode.fromMode(unidentifiedAccessMode),
                                 forceSmsSelection,
                                 Recipient.Capability.deserialize(uuidCapabilityValue),
                                 Recipient.Capability.deserialize(groupsV2CapabilityValue),
                                 InsightsBannerTier.fromId(insightsBannerTier),
                                 storageKey, identityKey, identityStatus, MentionSetting.fromId(mentionSettingId));
  }

  public BulkOperationsHandle beginBulkSystemContactUpdate() {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();
    database.beginTransaction();

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SYSTEM_INFO_PENDING, 1);

    database.update(TABLE_NAME, contentValues, SYSTEM_CONTACT_URI + " NOT NULL", null);

    return new BulkOperationsHandle(database);
  }

  public void setColor(@NonNull RecipientId id, @NonNull MaterialColor color) {
    ContentValues values = new ContentValues();
    values.put(COLOR, color.serialize());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setColorIfNotSet(@NonNull RecipientId id, @NonNull MaterialColor color) {
    if (setColorIfNotSetInternal(id, color)) {
      Recipient.live(id).refresh();
    }
  }

  private boolean setColorIfNotSetInternal(@NonNull RecipientId id, @NonNull MaterialColor color) {
    SQLiteDatabase db    = databaseHelper.getWritableDatabase();
    String         query = ID + " = ? AND " + COLOR + " IS NULL";
    String[]       args  = new String[]{ id.serialize() };

    ContentValues values = new ContentValues();
    values.put(COLOR, color.serialize());

    return db.update(TABLE_NAME, values, query, args) > 0;
  }

  public void setDefaultSubscriptionId(@NonNull RecipientId id, int defaultSubscriptionId) {
    ContentValues values = new ContentValues();
    values.put(DEFAULT_SUBSCRIPTION_ID, defaultSubscriptionId);
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setForceSmsSelection(@NonNull RecipientId id, boolean forceSmsSelection) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(FORCE_SMS_SELECTION, forceSmsSelection ? 1 : 0);
    if (update(id, contentValues)) {
      Recipient.live(id).refresh();
    }
  }

  public void setBlocked(@NonNull RecipientId id, boolean blocked) {
    ContentValues values = new ContentValues();
    values.put(BLOCKED, blocked ? 1 : 0);
    if (update(id, values)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
    }
  }

  public void setMessageRingtone(@NonNull RecipientId id, @Nullable Uri notification) {
    ContentValues values = new ContentValues();
    values.put(MESSAGE_RINGTONE, notification == null ? null : notification.toString());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setCallRingtone(@NonNull RecipientId id, @Nullable Uri ringtone) {
    ContentValues values = new ContentValues();
    values.put(CALL_RINGTONE, ringtone == null ? null : ringtone.toString());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setMessageVibrate(@NonNull RecipientId id, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(MESSAGE_VIBRATE, enabled.getId());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setCallVibrate(@NonNull RecipientId id, @NonNull VibrateState enabled) {
    ContentValues values = new ContentValues();
    values.put(CALL_VIBRATE, enabled.getId());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setMuted(@NonNull RecipientId id, long until) {
    ContentValues values = new ContentValues();
    values.put(MUTE_UNTIL, until);
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setSeenFirstInviteReminder(@NonNull RecipientId id) {
    setInsightsBannerTier(id, InsightsBannerTier.TIER_ONE);
  }

  public void setSeenSecondInviteReminder(@NonNull RecipientId id) {
    setInsightsBannerTier(id, InsightsBannerTier.TIER_TWO);
  }

  public void setHasSentInvite(@NonNull RecipientId id) {
    setSeenSecondInviteReminder(id);
  }

  private void setInsightsBannerTier(@NonNull RecipientId id, @NonNull InsightsBannerTier insightsBannerTier) {
    SQLiteDatabase database  = databaseHelper.getWritableDatabase();
    ContentValues  values    = new ContentValues(1);
    String         query     = ID + " = ? AND " + SEEN_INVITE_REMINDER + " < ?";
    String[]       args      = new String[]{ id.serialize(), String.valueOf(insightsBannerTier) };

    values.put(SEEN_INVITE_REMINDER, insightsBannerTier.id);
    database.update(TABLE_NAME, values, query, args);
    Recipient.live(id).refresh();
  }

  public void setExpireMessages(@NonNull RecipientId id, int expiration) {
    ContentValues values = new ContentValues(1);
    values.put(MESSAGE_EXPIRATION_TIME, expiration);
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setUnidentifiedAccessMode(@NonNull RecipientId id, @NonNull UnidentifiedAccessMode unidentifiedAccessMode) {
    ContentValues values = new ContentValues(1);
    values.put(UNIDENTIFIED_ACCESS_MODE, unidentifiedAccessMode.getMode());
    if (update(id, values)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
    }
  }

  public void setCapabilities(@NonNull RecipientId id, @NonNull SignalServiceProfile.Capabilities capabilities) {
    ContentValues values = new ContentValues(2);
    values.put(UUID_CAPABILITY,      Recipient.Capability.fromBoolean(capabilities.isUuid()).serialize());
    values.put(GROUPS_V2_CAPABILITY, Recipient.Capability.fromBoolean(capabilities.isGv2()).serialize());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  public void setMentionSetting(@NonNull RecipientId id, @NonNull MentionSetting mentionSetting) {
    ContentValues values = new ContentValues();
    values.put(MENTION_SETTING, mentionSetting.getId());
    if (update(id, values)) {
      Recipient.live(id).refresh();
    }
  }

  /**
   * Updates the profile key.
   * <p>
   * If it changes, it clears out the profile key credential and resets the unidentified access mode.
   * @return true iff changed.
   */
  public boolean setProfileKey(@NonNull RecipientId id, @NonNull ProfileKey profileKey) {
    String        selection         = ID + " = ?";
    String[]      args              = new String[]{id.serialize()};
    ContentValues valuesToCompare   = new ContentValues(1);
    ContentValues valuesToSet       = new ContentValues(3);
    String        encodedProfileKey = Base64.encodeBytes(profileKey.serialize());

    valuesToCompare.put(PROFILE_KEY, encodedProfileKey);

    valuesToSet.put(PROFILE_KEY, encodedProfileKey);
    valuesToSet.putNull(PROFILE_KEY_CREDENTIAL);
    valuesToSet.put(UNIDENTIFIED_ACCESS_MODE, UnidentifiedAccessMode.UNKNOWN.getMode());

    SqlUtil.UpdateQuery updateQuery = SqlUtil.buildTrueUpdateQuery(selection, args, valuesToCompare);

    if (update(updateQuery, valuesToSet)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
      StorageSyncHelper.scheduleSyncForDataChange();
      return true;
    }
    return false;
  }

  /**
   * Sets the profile key iff currently null.
   * <p>
   * If it sets it, it also clears out the profile key credential and resets the unidentified access mode.
   * @return true iff changed.
   */
  public boolean setProfileKeyIfAbsent(@NonNull RecipientId id, @NonNull ProfileKey profileKey) {
    SQLiteDatabase database    = databaseHelper.getWritableDatabase();
    String         selection   = ID + " = ? AND " + PROFILE_KEY + " is NULL";
    String[]       args        = new String[]{id.serialize()};
    ContentValues  valuesToSet = new ContentValues(3);

    valuesToSet.put(PROFILE_KEY, Base64.encodeBytes(profileKey.serialize()));
    valuesToSet.putNull(PROFILE_KEY_CREDENTIAL);
    valuesToSet.put(UNIDENTIFIED_ACCESS_MODE, UnidentifiedAccessMode.UNKNOWN.getMode());

    if (database.update(TABLE_NAME, valuesToSet, selection, args) > 0) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
      return true;
    } else {
      return false;
    }
  }

  /**
   * Updates the profile key credential as long as the profile key matches.
   */
  public void setProfileKeyCredential(@NonNull RecipientId id,
                                      @NonNull ProfileKey profileKey,
                                      @NonNull ProfileKeyCredential profileKeyCredential)
  {
    String        selection = ID + " = ? AND " + PROFILE_KEY + " = ?";
    String[]      args      = new String[]{id.serialize(), Base64.encodeBytes(profileKey.serialize())};
    ContentValues values    = new ContentValues(1);

    values.put(PROFILE_KEY_CREDENTIAL, Base64.encodeBytes(profileKeyCredential.serialize()));

    SqlUtil.UpdateQuery updateQuery = SqlUtil.buildTrueUpdateQuery(selection, args, values);

    if (update(updateQuery, values)) {
      // TODO [greyson] If we sync this in future, mark dirty
      //markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
    }
  }

  private void clearProfileKeyCredential(@NonNull RecipientId id) {
    ContentValues values = new ContentValues(1);
    values.putNull(PROFILE_KEY_CREDENTIAL);
    if (update(id, values)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
    }
  }

  /**
   * Fills in gaps (nulls) in profile key knowledge from new profile keys.
   * <p>
   * If from authoritative source, this will overwrite local, otherwise it will only write to the
   * database if missing.
   */
  public Set<RecipientId> persistProfileKeySet(@NonNull ProfileKeySet profileKeySet) {
    Map<UUID, ProfileKey> profileKeys              = profileKeySet.getProfileKeys();
    Map<UUID, ProfileKey> authoritativeProfileKeys = profileKeySet.getAuthoritativeProfileKeys();
    int                   totalKeys                = profileKeys.size() + authoritativeProfileKeys.size();

    if (totalKeys == 0) {
      return Collections.emptySet();
    }

    Log.i(TAG, String.format(Locale.US, "Persisting %d Profile keys, %d of which are authoritative", totalKeys, authoritativeProfileKeys.size()));

    HashSet<RecipientId> updated = new HashSet<>(totalKeys);
    RecipientId          selfId  = Recipient.self().getId();

    for (Map.Entry<UUID, ProfileKey> entry : profileKeys.entrySet()) {
      RecipientId recipientId = getOrInsertFromUuid(entry.getKey());

      if (setProfileKeyIfAbsent(recipientId, entry.getValue())) {
        Log.i(TAG, "Learned new profile key");
        updated.add(recipientId);
      }
    }

    for (Map.Entry<UUID, ProfileKey> entry : authoritativeProfileKeys.entrySet()) {
      RecipientId recipientId = getOrInsertFromUuid(entry.getKey());

      if (selfId.equals(recipientId)) {
        Log.i(TAG, "Seen authoritative update for self");
        if (!entry.getValue().equals(ProfileKeyUtil.getSelfProfileKey())) {
          Log.w(TAG, "Seen authoritative update for self that didn't match local, scheduling storage sync");
          StorageSyncHelper.scheduleSyncForDataChange();
        }
      } else {
        Log.i(TAG, String.format("Profile key from owner %s", recipientId));
        if (setProfileKey(recipientId, entry.getValue())) {
          Log.i(TAG, "Learned new profile key from owner");
          updated.add(recipientId);
        }
      }
    }

    return updated;
  }

  public void setProfileName(@NonNull RecipientId id, @NonNull ProfileName profileName) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PROFILE_GIVEN_NAME, profileName.getGivenName());
    contentValues.put(PROFILE_FAMILY_NAME, profileName.getFamilyName());
    contentValues.put(PROFILE_JOINED_NAME, profileName.toString());
    if (update(id, contentValues)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
      StorageSyncHelper.scheduleSyncForDataChange();
    }
  }

  public void setProfileAvatar(@NonNull RecipientId id, @Nullable String profileAvatar) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(SIGNAL_PROFILE_AVATAR, profileAvatar);
    if (update(id, contentValues)) {
      Recipient.live(id).refresh();

      if (id.equals(Recipient.self().getId())) {
        markDirty(id, DirtyState.UPDATE);
        StorageSyncHelper.scheduleSyncForDataChange();
      }
    }
  }

  public void setProfileSharing(@NonNull RecipientId id, @SuppressWarnings("SameParameterValue") boolean enabled) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PROFILE_SHARING, enabled ? 1 : 0);

    boolean profiledUpdated = update(id, contentValues);
    boolean colorUpdated    = enabled && setColorIfNotSetInternal(id, ContactColors.generateFor(Recipient.resolved(id).getDisplayName(context)));

    if (profiledUpdated || colorUpdated) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
      StorageSyncHelper.scheduleSyncForDataChange();
    }
  }

  public void setNotificationChannel(@NonNull RecipientId id, @Nullable String notificationChannel) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(NOTIFICATION_CHANNEL, notificationChannel);
    if (update(id, contentValues)) {
      Recipient.live(id).refresh();
    }
  }

  /**
   * @return True if setting the phone number resulted in changed recipientId, otherwise false.
   */
  public boolean setPhoneNumber(@NonNull RecipientId id, @NonNull String e164) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      setPhoneNumberOrThrow(id, e164);
      db.setTransactionSuccessful();
      return false;
    } catch (SQLiteConstraintException e) {
      Log.w(TAG, "[setPhoneNumber] Hit a conflict when trying to update " + id + ". Possibly merging.");

      RecipientSettings existing = getRecipientSettings(id);
      RecipientId       newId    = getAndPossiblyMerge(existing.getUuid(), e164, true);
      Log.w(TAG, "[setPhoneNumber] Resulting id: " + newId);

      db.setTransactionSuccessful();
      return !newId.equals(existing.getId());
    } finally {
      db.endTransaction();
    }
  }

  private void removePhoneNumber(@NonNull RecipientId recipientId, @NonNull SQLiteDatabase db) {
    ContentValues values = new ContentValues();
    values.putNull(PHONE);
    db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(recipientId));
  }

  /**
   * Should only use if you are confident that this will not result in any contact merging.
   */
  public void setPhoneNumberOrThrow(@NonNull RecipientId id, @NonNull String e164) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(PHONE, e164);

    if (update(id, contentValues)) {
      markDirty(id, DirtyState.UPDATE);
      Recipient.live(id).refresh();
      StorageSyncHelper.scheduleSyncForDataChange();
    }
  }

  public void setUsername(@NonNull RecipientId id, @Nullable String username) {
    if (username != null) {
      Optional<RecipientId> existingUsername = getByUsername(username);

      if (existingUsername.isPresent() && !id.equals(existingUsername.get())) {
        Log.i(TAG, "Username was previously thought to be owned by " + existingUsername.get() + ". Clearing their username.");
        setUsername(existingUsername.get(), null);
      }
    }

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(USERNAME, username);
    if (update(id, contentValues)) {
      Recipient.live(id).refresh();
      StorageSyncHelper.scheduleSyncForDataChange();
    }
  }

  public void clearUsernameIfExists(@NonNull String username) {
    Optional<RecipientId> existingUsername = getByUsername(username);

    if (existingUsername.isPresent()) {
      setUsername(existingUsername.get(), null);
    }
  }

  public Set<String> getAllPhoneNumbers() {
    SQLiteDatabase db      = databaseHelper.getReadableDatabase();
    Set<String>    results = new HashSet<>();

    try (Cursor cursor = db.query(TABLE_NAME, new String[] { PHONE }, null, null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        String number = cursor.getString(cursor.getColumnIndexOrThrow(PHONE));

        if (!TextUtils.isEmpty(number)) {
          results.add(number);
        }
      }
    }

    return results;
  }

  /**
   * @return True if setting the UUID resulted in changed recipientId, otherwise false.
   */
  public boolean markRegistered(@NonNull RecipientId id, @NonNull UUID uuid) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      markRegisteredOrThrow(id, uuid);
      db.setTransactionSuccessful();
      return false;
    } catch (SQLiteConstraintException e) {
      Log.w(TAG, "[markRegistered] Hit a conflict when trying to update " + id + ". Possibly merging.");

      RecipientSettings existing = getRecipientSettings(id);
      RecipientId       newId    = getAndPossiblyMerge(uuid, existing.getE164(), true);
      Log.w(TAG, "[markRegistered] Merged into " + newId);

      db.setTransactionSuccessful();
      return !newId.equals(existing.getId());
    } finally {
      db.endTransaction();
    }
  }

  /**
   * Should only use if you are confident that this shouldn't result in any contact merging.
   */
  public void markRegisteredOrThrow(@NonNull RecipientId id, @NonNull UUID uuid) {
    ContentValues contentValues = new ContentValues(2);
    contentValues.put(REGISTERED, RegisteredState.REGISTERED.getId());
    contentValues.put(UUID, uuid.toString().toLowerCase());

    if (update(id, contentValues)) {
      markDirty(id, DirtyState.INSERT);
      Recipient.live(id).refresh();
    }
  }

  /**
   * Marks the user as registered without providing a UUID. This should only be used when one
   * cannot be reasonably obtained. {@link #markRegistered(RecipientId, UUID)} should be strongly
   * preferred.
   */
  public void markRegistered(@NonNull RecipientId id) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, RegisteredState.REGISTERED.getId());
    if (update(id, contentValues)) {
      markDirty(id, DirtyState.INSERT);
      Recipient.live(id).refresh();
    }
  }

  public void markUnregistered(@NonNull RecipientId id) {
    ContentValues contentValues = new ContentValues(2);
    contentValues.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());
    if (update(id, contentValues)) {
      markDirty(id, DirtyState.DELETE);
      Recipient.live(id).refresh();
    }
  }

  public void bulkUpdatedRegisteredStatus(@NonNull Map<RecipientId, String> registered, Collection<RecipientId> unregistered) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      for (Map.Entry<RecipientId, String> entry : registered.entrySet()) {
        ContentValues values = new ContentValues(2);
        values.put(REGISTERED, RegisteredState.REGISTERED.getId());

        if (entry.getValue() != null) {
          values.put(UUID, entry.getValue().toLowerCase());
        }

        try {
          if (update(entry.getKey(), values)) {
            markDirty(entry.getKey(), DirtyState.INSERT);
          }
        } catch (SQLiteConstraintException e) {
          Log.w(TAG, "[bulkUpdateRegisteredStatus] Hit a conflict when trying to update " + entry.getKey() + ". Possibly merging.");

          RecipientSettings existing = getRecipientSettings(entry.getKey());
          RecipientId       newId    = getAndPossiblyMerge(UuidUtil.parseOrThrow(entry.getValue()), existing.getE164(), true);
          Log.w(TAG, "[bulkUpdateRegisteredStatus] Merged into " + newId);
        }
      }

      for (RecipientId id : unregistered) {
        ContentValues values = new ContentValues(2);
        values.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());
        values.put(UUID, (String) null);
        if (update(id, values)) {
          markDirty(id, DirtyState.DELETE);
        }
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  @Deprecated
  public void setRegistered(@NonNull RecipientId id, RegisteredState registeredState) {
    ContentValues contentValues = new ContentValues(1);
    contentValues.put(REGISTERED, registeredState.getId());

    if (update(id, contentValues)) {
      if (registeredState == RegisteredState.REGISTERED) {
        markDirty(id, DirtyState.INSERT);
      } else if (registeredState == RegisteredState.NOT_REGISTERED) {
        markDirty(id, DirtyState.DELETE);
      }

      Recipient.live(id).refresh();
    }
  }

  @Deprecated
  public void setRegistered(@NonNull Collection<RecipientId> activeIds,
                            @NonNull Collection<RecipientId> inactiveIds)
  {
    for (RecipientId activeId : activeIds) {
      ContentValues registeredValues = new ContentValues(1);
      registeredValues.put(REGISTERED, RegisteredState.REGISTERED.getId());

      if (update(activeId, registeredValues)) {
        markDirty(activeId, DirtyState.INSERT);
        Recipient.live(activeId).refresh();
      }
    }

    for (RecipientId inactiveId : inactiveIds) {
      ContentValues contentValues = new ContentValues(1);
      contentValues.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());

      if (update(inactiveId, contentValues)) {
        markDirty(inactiveId, DirtyState.DELETE);
        Recipient.live(inactiveId).refresh();
      }
    }
  }

  public @NonNull Map<RecipientId, String> bulkProcessCdsResult(@NonNull Map<String, UUID> mapping) {
    SQLiteDatabase               db      = databaseHelper.getWritableDatabase();
    HashMap<RecipientId, String> uuidMap = new HashMap<>();

    db.beginTransaction();
    try {
      for (Map.Entry<String, UUID> entry : mapping.entrySet()) {
        String                e164      = entry.getKey();
        UUID                  uuid      = entry.getValue();
        Optional<RecipientId> uuidEntry = uuid != null ? getByUuid(uuid) : Optional.absent();

        if (uuidEntry.isPresent()) {
          boolean idChanged = setPhoneNumber(uuidEntry.get(), e164);
          if (idChanged) {
            uuidEntry = getByUuid(Objects.requireNonNull(uuid));
          }
        }

        RecipientId id = uuidEntry.isPresent() ? uuidEntry.get() : getOrInsertFromE164(e164);

        uuidMap.put(id, uuid != null ? uuid.toString() : null);
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    return uuidMap;
  }

  public @NonNull List<RecipientId> getUninvitedRecipientsForInsights() {
    SQLiteDatabase    db      = databaseHelper.getReadableDatabase();
    List<RecipientId> results = new LinkedList<>();
    final String[]    args    = new String[]{String.valueOf(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(31))};

    try (Cursor cursor = db.rawQuery(INSIGHTS_INVITEE_LIST, args)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      }
    }

    return results;
  }

  public @NonNull List<RecipientId> getRegistered() {
    SQLiteDatabase    db      = databaseHelper.getReadableDatabase();
    List<RecipientId> results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, REGISTERED + " = ?", new String[] {"1"}, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      }
    }

    return results;
  }

  public List<RecipientId> getSystemContacts() {
    SQLiteDatabase    db      = databaseHelper.getReadableDatabase();
    List<RecipientId> results = new LinkedList<>();

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        results.add(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      }
    }

    return results;
  }

  public void updateSystemContactColors(@NonNull ColorUpdater updater) {
    SQLiteDatabase                  db      = databaseHelper.getReadableDatabase();
    Map<RecipientId, MaterialColor> updates = new HashMap<>();

    db.beginTransaction();
    try (Cursor cursor = db.query(TABLE_NAME, new String[] {ID, COLOR, SYSTEM_DISPLAY_NAME}, SYSTEM_DISPLAY_NAME + " IS NOT NULL AND " + SYSTEM_DISPLAY_NAME + " != \"\"", null, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        long          id       = cursor.getLong(cursor.getColumnIndexOrThrow(ID));
        MaterialColor newColor = updater.update(cursor.getString(cursor.getColumnIndexOrThrow(SYSTEM_DISPLAY_NAME)),
                                                cursor.getString(cursor.getColumnIndexOrThrow(COLOR)));

        ContentValues contentValues = new ContentValues(1);
        contentValues.put(COLOR, newColor.serialize());
        db.update(TABLE_NAME, contentValues, ID + " = ?", new String[] { String.valueOf(id) });

        updates.put(RecipientId.from(id), newColor);
      }
    } finally {
      db.setTransactionSuccessful();
      db.endTransaction();

      Stream.of(updates.entrySet()).forEach(entry -> Recipient.live(entry.getKey()).refresh());
    }
  }

  public @Nullable Cursor getSignalContacts(boolean includeSelf) {
    String   selection = BLOCKED    + " = ? AND "                                                     +
                         REGISTERED + " = ? AND "                                                     +
                         GROUP_ID   + " IS NULL AND "                                                 +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + PROFILE_SHARING + " = ?) AND " +
                         "(" + SORT_NAME + " NOT NULL OR " + USERNAME + " NOT NULL)";
    String[] args;

    if (includeSelf) {
      args = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), "1" };
    } else {
      selection += " AND " + ID + " != ?";
      args       = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), "1", Recipient.self().getId().serialize() };
    }

    String   orderBy   = SORT_NAME + ", " + SYSTEM_DISPLAY_NAME + ", " + SEARCH_PROFILE_NAME + ", " + USERNAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor querySignalContacts(@NonNull String query, boolean includeSelf) {
    query = buildCaseInsensitiveGlobPattern(query);

    String   selection = BLOCKED     + " = ? AND " +
                         REGISTERED  + " = ? AND " +
                         GROUP_ID    + " IS NULL AND " +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + PROFILE_SHARING + " = ?) AND " +
                         "(" +
                           PHONE     + " GLOB ? OR " +
                           SORT_NAME + " GLOB ? OR " +
                           USERNAME  + " GLOB ?" +
                         ")";
    String[] args;

    if (includeSelf) {
      args = new String[]{"0", String.valueOf(RegisteredState.REGISTERED.getId()), "1", query, query, query};
    } else {
      selection += " AND " + ID + " != ?";
      args       = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), "1", query, query, query, String.valueOf(Recipient.self().getId().toLong()) };
    }

    String   orderBy   = SORT_NAME + ", " + SYSTEM_DISPLAY_NAME + ", " + SEARCH_PROFILE_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor getNonSignalContacts() {
    String   selection = BLOCKED    + " = ? AND " +
                         REGISTERED + " != ? AND " +
                         GROUP_ID   + " IS NULL AND " +
                         SYSTEM_DISPLAY_NAME + " NOT NULL AND " +
                         "(" + PHONE + " NOT NULL OR " + EMAIL + " NOT NULL)";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()) };
    String   orderBy   = SYSTEM_DISPLAY_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor queryNonSignalContacts(@NonNull String query) {
    query = buildCaseInsensitiveGlobPattern(query);

    String   selection = BLOCKED    + " = ? AND " +
                         REGISTERED + " != ? AND " +
                         GROUP_ID   + " IS NULL AND " +
                         SYSTEM_DISPLAY_NAME + " NOT NULL AND " +
                         "(" + PHONE + " NOT NULL OR " + EMAIL + " NOT NULL) AND " +
                         "(" +
                           PHONE               + " GLOB ? OR " +
                           EMAIL               + " GLOB ? OR " +
                           SYSTEM_DISPLAY_NAME + " GLOB ?" +
                         ")";
    String[] args      = new String[] { "0", String.valueOf(RegisteredState.REGISTERED.getId()), query, query, query };
    String   orderBy   = SYSTEM_DISPLAY_NAME + ", " + PHONE;

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, orderBy);
  }

  public @Nullable Cursor queryAllContacts(@NonNull String query) {
    query = buildCaseInsensitiveGlobPattern(query);

    String   selection = BLOCKED + " = ? AND " +
                         "(" +
                           SORT_NAME + " GLOB ? OR " +
                           USERNAME  + " GLOB ? OR " +
                           PHONE     + " GLOB ? OR " +
                           EMAIL     + " GLOB ?" +
                         ")";
    String[] args      = new String[] { "0", query, query, query, query };

    return databaseHelper.getReadableDatabase().query(TABLE_NAME, SEARCH_PROJECTION, selection, args, null, null, null);
  }

  public @NonNull List<Recipient> queryRecipientsForMentions(@NonNull String query) {
    return queryRecipientsForMentions(query, null);
  }

  public @NonNull List<Recipient> queryRecipientsForMentions(@NonNull String query, @Nullable List<RecipientId> recipientIds) {
    query = buildCaseInsensitiveGlobPattern(query);

    String ids = null;
    if (Util.hasItems(recipientIds)) {
      ids = TextUtils.join(",", Stream.of(recipientIds).map(RecipientId::serialize).toList());
    }

    String   selection = BLOCKED + " = 0 AND " +
                         (ids != null ? ID + " IN (" + ids + ") AND " : "") +
                         SORT_NAME  + " GLOB ?";

    List<Recipient> recipients = new ArrayList<>();
    try (RecipientDatabase.RecipientReader reader = new RecipientReader(databaseHelper.getReadableDatabase().query(TABLE_NAME, MENTION_SEARCH_PROJECTION, selection, SqlUtil.buildArgs(query), null, null, SORT_NAME))) {
      Recipient recipient;
      while ((recipient = reader.getNext()) != null) {
        recipients.add(recipient);
      }
    }
    return recipients;
  }

  /**
   * Builds a case-insensitive GLOB pattern for fuzzy text queries. Works with all unicode
   * characters.
   *
   * Ex:
   *   cat -> [cC][aA][tT]
   */
  private static String buildCaseInsensitiveGlobPattern(@NonNull String query) {
    if (TextUtils.isEmpty(query)) {
      return "*";
    }

    StringBuilder pattern = new StringBuilder();

    for (int i = 0, len = query.codePointCount(0, query.length()); i < len; i++) {
      String point = StringUtil.codePointToString(query.codePointAt(i));

      pattern.append("[");
      pattern.append(point.toLowerCase());
      pattern.append(point.toUpperCase());
      pattern.append("]");
    }

    return "*" + pattern.toString() + "*";
  }

  public @NonNull List<Recipient> getRecipientsForMultiDeviceSync() {
    String   subquery  = "SELECT " + ThreadDatabase.TABLE_NAME + "." + ThreadDatabase.RECIPIENT_ID + " FROM " + ThreadDatabase.TABLE_NAME;
    String   selection = REGISTERED + " = ? AND " +
                         GROUP_ID   + " IS NULL AND " +
                         ID         + " != ? AND " +
                         "(" + SYSTEM_DISPLAY_NAME + " NOT NULL OR " + ID + " IN (" + subquery + "))";
    String[] args      = new String[] { String.valueOf(RegisteredState.REGISTERED.getId()), Recipient.self().getId().serialize() };

    List<Recipient> recipients = new ArrayList<>();

    try (Cursor cursor = databaseHelper.getReadableDatabase().query(TABLE_NAME, ID_PROJECTION, selection, args, null, null, null)) {
      while (cursor != null && cursor.moveToNext()) {
        recipients.add(Recipient.resolved(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID)))));
      }
    }

    return recipients;
  }

  /**
   * @param lastInteractionThreshold Only include contacts that have been interacted with since this time.
   * @param lastProfileFetchThreshold Only include contacts that haven't their profile fetched after this time.
   * @param limit Only return at most this many contact.
   */
  public List<RecipientId> getRecipientsForRoutineProfileFetch(long lastInteractionThreshold, long lastProfileFetchThreshold, int limit) {
    ThreadDatabase threadDatabase                       = DatabaseFactory.getThreadDatabase(context);
    Set<Recipient> recipientsWithinInteractionThreshold = new LinkedHashSet<>();

    try (ThreadDatabase.Reader reader = threadDatabase.readerFor(threadDatabase.getRecentPushConversationList(-1, false))) {
      ThreadRecord record;

      while ((record = reader.getNext()) != null && record.getDate() > lastInteractionThreshold) {
        Recipient recipient = Recipient.resolved(record.getRecipient().getId());

        if (recipient.isGroup()) {
          recipientsWithinInteractionThreshold.addAll(recipient.getParticipants());
        } else {
          recipientsWithinInteractionThreshold.add(recipient);
        }
      }
    }

    return Stream.of(recipientsWithinInteractionThreshold)
                 .filterNot(Recipient::isLocalNumber)
                 .filter(r -> r.getLastProfileFetchTime() < lastProfileFetchThreshold)
                 .limit(limit)
                 .map(Recipient::getId)
                 .toList();
  }

  public void markProfilesFetched(@NonNull Collection<RecipientId> ids, long time) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();
    try {
      ContentValues values = new ContentValues(1);
      values.put(LAST_PROFILE_FETCH, time);

      for (RecipientId id : ids) {
        db.update(TABLE_NAME, values, ID_WHERE, new String[] { id.serialize() });
      }
      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void applyBlockedUpdate(@NonNull List<SignalServiceAddress> blocked, List<byte[]> groupIds) {
    List<String> blockedE164 = Stream.of(blocked)
                                     .filter(b -> b.getNumber().isPresent())
                                     .map(b -> b.getNumber().get())
                                     .toList();
    List<String> blockedUuid = Stream.of(blocked)
                                     .filter(b -> b.getUuid().isPresent())
                                     .map(b -> b.getUuid().get().toString().toLowerCase())
                                     .toList();

    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    db.beginTransaction();
    try {
      ContentValues resetBlocked = new ContentValues();
      resetBlocked.put(BLOCKED, 0);
      db.update(TABLE_NAME, resetBlocked, null, null);

      ContentValues setBlocked = new ContentValues();
      setBlocked.put(BLOCKED, 1);
      setBlocked.put(PROFILE_SHARING, 0);

      for (String e164 : blockedE164) {
        db.update(TABLE_NAME, setBlocked, PHONE + " = ?", new String[] { e164 });
      }

      for (String uuid : blockedUuid) {
        db.update(TABLE_NAME, setBlocked, UUID + " = ?", new String[] { uuid });
      }

      List<GroupId.V1> groupIdStrings = Stream.of(groupIds).map(GroupId::v1orThrow).toList();

      for (GroupId.V1 groupId : groupIdStrings) {
        db.update(TABLE_NAME, setBlocked, GROUP_ID + " = ?", new String[] { groupId.toString() });
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }

    ApplicationDependencies.getRecipientCache().clear();
  }

  public void updateStorageKeys(@NonNull Map<RecipientId, byte[]> keys) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      for (Map.Entry<RecipientId, byte[]> entry : keys.entrySet()) {
        ContentValues values = new ContentValues();
        values.put(STORAGE_SERVICE_ID, Base64.encodeBytes(entry.getValue()));
        db.update(TABLE_NAME, values, ID_WHERE, new String[] { entry.getKey().serialize() });
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  public void clearDirtyState(@NonNull List<RecipientId> recipients) {
    SQLiteDatabase db = databaseHelper.getWritableDatabase();
    db.beginTransaction();

    try {
      ContentValues values = new ContentValues();
      values.put(DIRTY, DirtyState.CLEAN.getId());

      for (RecipientId id : recipients) {
        Optional<RecipientId> remapped = RemappedRecords.getInstance().getRecipient(context, id);
        if (remapped.isPresent()) {
          Log.w(TAG, "While clearing dirty state, noticed we have a remapped contact (" + id + " to " + remapped.get() + "). Safe to delete now.");
          db.delete(TABLE_NAME, ID_WHERE, new String[]{id.serialize()});
        } else {
          db.update(TABLE_NAME, values, ID_WHERE, new String[]{id.serialize()});
        }
      }

      db.setTransactionSuccessful();
    } finally {
      db.endTransaction();
    }
  }

  void markDirty(@NonNull RecipientId recipientId, @NonNull DirtyState dirtyState) {
    Log.d(TAG, "Attempting to mark " + recipientId + " with dirty state " + dirtyState);

    ContentValues contentValues = new ContentValues(1);
    contentValues.put(DIRTY, dirtyState.getId());

    String   query = ID + " = ? AND (" + UUID + " NOT NULL OR " + PHONE + " NOT NULL OR " + GROUP_ID + " NOT NULL) AND ";
    String[] args  = new String[] { recipientId.serialize(), String.valueOf(dirtyState.id) };

    switch (dirtyState) {
      case INSERT:
        query += "(" + DIRTY + " < ? OR " + DIRTY + " = ?)";
        args   = SqlUtil.appendArg(args, String.valueOf(DirtyState.DELETE.getId()));

        contentValues.put(STORAGE_SERVICE_ID, Base64.encodeBytes(StorageSyncHelper.generateKey()));
        break;
      case DELETE:
        query += "(" + DIRTY + " < ? OR " + DIRTY + " = ?)";
        args   = SqlUtil.appendArg(args, String.valueOf(DirtyState.INSERT.getId()));
        break;
      default:
        query += DIRTY + " < ?";
    }

    databaseHelper.getWritableDatabase().update(TABLE_NAME, contentValues, query, args);
  }

  /**
   * Will update the database with the content values you specified. It will make an intelligent
   * query such that this will only return true if a row was *actually* updated.
   */
  private boolean update(@NonNull RecipientId id, @NonNull ContentValues contentValues) {
    String              selection   = ID + " = ?";
    String[]            args        = new String[]{id.serialize()};
    SqlUtil.UpdateQuery updateQuery = SqlUtil.buildTrueUpdateQuery(selection, args, contentValues);

    return update(updateQuery, contentValues);
  }

  /**
   * Will update the database with the {@param contentValues} you specified.
   * <p>
   * This will only return true if a row was *actually* updated with respect to the where clause of the {@param updateQuery}.
   */
  private boolean update(@NonNull SqlUtil.UpdateQuery updateQuery, @NonNull ContentValues contentValues) {
    SQLiteDatabase database = databaseHelper.getWritableDatabase();

    return database.update(TABLE_NAME, contentValues, updateQuery.getWhere(), updateQuery.getWhereArgs()) > 0;
  }

  private @NonNull Optional<RecipientId> getByColumn(@NonNull String column, String value) {
    SQLiteDatabase db    = databaseHelper.getWritableDatabase();
    String         query = column + " = ?";
    String[]       args  = new String[] { value };

    try (Cursor cursor = db.query(TABLE_NAME, ID_PROJECTION, query, args, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) {
        return Optional.of(RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID))));
      } else {
        return Optional.absent();
      }
    }
  }

  private @NonNull GetOrInsertResult getOrInsertByColumn(@NonNull String column, String value) {
    if (TextUtils.isEmpty(value)) {
      throw new AssertionError(column + " cannot be empty.");
    }

    Optional<RecipientId> existing = getByColumn(column, value);

    if (existing.isPresent()) {
      return new GetOrInsertResult(existing.get(), false);
    } else {
      ContentValues values = new ContentValues();
      values.put(column, value);

      long id = databaseHelper.getWritableDatabase().insert(TABLE_NAME, null, values);

      if (id < 0) {
        existing = getByColumn(column, value);

        if (existing.isPresent()) {
          return new GetOrInsertResult(existing.get(), false);
        } else {
          throw new AssertionError("Failed to insert recipient!");
        }
      } else {
        return new GetOrInsertResult(RecipientId.from(id), true);
      }
    }
  }

  /**
   * Merges one UUID recipient with an E164 recipient. It is assumed that the E164 recipient does
   * *not* have a UUID.
   */
  @SuppressWarnings("ConstantConditions")
  private @NonNull RecipientId merge(@NonNull RecipientId byUuid, @NonNull RecipientId byE164) {
    ensureInTransaction();

    SQLiteDatabase db = databaseHelper.getWritableDatabase();

    RecipientSettings uuidSettings = getRecipientSettings(byUuid);
    RecipientSettings e164Settings = getRecipientSettings(byE164);

    // Recipient
    if (e164Settings.getStorageId() == null) {
      Log.w(TAG, "No storageId on the E164 recipient. Can delete right away.");
      db.delete(TABLE_NAME, ID_WHERE, SqlUtil.buildArgs(byE164));
    } else {
      Log.w(TAG, "The E164 recipient has a storageId. Clearing data and marking for deletion.");
      ContentValues values = new ContentValues();
      values.putNull(PHONE);
      values.put(REGISTERED, RegisteredState.NOT_REGISTERED.getId());
      values.put(DIRTY, DirtyState.DELETE.getId());
      db.update(TABLE_NAME, values, ID_WHERE, SqlUtil.buildArgs(byE164));
    }
    RemappedRecords.getInstance().addRecipient(context, byE164, byUuid);

    ContentValues uuidValues = new ContentValues();
    uuidValues.put(PHONE, e164Settings.getE164());
    uuidValues.put(BLOCKED, e164Settings.isBlocked() || uuidSettings.isBlocked());
    uuidValues.put(MESSAGE_RINGTONE, Optional.fromNullable(uuidSettings.getMessageRingtone()).or(Optional.fromNullable(e164Settings.getMessageRingtone())).transform(Uri::toString).orNull());
    uuidValues.put(MESSAGE_VIBRATE, uuidSettings.getMessageVibrateState() != VibrateState.DEFAULT ? uuidSettings.getMessageVibrateState().getId() : e164Settings.getMessageVibrateState().getId());
    uuidValues.put(CALL_RINGTONE, Optional.fromNullable(uuidSettings.getCallRingtone()).or(Optional.fromNullable(e164Settings.getCallRingtone())).transform(Uri::toString).orNull());
    uuidValues.put(CALL_VIBRATE, uuidSettings.getCallVibrateState() != VibrateState.DEFAULT ? uuidSettings.getCallVibrateState().getId() : e164Settings.getCallVibrateState().getId());
    uuidValues.put(NOTIFICATION_CHANNEL, uuidSettings.getNotificationChannel() != null ? uuidSettings.getNotificationChannel() : e164Settings.getNotificationChannel());
    uuidValues.put(MUTE_UNTIL, uuidSettings.getMuteUntil() > 0 ? uuidSettings.getMuteUntil() : e164Settings.getMuteUntil());
    uuidValues.put(COLOR, Optional.fromNullable(uuidSettings.getColor()).or(Optional.fromNullable(e164Settings.getColor())).transform(MaterialColor::serialize).orNull());
    uuidValues.put(SEEN_INVITE_REMINDER, e164Settings.getInsightsBannerTier().getId());
    uuidValues.put(DEFAULT_SUBSCRIPTION_ID, e164Settings.getDefaultSubscriptionId().or(-1));
    uuidValues.put(MESSAGE_EXPIRATION_TIME, uuidSettings.getExpireMessages() > 0 ? uuidSettings.getExpireMessages() : e164Settings.getExpireMessages());
    uuidValues.put(REGISTERED, RegisteredState.REGISTERED.getId());
    uuidValues.put(SYSTEM_DISPLAY_NAME, e164Settings.getSystemDisplayName());
    uuidValues.put(SYSTEM_PHOTO_URI, e164Settings.getSystemContactPhotoUri());
    uuidValues.put(SYSTEM_PHONE_LABEL, e164Settings.getSystemPhoneLabel());
    uuidValues.put(SYSTEM_CONTACT_URI, e164Settings.getSystemContactUri());
    uuidValues.put(PROFILE_SHARING, uuidSettings.isProfileSharing() || e164Settings.isProfileSharing());
    uuidValues.put(GROUPS_V2_CAPABILITY, uuidSettings.getGroupsV2Capability() != Recipient.Capability.UNKNOWN ? uuidSettings.getGroupsV2Capability().serialize() : e164Settings.getGroupsV2Capability().serialize());
    uuidValues.put(MENTION_SETTING, uuidSettings.getMentionSetting() != MentionSetting.ALWAYS_NOTIFY ? uuidSettings.getMentionSetting().getId() : e164Settings.getMentionSetting().getId());
    if (uuidSettings.getProfileKey() != null) {
      updateProfileValuesForMerge(uuidValues, uuidSettings);
    } else if (e164Settings.getProfileKey() != null) {
      updateProfileValuesForMerge(uuidValues, e164Settings);
    }
    db.update(TABLE_NAME, uuidValues, ID_WHERE, SqlUtil.buildArgs(byUuid));

    // Identities
    db.delete(IdentityDatabase.TABLE_NAME, IdentityDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));

    // Group Receipts
    ContentValues groupReceiptValues = new ContentValues();
    groupReceiptValues.put(GroupReceiptDatabase.RECIPIENT_ID, byUuid.serialize());
    db.update(GroupReceiptDatabase.TABLE_NAME, groupReceiptValues, GroupReceiptDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));

    // Groups
    GroupDatabase groupDatabase = DatabaseFactory.getGroupDatabase(context);
    for (GroupDatabase.GroupRecord group : groupDatabase.getGroupsContainingMember(byE164, false, true)) {
      List<RecipientId> newMembers = new ArrayList<>(group.getMembers());
      newMembers.remove(byE164);

      ContentValues groupValues = new ContentValues();
      groupValues.put(GroupDatabase.MEMBERS, RecipientId.toSerializedList(newMembers));
      db.update(GroupDatabase.TABLE_NAME, groupValues, GroupDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(group.getRecipientId()));
    }

    // Threads
    ThreadDatabase.MergeResult threadMerge = DatabaseFactory.getThreadDatabase(context).merge(byUuid, byE164);

    // SMS Messages
    ContentValues smsValues = new ContentValues();
    smsValues.put(SmsDatabase.RECIPIENT_ID, byUuid.serialize());
    if (threadMerge.neededMerge) {
      smsValues.put(SmsDatabase.THREAD_ID, threadMerge.threadId);
    }
    db.update(SmsDatabase.TABLE_NAME, smsValues, SmsDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));

    // MMS Messages
    ContentValues mmsValues = new ContentValues();
    mmsValues.put(MmsDatabase.RECIPIENT_ID, byUuid.serialize());
    if (threadMerge.neededMerge) {
      mmsValues.put(MmsDatabase.THREAD_ID, threadMerge.threadId);
    }
    db.update(MmsDatabase.TABLE_NAME, mmsValues, MmsDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));

    // Sessions
    boolean hasE164Session = DatabaseFactory.getSessionDatabase(context).getAllFor(byE164).size() > 0;
    boolean hasUuidSession = DatabaseFactory.getSessionDatabase(context).getAllFor(byUuid).size() > 0;

    if (hasE164Session && hasUuidSession) {
      Log.w(TAG, "Had a session for both users. Deleting the E164.");
      db.delete(SessionDatabase.TABLE_NAME, SessionDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));
    } else if (hasE164Session && !hasUuidSession) {
      Log.w(TAG, "Had a session for E164, but not UUID. Re-assigning to the UUID.");
      ContentValues values = new ContentValues();
      values.put(SessionDatabase.RECIPIENT_ID, byUuid.serialize());
      db.update(SessionDatabase.TABLE_NAME, values, SessionDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));
    } else if (!hasE164Session && hasUuidSession) {
      Log.w(TAG, "Had a session for UUID, but not E164. No action necessary.");
    } else {
      Log.w(TAG, "Had no sessions. No action necessary.");
    }

    // Mentions
    ContentValues mentionRecipientValues = new ContentValues();
    mentionRecipientValues.put(MentionDatabase.RECIPIENT_ID, byUuid.serialize());
    db.update(MentionDatabase.TABLE_NAME, mentionRecipientValues, MentionDatabase.RECIPIENT_ID + " = ?", SqlUtil.buildArgs(byE164));
    if (threadMerge.neededMerge) {
      ContentValues mentionThreadValues = new ContentValues();
      mentionThreadValues.put(MentionDatabase.THREAD_ID, threadMerge.threadId);
      db.update(MentionDatabase.TABLE_NAME, mentionThreadValues, MentionDatabase.THREAD_ID + " = ?", SqlUtil.buildArgs(threadMerge.previousThreadId));
    }

    DatabaseFactory.getThreadDatabase(context).update(threadMerge.threadId, false, false);

    return byUuid;
  }

  private static void updateProfileValuesForMerge(@NonNull ContentValues values, @NonNull RecipientSettings settings) {
    values.put(PROFILE_KEY, settings.getProfileKey() != null ? Base64.encodeBytes(settings.getProfileKey()) : null);
    values.put(PROFILE_KEY_CREDENTIAL, settings.getProfileKeyCredential() != null ? Base64.encodeBytes(settings.getProfileKeyCredential()) : null);
    values.put(SIGNAL_PROFILE_AVATAR, settings.getProfileAvatar());
    values.put(PROFILE_GIVEN_NAME, settings.getProfileName().getGivenName());
    values.put(PROFILE_FAMILY_NAME, settings.getProfileName().getFamilyName());
    values.put(PROFILE_JOINED_NAME, settings.getProfileName().toString());
  }

  private void ensureInTransaction() {
    if (!databaseHelper.getWritableDatabase().inTransaction()) {
      throw new IllegalStateException("Must be in a transaction!");
    }
  }

  public class BulkOperationsHandle {

    private final SQLiteDatabase database;

    private final Map<RecipientId, PendingContactInfo> pendingContactInfoMap = new HashMap<>();

    BulkOperationsHandle(SQLiteDatabase database) {
      this.database = database;
    }

    public void setSystemContactInfo(@NonNull RecipientId id,
                                     @Nullable String displayName,
                                     @Nullable String photoUri,
                                     @Nullable String systemPhoneLabel,
                                     int systemPhoneType,
                                     @Nullable String systemContactUri)
    {
      ContentValues dirtyQualifyingValues = new ContentValues();
      dirtyQualifyingValues.put(SYSTEM_DISPLAY_NAME, displayName);

      if (update(id, dirtyQualifyingValues)) {
        markDirty(id, DirtyState.UPDATE);
      }

      ContentValues refreshQualifyingValues = new ContentValues();
      refreshQualifyingValues.put(SYSTEM_PHOTO_URI, photoUri);
      refreshQualifyingValues.put(SYSTEM_PHONE_LABEL, systemPhoneLabel);
      refreshQualifyingValues.put(SYSTEM_PHONE_TYPE, systemPhoneType);
      refreshQualifyingValues.put(SYSTEM_CONTACT_URI, systemContactUri);

      boolean updatedValues = update(id, refreshQualifyingValues);
      boolean updatedColor  = displayName != null && setColorIfNotSetInternal(id, ContactColors.generateFor(displayName));

      if (updatedValues || updatedColor) {
        pendingContactInfoMap.put(id, new PendingContactInfo(displayName, photoUri, systemPhoneLabel, systemContactUri));
      }

      ContentValues otherValues = new ContentValues();
      otherValues.put(SYSTEM_INFO_PENDING, 0);
      update(id, otherValues);
    }

    public void finish() {
      markAllRelevantEntriesDirty();
      clearSystemDataForPendingInfo();

      database.setTransactionSuccessful();
      database.endTransaction();

      Stream.of(pendingContactInfoMap.entrySet()).forEach(entry -> Recipient.live(entry.getKey()).refresh());
    }

    private void markAllRelevantEntriesDirty() {
      String   query = SYSTEM_INFO_PENDING + " = ? AND " + STORAGE_SERVICE_ID + " NOT NULL AND " + DIRTY + " < ?";
      String[] args  = new String[] { "1", String.valueOf(DirtyState.UPDATE.getId()) };

      ContentValues values = new ContentValues(1);
      values.put(DIRTY, DirtyState.UPDATE.getId());

      database.update(TABLE_NAME, values, query, args);
    }

    private void clearSystemDataForPendingInfo() {
      String   query = SYSTEM_INFO_PENDING + " = ?";
      String[] args  = new String[] { "1" };

      ContentValues values = new ContentValues(5);

      values.put(SYSTEM_INFO_PENDING, 0);
      values.put(SYSTEM_DISPLAY_NAME, (String) null);
      values.put(SYSTEM_PHOTO_URI, (String) null);
      values.put(SYSTEM_PHONE_LABEL, (String) null);
      values.put(SYSTEM_CONTACT_URI, (String) null);

      database.update(TABLE_NAME, values, query, args);
    }
  }

  private static @NonNull String nullIfEmpty(String column) {
    return "NULLIF(" + column + ", '')";
  }

  private static @NonNull String removeWhitespace(@NonNull String column) {
    return "REPLACE(" + column + ", ' ', '')";
  }

  public interface ColorUpdater {
    MaterialColor update(@NonNull String name, @Nullable String color);
  }

  public static class RecipientSettings {
    private final RecipientId                     id;
    private final UUID                            uuid;
    private final String                          username;
    private final String                          e164;
    private final String                          email;
    private final GroupId                         groupId;
    private final GroupMasterKey                  groupMasterKey;
    private final GroupType                       groupType;
    private final boolean                         blocked;
    private final long                            muteUntil;
    private final VibrateState                    messageVibrateState;
    private final VibrateState                    callVibrateState;
    private final Uri                             messageRingtone;
    private final Uri                             callRingtone;
    private final MaterialColor                   color;
    private final int                             defaultSubscriptionId;
    private final int                             expireMessages;
    private final RegisteredState                 registered;
    private final byte[]                          profileKey;
    private final byte[]                          profileKeyCredential;
    private final String                          systemDisplayName;
    private final String                          systemContactPhoto;
    private final String                          systemPhoneLabel;
    private final String                          systemContactUri;
    private final ProfileName                     signalProfileName;
    private final String                          signalProfileAvatar;
    private final boolean                         hasProfileImage;
    private final boolean                         profileSharing;
    private final long                            lastProfileFetch;
    private final String                          notificationChannel;
    private final UnidentifiedAccessMode          unidentifiedAccessMode;
    private final boolean                         forceSmsSelection;
    private final Recipient.Capability            uuidCapability;
    private final Recipient.Capability            groupsV2Capability;
    private final InsightsBannerTier              insightsBannerTier;
    private final byte[]                          storageId;
    private final byte[]                          identityKey;
    private final IdentityDatabase.VerifiedStatus identityStatus;
    private final MentionSetting                  mentionSetting;

    RecipientSettings(@NonNull RecipientId id,
                      @Nullable UUID uuid,
                      @Nullable String username,
                      @Nullable String e164,
                      @Nullable String email,
                      @Nullable GroupId groupId,
                      @Nullable GroupMasterKey groupMasterKey,
                      @NonNull GroupType groupType,
                      boolean blocked,
                      long muteUntil,
                      @NonNull VibrateState messageVibrateState,
                      @NonNull VibrateState callVibrateState,
                      @Nullable Uri messageRingtone,
                      @Nullable Uri callRingtone,
                      @Nullable MaterialColor color,
                      int defaultSubscriptionId,
                      int expireMessages,
                      @NonNull  RegisteredState registered,
                      @Nullable byte[] profileKey,
                      @Nullable byte[] profileKeyCredential,
                      @Nullable String systemDisplayName,
                      @Nullable String systemContactPhoto,
                      @Nullable String systemPhoneLabel,
                      @Nullable String systemContactUri,
                      @NonNull ProfileName signalProfileName,
                      @Nullable String signalProfileAvatar,
                      boolean hasProfileImage,
                      boolean profileSharing,
                      long lastProfileFetch,
                      @Nullable String notificationChannel,
                      @NonNull UnidentifiedAccessMode unidentifiedAccessMode,
                      boolean forceSmsSelection,
                      Recipient.Capability uuidCapability,
                      Recipient.Capability groupsV2Capability,
                      @NonNull InsightsBannerTier insightsBannerTier,
                      @Nullable byte[] storageId,
                      @Nullable byte[] identityKey,
                      @NonNull IdentityDatabase.VerifiedStatus identityStatus,
                      @NonNull MentionSetting mentionSetting)
    {
      this.id                     = id;
      this.uuid                   = uuid;
      this.username               = username;
      this.e164                   = e164;
      this.email                  = email;
      this.groupId                = groupId;
      this.groupMasterKey         = groupMasterKey;
      this.groupType              = groupType;
      this.blocked                = blocked;
      this.muteUntil              = muteUntil;
      this.messageVibrateState    = messageVibrateState;
      this.callVibrateState       = callVibrateState;
      this.messageRingtone        = messageRingtone;
      this.callRingtone           = callRingtone;
      this.color                  = color;
      this.defaultSubscriptionId  = defaultSubscriptionId;
      this.expireMessages         = expireMessages;
      this.registered             = registered;
      this.profileKey             = profileKey;
      this.profileKeyCredential   = profileKeyCredential;
      this.systemDisplayName      = systemDisplayName;
      this.systemContactPhoto     = systemContactPhoto;
      this.systemPhoneLabel       = systemPhoneLabel;
      this.systemContactUri       = systemContactUri;
      this.signalProfileName      = signalProfileName;
      this.signalProfileAvatar    = signalProfileAvatar;
      this.hasProfileImage        = hasProfileImage;
      this.profileSharing         = profileSharing;
      this.lastProfileFetch       = lastProfileFetch;
      this.notificationChannel    = notificationChannel;
      this.unidentifiedAccessMode = unidentifiedAccessMode;
      this.forceSmsSelection      = forceSmsSelection;
      this.uuidCapability         = uuidCapability;
      this.groupsV2Capability     = groupsV2Capability;
      this.insightsBannerTier     = insightsBannerTier;
      this.storageId              = storageId;
      this.identityKey            = identityKey;
      this.identityStatus         = identityStatus;
      this.mentionSetting         = mentionSetting;
    }

    public RecipientId getId() {
      return id;
    }

    public @Nullable UUID getUuid() {
      return uuid;
    }

    public @Nullable String getUsername() {
      return username;
    }

    public @Nullable String getE164() {
      return e164;
    }

    public @Nullable String getEmail() {
      return email;
    }

    public @Nullable GroupId getGroupId() {
      return groupId;
    }

    /**
     * Only read populated for sync.
     */
    public @Nullable GroupMasterKey getGroupMasterKey() {
      return groupMasterKey;
    }

    public @NonNull GroupType getGroupType() {
      return groupType;
    }

    public @Nullable MaterialColor getColor() {
      return color;
    }

    public boolean isBlocked() {
      return blocked;
    }

    public long getMuteUntil() {
      return muteUntil;
    }

    public @NonNull VibrateState getMessageVibrateState() {
      return messageVibrateState;
    }

    public @NonNull VibrateState getCallVibrateState() {
      return callVibrateState;
    }

    public @Nullable Uri getMessageRingtone() {
      return messageRingtone;
    }

    public @Nullable Uri getCallRingtone() {
      return callRingtone;
    }

    public @NonNull InsightsBannerTier getInsightsBannerTier() {
      return insightsBannerTier;
    }

    public Optional<Integer> getDefaultSubscriptionId() {
      return defaultSubscriptionId != -1 ? Optional.of(defaultSubscriptionId) : Optional.absent();
    }

    public int getExpireMessages() {
      return expireMessages;
    }

    public RegisteredState getRegistered() {
      return registered;
    }

    public @Nullable byte[] getProfileKey() {
      return profileKey;
    }

    public @Nullable byte[] getProfileKeyCredential() {
      return profileKeyCredential;
    }

    public @Nullable String getSystemDisplayName() {
      return systemDisplayName;
    }

    public @Nullable String getSystemContactPhotoUri() {
      return systemContactPhoto;
    }

    public @Nullable String getSystemPhoneLabel() {
      return systemPhoneLabel;
    }

    public @Nullable String getSystemContactUri() {
      return systemContactUri;
    }

    public @NonNull ProfileName getProfileName() {
      return signalProfileName;
    }

    public @Nullable String getProfileAvatar() {
      return signalProfileAvatar;
    }

    public boolean hasProfileImage() {
      return hasProfileImage;
    }

    public boolean isProfileSharing() {
      return profileSharing;
    }

    public long getLastProfileFetch() {
      return lastProfileFetch;
    }

    public @Nullable String getNotificationChannel() {
      return notificationChannel;
    }

    public @NonNull UnidentifiedAccessMode getUnidentifiedAccessMode() {
      return unidentifiedAccessMode;
    }

    public boolean isForceSmsSelection() {
      return forceSmsSelection;
    }

    public Recipient.Capability getUuidCapability() {
      return uuidCapability;
    }

    public Recipient.Capability getGroupsV2Capability() {
      return groupsV2Capability;
    }

    public @Nullable byte[] getStorageId() {
      return storageId;
    }

    public @Nullable byte[] getIdentityKey() {
      return identityKey;
    }

    public @NonNull IdentityDatabase.VerifiedStatus getIdentityStatus() {
      return identityStatus;
    }

    public @NonNull MentionSetting getMentionSetting() {
      return mentionSetting;
    }
  }

  public static class RecipientReader implements Closeable {

    private final Cursor  cursor;

    RecipientReader(Cursor cursor) {
      this.cursor  = cursor;
    }

    public @NonNull Recipient getCurrent() {
      RecipientId id = RecipientId.from(cursor.getLong(cursor.getColumnIndexOrThrow(ID)));
      return Recipient.resolved(id);
    }

    public @Nullable Recipient getNext() {
      if (cursor != null && !cursor.moveToNext()) {
        return null;
      }

      return getCurrent();
    }

    public void close() {
      cursor.close();
    }
  }

  public final static class RecipientIdResult {
    private final RecipientId recipientId;
    private final boolean     requiresDirectoryRefresh;

    public RecipientIdResult(@NonNull RecipientId recipientId, boolean requiresDirectoryRefresh) {
      this.recipientId              = recipientId;
      this.requiresDirectoryRefresh = requiresDirectoryRefresh;
    }

    public @NonNull RecipientId getRecipientId() {
      return recipientId;
    }

    public boolean requiresDirectoryRefresh() {
      return requiresDirectoryRefresh;
    }
  }

  private static class PendingContactInfo {

    private final String displayName;
    private final String photoUri;
    private final String phoneLabel;
    private final String contactUri;

    private PendingContactInfo(String displayName, String photoUri, String phoneLabel, String contactUri) {
      this.displayName = displayName;
      this.photoUri    = photoUri;
      this.phoneLabel  = phoneLabel;
      this.contactUri  = contactUri;
    }
  }

  public static class MissingRecipientException extends IllegalStateException {
    public MissingRecipientException(@Nullable RecipientId id) {
      super("Failed to find recipient with ID: " + id);
    }
  }

  private static class GetOrInsertResult {
    final RecipientId recipientId;
    final boolean     neededInsert;

    private GetOrInsertResult(@NonNull RecipientId recipientId, boolean neededInsert) {
      this.recipientId  = recipientId;
      this.neededInsert = neededInsert;
    }
  }
}
