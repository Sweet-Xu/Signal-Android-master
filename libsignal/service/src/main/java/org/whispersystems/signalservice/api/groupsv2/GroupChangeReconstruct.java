package org.whispersystems.signalservice.api.groupsv2;

import com.google.protobuf.ByteString;

import org.signal.storageservice.protos.groups.local.DecryptedGroup;
import org.signal.storageservice.protos.groups.local.DecryptedGroupChange;
import org.signal.storageservice.protos.groups.local.DecryptedMember;
import org.signal.storageservice.protos.groups.local.DecryptedModifyMemberRole;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMember;
import org.signal.storageservice.protos.groups.local.DecryptedPendingMemberRemoval;
import org.signal.storageservice.protos.groups.local.DecryptedString;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class GroupChangeReconstruct {

  /**
   * Given a {@param fromState} and a {@param toState} creates a {@link DecryptedGroupChange} that would take the {@param fromState} to the {@param toState}.
   */
  public static DecryptedGroupChange reconstructGroupChange(DecryptedGroup fromState, DecryptedGroup toState) {
    DecryptedGroupChange.Builder builder = DecryptedGroupChange.newBuilder()
                                                               .setRevision(toState.getRevision());

    if (!fromState.getTitle().equals(toState.getTitle())) {
      builder.setNewTitle(DecryptedString.newBuilder().setValue(toState.getTitle()));
    }

    if (!fromState.getAvatar().equals(toState.getAvatar())) {
      builder.setNewAvatar(DecryptedString.newBuilder().setValue(toState.getAvatar()));
    }

    if (!fromState.getDisappearingMessagesTimer().equals(toState.getDisappearingMessagesTimer())) {
      builder.setNewTimer(toState.getDisappearingMessagesTimer());
    }

    if (!fromState.getAccessControl().getAttributes().equals(toState.getAccessControl().getAttributes())) {
      builder.setNewAttributeAccess(toState.getAccessControl().getAttributes());
    }

    if (!fromState.getAccessControl().getMembers().equals(toState.getAccessControl().getMembers())) {
      builder.setNewMemberAccess(toState.getAccessControl().getMembers());
    }

    Set<ByteString> fromStateMemberUuids = membersToSetOfUuids(fromState.getMembersList());
    Set<ByteString> toStateMemberUuids   = membersToSetOfUuids(toState.getMembersList());

    Set<ByteString> pendingMembersListA = pendingMembersToSetOfUuids(fromState.getPendingMembersList());
    Set<ByteString> pendingMembersListB = pendingMembersToSetOfUuids(toState.getPendingMembersList());

    Set<ByteString> removedPendingMemberUuids = subtract(pendingMembersListA, pendingMembersListB);
    Set<ByteString> newPendingMemberUuids     = subtract(pendingMembersListB, pendingMembersListA);
    Set<ByteString> removedMemberUuids        = subtract(fromStateMemberUuids, toStateMemberUuids);
    Set<ByteString> newMemberUuids            = subtract(toStateMemberUuids, fromStateMemberUuids);

    Set<ByteString>             addedByInvitationUuids   = intersect(newMemberUuids, removedPendingMemberUuids);
    Set<DecryptedMember>        addedMembersByInvitation = intersectByUUID(toState.getMembersList(), addedByInvitationUuids);
    Set<DecryptedMember>        addedMembers             = intersectByUUID(toState.getMembersList(), subtract(newMemberUuids, addedByInvitationUuids));
    Set<DecryptedPendingMember> uninvitedMembers         = intersectPendingByUUID(fromState.getPendingMembersList(), subtract(removedPendingMemberUuids, addedByInvitationUuids));

    for (DecryptedMember member : intersectByUUID(fromState.getMembersList(), removedMemberUuids)) {
      builder.addDeleteMembers(member.getUuid());
    }

    for (DecryptedMember member : addedMembers) {
      builder.addNewMembers(member);
    }

    for (DecryptedMember member : addedMembersByInvitation) {
      builder.addPromotePendingMembers(member);
    }

    for (DecryptedPendingMember uninvitedMember : uninvitedMembers) {
      builder.addDeletePendingMembers(DecryptedPendingMemberRemoval.newBuilder()
                                                                   .setUuid(uninvitedMember.getUuid())
                                                                   .setUuidCipherText(uninvitedMember.getUuidCipherText()));
    }

    for (DecryptedPendingMember invitedMember : intersectPendingByUUID(toState.getPendingMembersList(), newPendingMemberUuids)) {
      builder.addNewPendingMembers(invitedMember);
    }

    Set<ByteString>                  consistentMemberUuids = intersect(fromStateMemberUuids, toStateMemberUuids);
    Set<DecryptedMember>             changedMembers        = intersectByUUID(subtract(toState.getMembersList(), fromState.getMembersList()), consistentMemberUuids);
    Map<ByteString, DecryptedMember> membersUuidMap        = uuidMap(fromState.getMembersList());

    for (DecryptedMember newState : changedMembers) {
      DecryptedMember oldState = membersUuidMap.get(newState.getUuid());
      if (oldState.getRole() != newState.getRole()) {
        builder.addModifyMemberRoles(DecryptedModifyMemberRole.newBuilder()
                                                              .setUuid(newState.getUuid())
                                                              .setRole(newState.getRole()));
      }

      if (oldState.getProfileKey() != newState.getProfileKey()) {
        builder.addModifiedProfileKeys(newState);
      }
    }

    return builder.build();
  }

  private static Map<ByteString, DecryptedMember> uuidMap(List<DecryptedMember> membersList) {
    HashMap<ByteString, DecryptedMember> map = new HashMap<>(membersList.size());
    for (DecryptedMember member : membersList) {
      map.put(member.getUuid(), member);
    }
    return map;
  }

  private static Set<DecryptedMember> intersectByUUID(Collection<DecryptedMember> members, Set<ByteString> uuids) {
    Set<DecryptedMember> result = new HashSet<>(members.size());
    for (DecryptedMember member : members) {
      if (uuids.contains(member.getUuid()))
        result.add(member);
    }
    return result;
  }

  private static Set<DecryptedPendingMember> intersectPendingByUUID(Collection<DecryptedPendingMember> members, Set<ByteString> uuids) {
    Set<DecryptedPendingMember> result = new HashSet<>(members.size());
    for (DecryptedPendingMember member : members) {
      if (uuids.contains(member.getUuid()))
        result.add(member);
    }
    return result;
  }

  private static Set<ByteString> pendingMembersToSetOfUuids(Collection<DecryptedPendingMember> pendingMembers) {
    HashSet<ByteString> uuids = new HashSet<>(pendingMembers.size());
    for (DecryptedPendingMember pendingMember : pendingMembers) {
      uuids.add(pendingMember.getUuid());
    }
    return uuids;
  }

  private static Set<ByteString> membersToSetOfUuids(Collection<DecryptedMember> members) {
    HashSet<ByteString> uuids = new HashSet<>(members.size());
    for (DecryptedMember member : members) {
      uuids.add(member.getUuid());
    }
    return uuids;
  }

  private static <T> Set<T> subtract(Collection<T> a, Collection<T> b) {
    Set<T> result = new HashSet<>(a);
    result.removeAll(b);
    return result;
  }

  private static <T> Set<T> intersect(Collection<T> a, Collection<T> b) {
    Set<T> result = new HashSet<>(a);
    result.retainAll(b);
    return result;
  }
}
