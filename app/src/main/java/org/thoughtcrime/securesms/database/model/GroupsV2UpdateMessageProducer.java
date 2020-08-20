package org.thoughtcrime.securesms.database.model;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import com.google.protobuf.ByteString;

import org.signal.storageservice.protos.groups.AccessControl;
import org.signal.storageservice.protos.groups.Member;
import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedModifyMemberRole;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMemberRemoval;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.groups.GV2AccessLevelUtil;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.StringUtil;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.groupsv2.DecryptedGroupUtil;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

final class GroupsV2UpdateMessageProducer {

  @NonNull private final Context                context;
  @NonNull private final DescribeMemberStrategy descriptionStrategy;
  @NonNull private final UUID                   selfUuid;
  @NonNull private final ByteString             selfUuidBytes;

  /**
   * @param descriptionStrategy Strategy for member description.
   */
  GroupsV2UpdateMessageProducer(@NonNull Context context,
                                @NonNull DescribeMemberStrategy descriptionStrategy,
                                @NonNull UUID selfUuid) {
    this.context             = context;
    this.descriptionStrategy = descriptionStrategy;
    this.selfUuid            = selfUuid;
    this.selfUuidBytes       = UuidUtil.toByteString(selfUuid);
  }

  /**
   * Describes a group that is new to you, use this when there is no available change record.
   * <p>
   * Invitation and groups you create are the most common cases where no change is available.
   */
  UpdateDescription describeNewGroup(@NonNull DecryptedGroup group) {
    Optional<DecryptedPendingMember> selfPending = DecryptedGroupUtil.findPendingByUuid(group.getPendingMembersList(), selfUuid);
    if (selfPending.isPresent()) {
      return updateDescription(selfPending.get().getAddedByUuid(), inviteBy -> context.getString(R.string.MessageRecord_s_invited_you_to_the_group, inviteBy));
    }

    if (group.getRevision() == 0) {
      Optional<DecryptedMember> foundingMember = DecryptedGroupUtil.firstMember(group.getMembersList());
      if (foundingMember.isPresent()) {
        ByteString foundingMemberUuid = foundingMember.get().getUuid();
        if (selfUuidBytes.equals(foundingMemberUuid)) {
          return updateDescription(context.getString(R.string.MessageRecord_you_created_the_group));
        } else {
          return updateDescription(foundingMemberUuid, creator -> context.getString(R.string.MessageRecord_s_added_you, creator));
        }
      }
    }

    if (DecryptedGroupUtil.findMemberByUuid(group.getMembersList(), selfUuid).isPresent()) {
      return updateDescription(context.getString(R.string.MessageRecord_you_joined_the_group));
    } else {
      return updateDescription(context.getString(R.string.MessageRecord_group_updated));
    }
  }

  List<UpdateDescription> describeChanges(@NonNull DecryptedGroupChange change) {
    List<UpdateDescription> updates = new LinkedList<>();

    if (change.getEditor().isEmpty() || UuidUtil.UNKNOWN_UUID.equals(UuidUtil.fromByteString(change.getEditor()))) {
      describeUnknownEditorMemberAdditions(change, updates);

      describeUnknownEditorModifyMemberRoles(change, updates);
      describeUnknownEditorInvitations(change, updates);
      describeUnknownEditorRevokedInvitations(change, updates);
      describeUnknownEditorPromotePending(change, updates);
      describeUnknownEditorNewTitle(change, updates);
      describeUnknownEditorNewAvatar(change, updates);
      describeUnknownEditorNewTimer(change, updates);
      describeUnknownEditorNewAttributeAccess(change, updates);
      describeUnknownEditorNewMembershipAccess(change, updates);

      describeUnknownEditorMemberRemovals(change, updates);

      if (updates.isEmpty()) {
        describeUnknownEditorUnknownChange(updates);
      }

    } else {
      describeMemberAdditions(change, updates);

      describeModifyMemberRoles(change, updates);
      describeInvitations(change, updates);
      describeRevokedInvitations(change, updates);
      describePromotePending(change, updates);
      describeNewTitle(change, updates);
      describeNewAvatar(change, updates);
      describeNewTimer(change, updates);
      describeNewAttributeAccess(change, updates);
      describeNewMembershipAccess(change, updates);

      describeMemberRemovals(change, updates);

      if (updates.isEmpty()) {
        describeUnknownChange(change, updates);
      }
    }

    return updates;
  }

