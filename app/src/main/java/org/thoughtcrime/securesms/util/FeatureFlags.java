package org.thoughtcrime.securesms.util;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import com.annimon.stream.Stream;
import com.google.android.collect.Sets;

import org.json.JSONException;
import org.json.JSONObject;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobs.RefreshAttributesJob;
import org.thoughtcrime.securesms.jobs.RefreshOwnProfileJob;
import org.thoughtcrime.securesms.jobs.RemoteConfigRefreshJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.logging.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * A location for flags that can be set locally and remotely. These flags can guard features that
 * are not yet ready to be activated.
 *
 * When creating a new flag:
 * - Create a new string constant. This should almost certainly be prefixed with "android."
 * - Add a method to retrieve the value using {@link #getBoolean(String, boolean)}. You can also add
 *   other checks here, like requiring other flags.
 * - If you want to be able to change a flag remotely, place it in {@link #REMOTE_CAPABLE}.
 * - If you would like to force a value for testing, place an entry in {@link #FORCED_VALUES}.
 *   Do not commit changes to this map!
 *
 * Other interesting things you can do:
 * - Make a flag {@link #HOT_SWAPPABLE}
 * - Make a flag {@link #STICKY} -- booleans only!
 * - Register a listener for flag changes in  {@link #FLAG_CHANGE_LISTENERS}
 */
public final class FeatureFlags {

  private static final String TAG = Log.tag(FeatureFlags.class);

  private static final long FETCH_INTERVAL = TimeUnit.HOURS.toMillis(2);

  private static final String USERNAMES                  = "android.usernames";
  private static final String ATTACHMENTS_V3             = "android.attachmentsV3.2";
  private static final String REMOTE_DELETE              = "android.remoteDelete";
  private static final String GROUPS_V2_OLD_1            = "android.groupsv2";
  private static final String GROUPS_V2_OLD_2            = "android.groupsv2.2";
  private static final String GROUPS_V2                  = "android.groupsv2.3";
  private static final String GROUPS_V2_CREATE           = "android.groupsv2.create.3";
  private static final String GROUPS_V2_CAPACITY         = "global.groupsv2.maxGroupSize";
  private static final String CDS                        = "android.cds.3";
  private static final String INTERNAL_USER              = "android.internalUser";
  private static final String MENTIONS                   = "android.mentions";
  private static final String VERIFY_V2                  = "android.verifyV2";

  /**
   * We will only store remote values for flags in this set. If you want a flag to be controllable
   * remotely, place it in here.
   */

  private static final Set<String> REMOTE_CAPABLE = Sets.newHashSet(
      ATTACHMENTS_V3,
      REMOTE_DELETE,
      GROUPS_V2,
      GROUPS_V2_CREATE,
      GROUPS_V2_CAPACITY,
      CDS,
      INTERNAL_USER,
      MENTIONS,
      VERIFY_V2
  );

  /**
   * Values in this map will take precedence over any value. This should only be used for local
   * development. Given that you specify a default when retrieving a value, and that we only store
   * remote values for things in {@link #REMOTE_CAPABLE}, there should be no need to ever *commit*
   * an addition to this map.
   */
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private static final Map<String, Object> FORCED_VALUES = new HashMap<String, Object>() {{
  }};

  /**
   * By default, flags are only updated once at app start. This is to ensure that values don't
   * change within an app session, simplifying logic. However, given that this can delay how often
   * a flag is updated, you can put a flag in here to mark it as 'hot swappable'. Flags in this set
   * will be updated arbitrarily at runtime. This will make values more responsive, but also places
   * more burden on the reader to ensure that the app experience remains consistent.
   */
  private static final Set<String> HOT_SWAPPABLE = Sets.newHashSet(
      ATTACHMENTS_V3,
      GROUPS_V2_CREATE,
      VERIFY_V2
  );

  /**
   * Flags in this set will stay true forever once they receive a true value from a remote config.
   */
  private static final Set<String> STICKY = Sets.newHashSet(
      GROUPS_V2,
      GROUPS_V2_OLD_1,
      GROUPS_V2_OLD_2,
      VERIFY_V2
    );

  /**
   * Listeners that are called when the value in {@link #REMOTE_VALUES} changes. That means that
   * hot-swappable flags will have this invoked as soon as we know about that change, but otherwise
   * these will only run during initialization.
   *
   * These can be called on any thread, including the main thread, so be careful!
   *
   * Also note that this doesn't play well with {@link #FORCED_VALUES} -- changes there will not
   * trigger changes in this map, so you'll have to do some manually hacking to get yourself in the
   * desired test state.
   */
  private static final Map<String, OnFlagChange> FLAG_CHANGE_LISTENERS = new HashMap<String, OnFlagChange>() {{
    put(GROUPS_V2, (change) -> {
      if (change == Change.ENABLED) {
        ApplicationDependencies.getJobManager().startChain(new RefreshAttributesJob())
                               .then(new RefreshOwnProfileJob())
                               .enqueue();
      }
    });
  }};

  private static final Map<String, Object> REMOTE_VALUES = new TreeMap<>();

  private FeatureFlags() {}

  public static synchronized void init() {
    Map<String, Object> current = parseStoredConfig(SignalStore.remoteConfigValues().getCurrentConfig());
    Map<String, Object> pending = parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig());
    Map<String, Change> changes = computeChanges(current, pending);

    SignalStore.remoteConfigValues().setCurrentConfig(mapToJson(pending));
    REMOTE_VALUES.putAll(pending);
    triggerFlagChangeListeners(changes);

    Log.i(TAG, "init() " + REMOTE_VALUES.toString());
  }

  public static synchronized void refreshIfNecessary() {
    long timeSinceLastFetch = System.currentTimeMillis() - SignalStore.remoteConfigValues().getLastFetchTime();

    if (timeSinceLastFetch > FETCH_INTERVAL) {
      Log.i(TAG, "Scheduling remote config refresh.");
      ApplicationDependencies.getJobManager().add(new RemoteConfigRefreshJob());
    } else {
      Log.i(TAG, "Skipping remote config refresh. Refreshed " + timeSinceLastFetch + " ms ago.");
    }
  }

  public static synchronized void update(@NonNull Map<String, Object> config) {
    Map<String, Object> memory  = REMOTE_VALUES;
    Map<String, Object> disk    = parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig());
    UpdateResult        result  = updateInternal(config, memory, disk, REMOTE_CAPABLE, HOT_SWAPPABLE, STICKY);

    SignalStore.remoteConfigValues().setPendingConfig(mapToJson(result.getDisk()));
    REMOTE_VALUES.clear();
    REMOTE_VALUES.putAll(result.getMemory());
    triggerFlagChangeListeners(result.getMemoryChanges());

    SignalStore.remoteConfigValues().setLastFetchTime(System.currentTimeMillis());

    Log.i(TAG, "[Memory] Before: " + memory.toString());
    Log.i(TAG, "[Memory] After : " + result.getMemory().toString());
    Log.i(TAG, "[Disk]   Before: " + disk.toString());
    Log.i(TAG, "[Disk]   After : " + result.getDisk().toString());
  }

  /** Creating usernames, sending messages by username. */
  public static synchronized boolean usernames() {
    return getBoolean(USERNAMES, false);
  }

  /** Whether or not we use the attachments v3 form. */
  public static boolean attachmentsV3() {
    return getBoolean(ATTACHMENTS_V3, false);
  }

  /** Send support for remotely deleting a message. */
  public static boolean remoteDelete() {
    return getBoolean(REMOTE_DELETE, false);
  }

  /** Groups v2 send and receive. */
  public static boolean groupsV2() {
    return groupsV2OlderStickyFlags() || groupsV2LatestFlag();
  }

  /** Attempt groups v2 creation. */
  public static boolean groupsV2create() {
    return groupsV2LatestFlag() &&
           getBoolean(GROUPS_V2_CREATE, false) &&
           !SignalStore.internalValues().gv2DoNotCreateGv2Groups();
  }

  private static boolean groupsV2LatestFlag() {
    return getBoolean(GROUPS_V2, false);
  }

  /** Clients that previously saw these flags as true must continue to respect that */
  private static boolean groupsV2OlderStickyFlags() {
    return getBoolean(GROUPS_V2_OLD_1, false) ||
           getBoolean(GROUPS_V2_OLD_2, false);
  }

  /**
   * Maximum number of members allowed in a group.
   */
  public static int gv2GroupCapacity() {
    return getInteger(GROUPS_V2_CAPACITY, 151);
  }

  /** Internal testing extensions. */
  public static boolean internalUser() {
    return getBoolean(INTERNAL_USER, false);
  }

  /** Whether or not to use the new contact discovery service endpoint, which supports UUIDs. */
  public static boolean cds() {
    return getBoolean(CDS, false);
  }

  /** Whether or not we allow mentions send support in groups. */
  public static boolean mentions() {
    return groupsV2() && getBoolean(MENTIONS, false);
  }

  /** Whether or not to use the UUID in verification codes. */
  public static boolean verifyV2() {
    return getBoolean(VERIFY_V2, false);
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getMemoryValues() {
    return new TreeMap<>(REMOTE_VALUES);
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getDiskValues() {
    return new TreeMap<>(parseStoredConfig(SignalStore.remoteConfigValues().getCurrentConfig()));
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getPendingDiskValues() {
    return new TreeMap<>(parseStoredConfig(SignalStore.remoteConfigValues().getPendingConfig()));
  }

  /** Only for rendering debug info. */
  public static synchronized @NonNull Map<String, Object> getForcedValues() {
    return new TreeMap<>(FORCED_VALUES);
  }

  @VisibleForTesting
  static @NonNull UpdateResult updateInternal(@NonNull Map<String, Object> remote,
                                              @NonNull Map<String, Object> localMemory,
                                              @NonNull Map<String, Object> localDisk,
                                              @NonNull Set<String>         remoteCapable,
                                              @NonNull Set<String>         hotSwap,
                                              @NonNull Set<String>         sticky)
  {
    Map<String, Object> newMemory = new TreeMap<>(localMemory);
    Map<String, Object> newDisk   = new TreeMap<>(localDisk);

    Set<String> allKeys = new HashSet<>();
    allKeys.addAll(remote.keySet());
    allKeys.addAll(localDisk.keySet());
    allKeys.addAll(localMemory.keySet());

    Stream.of(allKeys)
          .filter(remoteCapable::contains)
          .forEach(key -> {
            Object remoteValue = remote.get(key);
            Object diskValue   = localDisk.get(key);
            Object newValue    = remoteValue;

            if (newValue != null && diskValue != null && newValue.getClass() != diskValue.getClass()) {
              Log.w(TAG, "Type mismatch! key: " + key);

              newDisk.remove(key);

              if (hotSwap.contains(key)) {
                newMemory.remove(key);
              }

              return;
            }

            if (sticky.contains(key) && (newValue instanceof Boolean || diskValue instanceof Boolean)) {
              newValue = diskValue == Boolean.TRUE ? Boolean.TRUE : newValue;
            } else if (sticky.contains(key)) {
              Log.w(TAG, "Tried to make a non-boolean sticky! Ignoring. (key: " + key + ")");
            }

            if (newValue != null) {
              newDisk.put(key, newValue);
            } else {
              newDisk.remove(key);
            }

            if (hotSwap.contains(key)) {
              if (newValue != null) {
                newMemory.put(key, newValue);
              } else {
                newMemory.remove(key);
              }
            }
          });

    Stream.of(allKeys)
          .filterNot(remoteCapable::contains)
          .filterNot(key -> sticky.contains(key) && localDisk.get(key) == Boolean.TRUE)
          .forEach(key -> {
            newDisk.remove(key);

            if (hotSwap.contains(key)) {
              newMemory.remove(key);
            }
          });

    return new UpdateResult(newMemory, newDisk, computeChanges(localMemory, newMemory));
  }

  @VisibleForTesting
  static @NonNull Map<String, Change> computeChanges(@NonNull Map<String, Object> oldMap, @NonNull Map<String, Object> newMap) {
    Map<String, Change> changes = new HashMap<>();
    Set<String>         allKeys = new HashSet<>();

    allKeys.addAll(oldMap.keySet());
    allKeys.addAll(newMap.keySet());

    for (String key : allKeys) {
      Object oldValue = oldMap.get(key);
      Object newValue = newMap.get(key);

      if (oldValue == null && newValue == null) {
        throw new AssertionError("Should not be possible.");
      } else if (oldValue != null && newValue == null) {
        changes.put(key, Change.REMOVED);
      } else if (newValue != oldValue && newValue instanceof Boolean) {
        changes.put(key, (boolean) newValue ? Change.ENABLED : Change.DISABLED);
      } else if (newValue != oldValue) {
        changes.put(key, Change.CHANGED);
      }
    }

    return changes;
  }

  private static boolean getBoolean(@NonNull String key, boolean defaultValue) {
    Boolean forced = (Boolean) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    Object remote = REMOTE_VALUES.get(key);
    if (remote instanceof Boolean) {
      return (boolean) remote;
    } else if (remote != null) {
      Log.w(TAG, "Expected a boolean for key '" + key + "', but got something else! Falling back to the default.");
    }

    return defaultValue;
  }

  private static int getInteger(@NonNull String key, int defaultValue) {
    Integer forced = (Integer) FORCED_VALUES.get(key);
    if (forced != null) {
      return forced;
    }

    String remote = (String) REMOTE_VALUES.get(key);
    if (remote != null) {
      try {
        return Integer.parseInt(remote);
      } catch (NumberFormatException e) {
        Log.w(TAG, "Expected an int for key '" + key + "', but got something else! Falling back to the default.");
      }
    }

    return defaultValue;
  }

  private static Map<String, Object> parseStoredConfig(String stored) {
    Map<String, Object> parsed = new HashMap<>();

    if (TextUtils.isEmpty(stored)) {
      Log.i(TAG, "No remote config stored. Skipping.");
      return parsed;
    }

    try {
      JSONObject       root = new JSONObject(stored);
      Iterator<String> iter = root.keys();

      while (iter.hasNext()) {
        String key = iter.next();
        parsed.put(key, root.get(key));
      }
    } catch (JSONException e) {
      throw new AssertionError("Failed to parse! Cleared storage.");
    }

    return parsed;
  }

  private static @NonNull String mapToJson(@NonNull Map<String, Object> map) {
    try {
      JSONObject json = new JSONObject();

      for (Map.Entry<String, Object> entry : map.entrySet()) {
        json.put(entry.getKey(), entry.getValue());
      }

      return json.toString();
    } catch (JSONException e) {
      throw new AssertionError(e);
    }
  }

  private static void triggerFlagChangeListeners(Map<String, Change> changes) {
    for (Map.Entry<String, Change> change : changes.entrySet()) {
      OnFlagChange listener = FLAG_CHANGE_LISTENERS.get(change.getKey());

      if (listener != null) {
        Log.i(TAG, "Triggering change listener for: " + change.getKey());
        listener.onFlagChange(change.getValue());
      }
    }
  }

  private static final class MissingFlagRequirementError extends Error {
  }

  @VisibleForTesting
  static final class UpdateResult {
    private final Map<String, Object> memory;
    private final Map<String, Object> disk;
    private final Map<String, Change>  memoryChanges;

    UpdateResult(@NonNull Map<String, Object> memory, @NonNull Map<String, Object> disk, @NonNull Map<String, Change> memoryChanges) {
      this.memory        = memory;
      this.disk          = disk;
      this.memoryChanges = memoryChanges;
    }

    public @NonNull Map<String, Object> getMemory() {
      return memory;
    }

    public @NonNull Map<String, Object> getDisk() {
      return disk;
    }

    public @NonNull Map<String, Change> getMemoryChanges() {
      return memoryChanges;
    }
  }

  @VisibleForTesting
  interface OnFlagChange {
    void onFlagChange(@NonNull Change change);
  }

  enum Change {
    ENABLED, DISABLED, CHANGED, REMOVED
  }
}
