package org.thoughtcrime.securesms.groups;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.signal.zkgroup.groups.GroupMasterKey;
import org.signal.zkgroup.groups.UuidCiphertext;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.profiles.AvatarHelper;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.Util;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GroupManager {

  private static final String TAG = Log.tag(GroupManager.class);

  @WorkerThread
  public static @NonNull GroupActionResult createGroup(@NonNull  Context        context,
                                                       @NonNull  Set<Recipient> members,
                                                       @Nullable byte[]         avatar,
                                                       @Nullable String         name,
                                                                 boolean        mms)
      throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    boolean          shouldAttemptToCreateV2 = !mms && FeatureFlags.groupsV2create();
    Set<RecipientId> memberIds               = getMemberIds(members);

    if (shouldAttemptToCreateV2) {
      try {
        try (GroupManagerV2.GroupCreator groupCreator = new GroupManagerV2(context).create()) {
          return groupCreator.createGroup(memberIds, name, avatar);
        }
      } catch (MembershipNotSuitableForV2Exception e) {
        Log.w(TAG, "Attempted to make a GV2, but membership was not suitable, falling back to GV1", e);

        return GroupManagerV1.createGroup(context, memberIds, avatar, name, false);
      }
    } else {
      return GroupManagerV1.createGroup(context, memberIds, avatar, name, mms);
    }
  }

  @WorkerThread
  public static GroupActionResult updateGroupDetails(@NonNull  Context context,
                                                     @NonNull  GroupId groupId,
                                                     @Nullable byte[]  avatar,
                                                               boolean avatarChanged,
                                                     @NonNull  String  name,
                                                               boolean nameChanged)
    throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        return edit.updateGroupTitleAndAvatar(nameChanged ? name : null, avatar, avatarChanged);
      }
    } else {
      List<Recipient> members = DatabaseFactory.getGroupDatabase(context)
                                               .getGroupMembers(groupId, GroupDatabase.MemberSet.FULL_MEMBERS_EXCLUDING_SELF);

      Set<RecipientId> recipientIds = getMemberIds(new HashSet<>(members));

      return GroupManagerV1.updateGroup(context, groupId.requireV1(), recipientIds, avatar, name, 0);
    }
  }

  private static Set<RecipientId> getMemberIds(Collection<Recipient> recipients) {
    Set<RecipientId> results = new HashSet<>(recipients.size());

    for (Recipient recipient : recipients) {
      results.add(recipient.getId());
    }

    return results;
  }

  @WorkerThread
  public static void leaveGroup(@NonNull Context context, @NonNull GroupId.Push groupId)
      throws GroupChangeBusyException, GroupChangeFailedException, IOException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
        edit.leaveGroup();
        Log.i(TAG, "Left group " + groupId);
      } catch (GroupInsufficientRightsException e) {
        Log.w(TAG, "Unexpected prevention from leaving " + groupId + " due to rights", e);
        throw new GroupChangeFailedException(e);
      } catch (GroupNotAMemberException e) {
        Log.w(TAG, "Already left group " + groupId, e);
      }
    } else {
      if (!GroupManagerV1.leaveGroup(context, groupId.requireV1())) {
        Log.w(TAG, "GV1 group leave failed" + groupId);
        throw new GroupChangeFailedException();
      }
    }
  }

  @WorkerThread
  public static void leaveGroupFromBlockOrMessageRequest(@NonNull Context context, @NonNull GroupId.Push groupId)
      throws IOException, GroupChangeBusyException, GroupChangeFailedException
  {
    if (groupId.isV2()) {
      leaveGroup(context, groupId.requireV2());
    } else {
      if (!GroupManagerV1.silentLeaveGroup(context, groupId.requireV1())) {
        throw new GroupChangeFailedException();
      }
    }
  }

  @WorkerThread
  public static void addMemberAdminsAndLeaveGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Collection<RecipientId> newAdmins)
      throws GroupChangeBusyException, GroupChangeFailedException, IOException, GroupInsufficientRightsException, GroupNotAMemberException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
      edit.addMemberAdminsAndLeaveGroup(newAdmins);
      Log.i(TAG, "Left group " + groupId);
    }
  }

  @WorkerThread
  public static void ejectFromGroup(@NonNull Context context, @NonNull GroupId.V2 groupId, @NonNull Recipient recipient)
      throws GroupChangeBusyException, GroupChangeFailedException, GroupInsufficientRightsException, GroupNotAMemberException, IOException
  {
    try (GroupManagerV2.GroupEditor edit = new GroupManagerV2(context).edit(groupId.requireV2())) {
      edit.ejectMember(recipient.getId());
      Log.i(TAG, "Member removed from group " + groupId);
    }
  }

  @WorkerThread
  public static void updateGroupFromServer(@NonNull Context context,
                                           @NonNull GroupMasterKey groupMasterKey,
                                           int revision,
                                           long timestamp,
                                           @Nullable byte[] signedGroupChange)
      throws GroupChangeBusyException, IOException, GroupNotAMemberException
  {
    try (GroupManagerV2.GroupUpdater updater = new GroupManagerV2(context).updater(groupMasterKey)) {
      updater.updateLocalToServerRevision(revision, timestamp, signedGroupChange);
    }
  }

  @WorkerThread
  public static void setMemberAdmin(@NonNull Context context,
                                    @NonNull GroupId.V2 groupId,
                                    @NonNull RecipientId recipientId,
                                    boolean admin)
      throws GroupChangeBusyException, GroupChangeFailedException, GroupInsufficientRightsException, GroupNotAMemberException, IOException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.setMemberAdmin(recipientId, admin);
    }
  }

  @WorkerThread
  public static void updateSelfProfileKeyInGroup(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws IOException, GroupChangeBusyException, GroupInsufficientRightsException, GroupNotAMemberException, GroupChangeFailedException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateSelfProfileKeyInGroup();
    }
  }

  @WorkerThread
  public static void acceptInvite(@NonNull Context context, @NonNull GroupId.V2 groupId)
      throws GroupChangeBusyException, GroupChangeFailedException, GroupNotAMemberException, GroupInsufficientRightsException, IOException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.acceptInvite();
      DatabaseFactory.getGroupDatabase(context)
                     .setActive(groupId, true);
    }
  }

  @WorkerThread
  public static void updateGroupTimer(@NonNull Context context, @NonNull GroupId.Push groupId, int expirationTime)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        editor.updateGroupTimer(expirationTime);
      }
    } else {
      GroupManagerV1.updateGroupTimer(context, groupId.requireV1(), expirationTime);
    }
  }

  @WorkerThread
  public static void revokeInvites(@NonNull Context context,
                                   @NonNull GroupId.V2 groupId,
                                   @NonNull Collection<UuidCiphertext> uuidCipherTexts)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.revokeInvites(uuidCipherTexts);
    }
  }

  @WorkerThread
  public static void applyMembershipAdditionRightsChange(@NonNull Context context,
                                                         @NonNull GroupId.V2 groupId,
                                                         @NonNull GroupAccessControl newRights)
       throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateMembershipRights(newRights);
    }
  }

  @WorkerThread
  public static void applyAttributesRightsChange(@NonNull Context context,
                                                 @NonNull GroupId.V2 groupId,
                                                 @NonNull GroupAccessControl newRights)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException
  {
    try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
      editor.updateAttributesRights(newRights);
    }
  }

  @WorkerThread
  public static @NonNull GroupActionResult addMembers(@NonNull Context context,
                                                      @NonNull GroupId.Push groupId,
                                                      @NonNull Collection<RecipientId> newMembers)
      throws GroupChangeFailedException, GroupInsufficientRightsException, IOException, GroupNotAMemberException, GroupChangeBusyException, MembershipNotSuitableForV2Exception
  {
    if (groupId.isV2()) {
      try (GroupManagerV2.GroupEditor editor = new GroupManagerV2(context).edit(groupId.requireV2())) {
        return editor.addMembers(newMembers);
      }
    } else {
      GroupDatabase.GroupRecord groupRecord  = DatabaseFactory.getGroupDatabase(context).requireGroup(groupId);
      List<RecipientId>         members      = groupRecord.getMembers();
      byte[]                    avatar       = groupRecord.hasAvatar() ? Util.readFully(AvatarHelper.getAvatar(context, groupRecord.getRecipientId())) : null;
      Set<RecipientId>          recipientIds = new HashSet<>(members);
      int                       originalSize = recipientIds.size();

      recipientIds.addAll(newMembers);
      return GroupManagerV1.updateGroup(context, groupId, recipientIds, avatar, groupRecord.getTitle(), recipientIds.size() - originalSize);
    }
  }

  public static class GroupActionResult {
    private final Recipient         groupRecipient;
    private final long              threadId;
    private final int               addedMemberCount;
    private final List<RecipientId> invitedMembers;

    public GroupActionResult(@NonNull Recipient groupRecipient,
                             long threadId,
                             int addedMemberCount,
                             @NonNull List<RecipientId> invitedMembers)
    {
      this.groupRecipient   = groupRecipient;
      this.threadId         = threadId;
      this.addedMemberCount = addedMemberCount;
      this.invitedMembers   = invitedMembers;
    }

    public @NonNull Recipient getGroupRecipient() {
      return groupRecipient;
    }

    public long getThreadId() {
      return threadId;
    }

    public int getAddedMemberCount() {
      return addedMemberCount;
    }

    public @NonNull List<RecipientId> getInvitedMembers() {
      return invitedMembers;
    }
  }
}
