package org.thoughtcrime.securesms.groups.v2;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.logging.Log;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Collects profile keys from group states.
 * <p>
 * Separates out "authoritative" profile keys that came from a group update created by their owner.
 * <p>
 * Authoritative profile keys can be used to overwrite local profile keys.
 * Non-authoritative profile keys can be used to fill in missing knowledge.
 */
public final class ProfileKeySet {

  private static final String TAG = Log.tag(ProfileKeySet.class);

  private final Map<UUID, ProfileKey> profileKeys              = new LinkedHashMap<>();
  private final Map<UUID, ProfileKey> authoritativeProfileKeys = new LinkedHashMap<>();

  /**
   * Add new profile keys from a group change.
   * <p>
   * If the change came from the member whose profile key is changing then it is regarded as
   * authoritative.
   */
  public void addKeysFromGroupChange(@NonNull DecryptedGroupChange change) {
    UUID editor = UuidUtil.fromByteStringOrNull(change.getEditor());

    for (DecryptedMember member : change.getNewMembersList()) {
      addMemberKey(member, editor);
    }

    for (DecryptedMember member : change.getPromotePendingMembersList()) {
      addMemberKey(member, editor);
    }

    for (DecryptedMember member : change.getModifiedProfileKeysList()) {
      addMemberKey(member, editor);
    }
  }

  /**
   * Add new profile keys from the group state.
   * <p>
   * Profile keys found in group state are never authoritative as the change cannot be easily
   * attributed to a member and it's possible that the group is out of date. So profile keys
   * gathered from a group state can only be used to fill in gaps in knowledge.
   */
  public void addKeysFromGroupState(@NonNull DecryptedGroup group) {
    for (DecryptedMember member : group.getMembersList()) {
      addMemberKey(member, null);
    }
  }

  private void addMemberKey(@NonNull DecryptedMember member, @Nullable UUID changeSource) {
    UUID memberUuid = UuidUtil.fromByteString(member.getUuid());

    if (UuidUtil.UNKNOWN_UUID.equals(memberUuid)) {
      Log.w(TAG, "Seen unknown member UUID");
      return;
    }

    ProfileKey profileKey;
    try {
      profileKey = new ProfileKey(member.getProfileKey().toByteArray());
    } catch (InvalidInputException e) {
      Log.w(TAG, "Bad profile key in group");
      return;
    }

    if (memberUuid.equals(changeSource)) {
      authoritativeProfileKeys.put(memberUuid, profileKey);
      profileKeys.remove(memberUuid);
    } else {
      if (!authoritativeProfileKeys.containsKey(memberUuid)) {
        profileKeys.put(memberUuid, profileKey);
      }
    }
  }

  public Map<UUID, ProfileKey> getProfileKeys() {
    return profileKeys;
  }

  public Map<UUID, ProfileKey> getAuthoritativeProfileKeys() {
    return authoritativeProfileKeys;
  }
}
