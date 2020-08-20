package org.thoughtcrime.securesms.groups.ui.managegroup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import androidx.core.util.Consumer;

import com.annimon.stream.Stream;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.thoughtcrime.securesms.ContactSelectionListFragment;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.GroupDatabase;
import org.thoughtcrime.securesms.database.RecipientDatabase;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.groups.GroupAccessControl;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.groups.GroupProtoUtil;
import org.thoughtcrime.securesms.groups.MembershipNotSuitableForV2Exception;
import org.thoughtcrime.securesms.groups.ui.AddMembersResultCallback;
import org.thoughtcrime.securesms.groups.ui.GroupChangeErrorCallback;
import org.thoughtcrime.securesms.groups.ui.GroupChangeFailureReason;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.recipients.RecipientUtil;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.thoughtcrime.securesms.util.concurrent.SimpleTask;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

final class ManageGroupRepository {

  private static final String TAG = Log.tag(ManageGroupRepository.class);

  private final Context context;
  private final GroupId groupId;

  ManageGroupRepository(@NonNull Context context, @NonNull GroupId groupId) {
    this.context  = context;
    this.groupId  = groupId;
  }

  public GroupId getGroupId() {
    return groupId;
  }

  void getGroupState(@NonNull Consumer<GroupStateResult> onGroupStateLoaded) {
    SignalExecutors.BOUNDED.execute(() -> onGroupStateLoaded.accept(getGroupState()));
  }

  void getGroupCapacity(@NonNull Consumer<GroupCapacityResult> onGroupCapacityLoaded) {
    SimpleTask.run(SignalExecutors.BOUNDED, () -> {
      GroupDatabase.GroupRecord groupRecord = DatabaseFactory.getGroupDatabase(context).getGroup(groupId).get();
      if (groupRecord.isV2Group()) {
        DecryptedGroup    decryptedGroup = groupRecord.requireV2GroupProperties().getDecryptedGroup();
        List<RecipientId> pendingMembers = Stream.of(decryptedGroup.getPendingMembersList())
                                                 .map(member -> GroupProtoUtil.uuidByteStringToRecipientId(member.getUuid()))
                                                 .toList();
        List<RecipientId> members        = new LinkedList<>(groupRecord.getMembers());

        members.addAll(pendingMembers);

        return new GroupCapacityResult(members, FeatureFlags.gv2GroupCapacity());
      } else {
        return new GroupCapacityResult(groupRecord.getMembers(), ContactSelectionListFragment.NO_LIMIT);
      }
    }, onGroupCapacityLoaded::accept);
  }

  @WorkerThread
  private GroupStateResult getGroupState() {
    ThreadDatabase threadDatabase = DatabaseFactory.getThreadDatabase(context);
    Recipient      groupRecipient = Recipient.externalGroup(context, groupId);
    long           threadId       = threadDatabase.getThreadIdFor(groupRecipient);

    return new GroupStateResult(threadId, groupRecipient);
  }

  void setExpiration(int newExpirationTime, @NonNull GroupChangeErrorCallback error) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.updateGroupTimer(context, groupId.requirePush(), newExpirationTime);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
      }
    });
  }

  void applyMembershipRightsChange(@NonNull GroupAccessControl newRights, @NonNull GroupChangeErrorCallback error) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.applyMembershipAdditionRightsChange(context, groupId.requireV2(), newRights);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
      }
    });
  }

  void applyAttributesRightsChange(@NonNull GroupAccessControl newRights, @NonNull GroupChangeErrorCallback error) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.applyAttributesRightsChange(context, groupId.requireV2(), newRights);
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
      }
    });
  }

  public void getRecipient(@NonNull Consumer<Recipient> recipientCallback) {
    SimpleTask.run(SignalExecutors.BOUNDED,
                   () -> Recipient.externalGroup(context, groupId),
                   recipientCallback::accept);
  }

  void setMuteUntil(long until) {
    SignalExecutors.BOUNDED.execute(() -> {
      RecipientId recipientId = Recipient.externalGroup(context, groupId).getId();
      DatabaseFactory.getRecipientDatabase(context).setMuted(recipientId, until);
    });
  }

  void addMembers(@NonNull List<RecipientId> selected, @NonNull AddMembersResultCallback addMembersResultCallback, @NonNull GroupChangeErrorCallback error) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        GroupManager.GroupActionResult groupActionResult = GroupManager.addMembers(context, groupId.requirePush(), selected);
        addMembersResultCallback.onMembersAdded(groupActionResult.getAddedMemberCount(), groupActionResult.getInvitedMembers());
      } catch (GroupChangeException | MembershipNotSuitableForV2Exception | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
      }
    });
  }

  void blockAndLeaveGroup(@NonNull GroupChangeErrorCallback error, @NonNull Runnable onSuccess) {
    SignalExecutors.UNBOUNDED.execute(() -> {
      try {
        RecipientUtil.block(context, Recipient.externalGroup(context, groupId));
        onSuccess.run();
      } catch (GroupChangeException | IOException e) {
        Log.w(TAG, e);
        error.onError(GroupChangeFailureReason.fromException(e));
      }
    });
  }

  void setMentionSetting(RecipientDatabase.MentionSetting mentionSetting) {
    SignalExecutors.BOUNDED.execute(() -> {
      RecipientId recipientId = Recipient.externalGroup(context, groupId).getId();
      DatabaseFactory.getRecipientDatabase(context).setMentionSetting(recipientId, mentionSetting);
    });
  }

  static final class GroupStateResult {

    private final long      threadId;
    private final Recipient recipient;

    private GroupStateResult(long threadId,
                             Recipient recipient)
    {
      this.threadId  = threadId;
      this.recipient = recipient;
    }

    long getThreadId() {
      return threadId;
    }

    Recipient getRecipient() {
      return recipient;
    }
  }

  static final class GroupCapacityResult {
    private final List<RecipientId> members;
    private final int               totalCapacity;

    GroupCapacityResult(@NonNull List<RecipientId> members, int totalCapacity) {
      this.members        = members;
      this.totalCapacity  = totalCapacity;
    }

    public @NonNull List<RecipientId> getMembers() {
      return members;
    }

    public int getTotalCapacity() {
      return totalCapacity;
    }
  }

}