  /**
   * Handles case of future protocol versions where we don't know what has changed.
   */
  private void describeUnknownChange(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (editorIsYou) {
      updates.add(updateDescription(context.getString(R.string.MessageRecord_you_updated_group)));
    } else {
      updates.add(updateDescription(change.getEditor(), (editor) -> context.getString(R.string.MessageRecord_s_updated_group, editor)));
    }
  }

  private void describeUnknownEditorUnknownChange(@NonNull List<UpdateDescription> updates) {
    updates.add(updateDescription(context.getString(R.string.MessageRecord_the_group_was_updated)));
  }

  private void describeMemberAdditions(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    for (DecryptedMember member : change.getNewMembersList()) {
      boolean newMemberIsYou = member.getUuid().equals(selfUuidBytes);

      if (editorIsYou) {
        if (newMemberIsYou) {
          updates.add(0, updateDescription(context.getString(R.string.MessageRecord_you_joined_the_group)));
        } else {
          updates.add(updateDescription(member.getUuid(), added -> context.getString(R.string.MessageRecord_you_added_s, added)));
        }
      } else {
        if (newMemberIsYou) {
          updates.add(0, updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_added_you, editor)));
        } else {
          if (member.getUuid().equals(change.getEditor())) {
            updates.add(updateDescription(member.getUuid(), newMember -> context.getString(R.string.MessageRecord_s_joined_the_group, newMember)));
          } else {
            updates.add(updateDescription(change.getEditor(), member.getUuid(), (editor, newMember) -> context.getString(R.string.MessageRecord_s_added_s, editor, newMember)));
          }
        }
      }
    }
  }

  private void describeUnknownEditorMemberAdditions(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    for (DecryptedMember member : change.getNewMembersList()) {
      boolean newMemberIsYou = member.getUuid().equals(selfUuidBytes);

      if (newMemberIsYou) {
        updates.add(0, updateDescription(context.getString(R.string.MessageRecord_you_joined_the_group)));
      } else {
        updates.add(updateDescription(member.getUuid(), newMember -> context.getString(R.string.MessageRecord_s_joined_the_group, newMember)));
      }
    }
  }

  private void describeMemberRemovals(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    for (ByteString member : change.getDeleteMembersList()) {
      boolean removedMemberIsYou = member.equals(selfUuidBytes);

      if (editorIsYou) {
        if (removedMemberIsYou) {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_you_left_the_group)));
        } else {
          updates.add(updateDescription(member, removedMember -> context.getString(R.string.MessageRecord_you_removed_s, removedMember)));
        }
      } else {
        if (removedMemberIsYou) {
          updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_removed_you_from_the_group, editor)));
        } else {
          if (member.equals(change.getEditor())) {
            updates.add(updateDescription(member, leavingMember -> context.getString(R.string.MessageRecord_s_left_the_group, leavingMember)));
          } else {
            updates.add(updateDescription(change.getEditor(), member, (editor, removedMember) -> context.getString(R.string.MessageRecord_s_removed_s, editor, removedMember)));
          }
        }
      }
    }
  }

  private void describeUnknownEditorMemberRemovals(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    for (ByteString member : change.getDeleteMembersList()) {
      boolean removedMemberIsYou = member.equals(selfUuidBytes);

      if (removedMemberIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_are_no_longer_in_the_group)));
      } else {
        updates.add(updateDescription(member, oldMember -> context.getString(R.string.MessageRecord_s_is_no_longer_in_the_group, oldMember)));
      }
    }
  }

  private void describeModifyMemberRoles(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    for (DecryptedModifyMemberRole roleChange : change.getModifyMemberRolesList()) {
      boolean changedMemberIsYou = roleChange.getUuid().equals(selfUuidBytes);
      if (roleChange.getRole() == Member.Role.ADMINISTRATOR) {
        if (editorIsYou) {
          updates.add(updateDescription(roleChange.getUuid(), newAdmin -> context.getString(R.string.MessageRecord_you_made_s_an_admin, newAdmin)));
        } else {
          if (changedMemberIsYou) {
            updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_made_you_an_admin, editor)));
          } else {
            updates.add(updateDescription(change.getEditor(), roleChange.getUuid(), (editor, newAdmin) -> context.getString(R.string.MessageRecord_s_made_s_an_admin, editor, newAdmin)));

          }
        }
      } else {
        if (editorIsYou) {
          updates.add(updateDescription(roleChange.getUuid(), oldAdmin -> context.getString(R.string.MessageRecord_you_revoked_admin_privileges_from_s, oldAdmin)));
        } else {
          if (changedMemberIsYou) {
            updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_revoked_your_admin_privileges, editor)));
          } else {
            updates.add(updateDescription(change.getEditor(), roleChange.getUuid(), (editor, oldAdmin) -> context.getString(R.string.MessageRecord_s_revoked_admin_privileges_from_s, editor, oldAdmin)));
          }
        }
      }
    }
  }

  private void describeUnknownEditorModifyMemberRoles(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    for (DecryptedModifyMemberRole roleChange : change.getModifyMemberRolesList()) {
      boolean changedMemberIsYou = roleChange.getUuid().equals(selfUuidBytes);

      if (roleChange.getRole() == Member.Role.ADMINISTRATOR) {
        if (changedMemberIsYou) {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_you_are_now_an_admin)));
        } else {
          updates.add(updateDescription(roleChange.getUuid(), newAdmin -> context.getString(R.string.MessageRecord_s_is_now_an_admin, newAdmin)));
        }
      } else {
        if (changedMemberIsYou) {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_you_are_no_longer_an_admin)));
        } else {
          updates.add(updateDescription(roleChange.getUuid(), oldAdmin -> context.getString(R.string.MessageRecord_s_is_no_longer_an_admin, oldAdmin)));
        }
      }
    }
  }

  private void describeInvitations(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);
    int notYouInviteCount = 0;

    for (DecryptedPendingMember invitee : change.getNewPendingMembersList()) {
      boolean newMemberIsYou = invitee.getUuid().equals(selfUuidBytes);

      if (newMemberIsYou) {
        updates.add(0, updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_invited_you_to_the_group, editor)));
      } else {
        if (editorIsYou) {
          updates.add(updateDescription(invitee.getUuid(), newInvitee -> context.getString(R.string.MessageRecord_you_invited_s_to_the_group, newInvitee)));
        } else {
          notYouInviteCount++;
        }
      }
    }

    if (notYouInviteCount > 0) {
      final int notYouInviteCountFinalCopy = notYouInviteCount;
      updates.add(updateDescription(change.getEditor(), editor -> context.getResources().getQuantityString(R.plurals.MessageRecord_s_invited_members, notYouInviteCountFinalCopy, editor, notYouInviteCountFinalCopy)));
    }
  }

  private void describeUnknownEditorInvitations(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    int notYouInviteCount = 0;

    for (DecryptedPendingMember invitee : change.getNewPendingMembersList()) {
      boolean newMemberIsYou = invitee.getUuid().equals(selfUuidBytes);

      if (newMemberIsYou) {
        UUID uuid = UuidUtil.fromByteStringOrUnknown(invitee.getAddedByUuid());

        if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
          updates.add(0, updateDescription(context.getString(R.string.MessageRecord_you_were_invited_to_the_group)));
        } else {
          updates.add(0, updateDescription(invitee.getAddedByUuid(), editor -> context.getString(R.string.MessageRecord_s_invited_you_to_the_group, editor)));
        }
      } else {
        notYouInviteCount++;
      }
    }

    if (notYouInviteCount > 0) {
      updates.add(updateDescription(context.getResources().getQuantityString(R.plurals.MessageRecord_d_people_were_invited_to_the_group, notYouInviteCount, notYouInviteCount)));
    }
  }

  private void describeRevokedInvitations(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);
    int notDeclineCount = 0;

    for (DecryptedPendingMemberRemoval invitee : change.getDeletePendingMembersList()) {
      boolean decline = invitee.getUuid().equals(change.getEditor());
      if (decline) {
        if (editorIsYou) {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_you_declined_the_invitation_to_the_group)));
        } else {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_someone_declined_an_invitation_to_the_group)));
        }
      } else if (invitee.getUuid().equals(selfUuidBytes)) {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_revoked_your_invitation_to_the_group, editor)));
      } else {
        notDeclineCount++;
      }
    }

    if (notDeclineCount > 0) {
      if (editorIsYou) {
        updates.add(updateDescription(context.getResources().getQuantityString(R.plurals.MessageRecord_you_revoked_invites, notDeclineCount, notDeclineCount)));
      } else {
        final int notDeclineCountFinalCopy = notDeclineCount;
        updates.add(updateDescription(change.getEditor(), editor -> context.getResources().getQuantityString(R.plurals.MessageRecord_s_revoked_invites, notDeclineCountFinalCopy, editor, notDeclineCountFinalCopy)));
      }
    }
  }

  private void describeUnknownEditorRevokedInvitations(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    int notDeclineCount = 0;

    for (DecryptedPendingMemberRemoval invitee : change.getDeletePendingMembersList()) {
      boolean inviteeWasYou = invitee.getUuid().equals(selfUuidBytes);

      if (inviteeWasYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_an_admin_revoked_your_invitation_to_the_group)));
      } else {
        notDeclineCount++;
      }
    }

    if (notDeclineCount > 0) {
      updates.add(updateDescription(context.getResources().getQuantityString(R.plurals.MessageRecord_d_invitations_were_revoked, notDeclineCount, notDeclineCount)));
    }
  }

  private void describePromotePending(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    for (DecryptedMember newMember : change.getPromotePendingMembersList()) {
      ByteString uuid = newMember.getUuid();
      boolean newMemberIsYou = uuid.equals(selfUuidBytes);

      if (editorIsYou) {
        if (newMemberIsYou) {
          updates.add(updateDescription(context.getString(R.string.MessageRecord_you_accepted_invite)));
        } else {
          updates.add(updateDescription(uuid, newPromotedMember -> context.getString(R.string.MessageRecord_you_added_invited_member_s, newPromotedMember)));
        }
      } else {
        if (newMemberIsYou) {
          updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_added_you, editor)));
        } else {
          if (uuid.equals(change.getEditor())) {
            updates.add(updateDescription(uuid, newAcceptedMember -> context.getString(R.string.MessageRecord_s_accepted_invite, newAcceptedMember)));
          } else {
            updates.add(updateDescription(change.getEditor(), uuid, (editor, newAcceptedMember) -> context.getString(R.string.MessageRecord_s_added_invited_member_s, editor, newAcceptedMember)));
          }
        }
      }
    }
  }

  private void describeUnknownEditorPromotePending(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    for (DecryptedMember newMember : change.getPromotePendingMembersList()) {
      ByteString uuid = newMember.getUuid();
      boolean newMemberIsYou = uuid.equals(selfUuidBytes);

      if (newMemberIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_joined_the_group)));
      } else {
        updates.add(updateDescription(uuid, newMemberName -> context.getString(R.string.MessageRecord_s_joined_the_group, newMemberName)));
      }
    }
  }

  private void describeNewTitle(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (change.hasNewTitle()) {
      String newTitle = StringUtil.isolateBidi(change.getNewTitle().getValue());
      if (editorIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_changed_the_group_name_to_s, newTitle)));
      } else {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_changed_the_group_name_to_s, editor, newTitle)));
      }
    }
  }

  private void describeUnknownEditorNewTitle(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    if (change.hasNewTitle()) {
      updates.add(updateDescription(context.getString(R.string.MessageRecord_the_group_name_has_changed_to_s, StringUtil.isolateBidi(change.getNewTitle().getValue()))));
    }
  }

  private void describeNewAvatar(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (change.hasNewAvatar()) {
      if (editorIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_changed_the_group_avatar)));
      } else {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_changed_the_group_avatar, editor)));
      }
    }
  }

  private void describeUnknownEditorNewAvatar(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    if (change.hasNewAvatar()) {
      updates.add(updateDescription(context.getString(R.string.MessageRecord_the_group_group_avatar_has_been_changed)));
    }
  }

  private void describeNewTimer(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (change.hasNewTimer()) {
      String time = ExpirationUtil.getExpirationDisplayValue(context, change.getNewTimer().getDuration());
      if (editorIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_set_disappearing_message_time_to_s, time)));
      } else {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, editor, time)));
      }
    }
  }

  private void describeUnknownEditorNewTimer(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    if (change.hasNewTimer()) {
      String time = ExpirationUtil.getExpirationDisplayValue(context, change.getNewTimer().getDuration());
      updates.add(updateDescription(context.getString(R.string.MessageRecord_disappearing_message_time_set_to_s, time)));
    }
  }

  private void describeNewAttributeAccess(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (change.getNewAttributeAccess() != AccessControl.AccessRequired.UNKNOWN) {
      String accessLevel = GV2AccessLevelUtil.toString(context, change.getNewAttributeAccess());
      if (editorIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_changed_who_can_edit_group_info_to_s, accessLevel)));
      } else {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_changed_who_can_edit_group_info_to_s, editor, accessLevel)));
      }
    }
  }

  private void describeUnknownEditorNewAttributeAccess(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    if (change.getNewAttributeAccess() != AccessControl.AccessRequired.UNKNOWN) {
      String accessLevel = GV2AccessLevelUtil.toString(context, change.getNewAttributeAccess());
      updates.add(updateDescription(context.getString(R.string.MessageRecord_who_can_edit_group_info_has_been_changed_to_s, accessLevel)));
    }
  }

  private void describeNewMembershipAccess(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    boolean editorIsYou = change.getEditor().equals(selfUuidBytes);

    if (change.getNewMemberAccess() != AccessControl.AccessRequired.UNKNOWN) {
      String accessLevel = GV2AccessLevelUtil.toString(context, change.getNewMemberAccess());
      if (editorIsYou) {
        updates.add(updateDescription(context.getString(R.string.MessageRecord_you_changed_who_can_edit_group_membership_to_s, accessLevel)));
      } else {
        updates.add(updateDescription(change.getEditor(), editor -> context.getString(R.string.MessageRecord_s_changed_who_can_edit_group_membership_to_s, editor, accessLevel)));
      }
    }
  }

  private void describeUnknownEditorNewMembershipAccess(@NonNull DecryptedGroupChange change, @NonNull List<UpdateDescription> updates) {
    if (change.getNewMemberAccess() != AccessControl.AccessRequired.UNKNOWN) {
      String accessLevel = GV2AccessLevelUtil.toString(context, change.getNewMemberAccess());
      updates.add(updateDescription(context.getString(R.string.MessageRecord_who_can_edit_group_membership_has_been_changed_to_s, accessLevel)));
    }
  }

  interface DescribeMemberStrategy {

    /**
     * Map a UUID to a string that describes the group member.
     */
    @NonNull
    @WorkerThread
    String describe(@NonNull UUID uuid);
  }

  private interface StringFactory1Arg {
    String create(String arg1);
  }

  private interface StringFactory2Args {
    String create(String arg1, String arg2);
  }

  private static UpdateDescription updateDescription(@NonNull String string) {
    return UpdateDescription.staticDescription(string);
  }

  private UpdateDescription updateDescription(@NonNull ByteString uuid1Bytes, @NonNull StringFactory1Arg stringFactory) {
    UUID uuid1 = UuidUtil.fromByteStringOrUnknown(uuid1Bytes);

    return UpdateDescription.mentioning(Collections.singletonList(uuid1), () -> stringFactory.create(descriptionStrategy.describe(uuid1)));
  }

  private UpdateDescription updateDescription(@NonNull ByteString uuid1Bytes, @NonNull ByteString uuid2Bytes, @NonNull StringFactory2Args stringFactory) {
    UUID uuid1 = UuidUtil.fromByteStringOrUnknown(uuid1Bytes);
    UUID uuid2 = UuidUtil.fromByteStringOrUnknown(uuid2Bytes);

    return UpdateDescription.mentioning(Arrays.asList(uuid1, uuid2), () -> stringFactory.create(descriptionStrategy.describe(uuid1), descriptionStrategy.describe(uuid2)));
  }
}