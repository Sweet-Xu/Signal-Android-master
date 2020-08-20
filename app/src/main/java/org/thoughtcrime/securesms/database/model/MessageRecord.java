/*
 * Copyright (C) 2012 Moxie Marlinpsike
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
package org.thoughtcrime.securesms.database.model;

import android.content.Context;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.MmsSmsColumns;
import org.thoughtcrime.securesms.database.SmsDatabase;
import org.thoughtcrime.securesms.database.documents.IdentityKeyMismatch;
import org.thoughtcrime.securesms.database.documents.NetworkFailure;
import org.thoughtcrime.securesms.database.model.databaseprotos.DecryptedGroupV2Context;
import org.thoughtcrime.securesms.database.model.databaseprotos.ProfileChangeDetails;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Base64;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.GroupUtil;
import org.thoughtcrime.securesms.util.StringUtil;
import org.whispersystems.libsignal.util.guava.Function;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The base class for message record models that are displayed in
 * conversations, as opposed to models that are displayed in a thread list.
 * Encapsulates the shared data between both SMS and MMS messages.
 *
 * @author Moxie Marlinspike
 *
 */
public abstract class MessageRecord extends DisplayRecord {

  private static final String TAG = Log.tag(MessageRecord.class);

  private final Recipient                 individualRecipient;
  private final int                       recipientDeviceId;
  private final long                      id;
  private final List<IdentityKeyMismatch> mismatches;
  private final List<NetworkFailure>      networkFailures;
  private final int                       subscriptionId;
  private final long                      expiresIn;
  private final long                      expireStarted;
  private final boolean                   unidentified;
  private final List<ReactionRecord>      reactions;
  private final long                      serverTimestamp;
  private final boolean                   remoteDelete;

  MessageRecord(long id, String body, Recipient conversationRecipient,
                Recipient individualRecipient, int recipientDeviceId,
                long dateSent, long dateReceived, long dateServer, long threadId,
                int deliveryStatus, int deliveryReceiptCount, long type,
                List<IdentityKeyMismatch> mismatches,
                List<NetworkFailure> networkFailures,
                int subscriptionId, long expiresIn, long expireStarted,
                int readReceiptCount, boolean unidentified,
                @NonNull List<ReactionRecord> reactions, boolean remoteDelete)
  {
    super(body, conversationRecipient, dateSent, dateReceived,
          threadId, deliveryStatus, deliveryReceiptCount, type, readReceiptCount);
    this.id                  = id;
    this.individualRecipient = individualRecipient;
    this.recipientDeviceId   = recipientDeviceId;
    this.mismatches          = mismatches;
    this.networkFailures     = networkFailures;
    this.subscriptionId      = subscriptionId;
    this.expiresIn           = expiresIn;
    this.expireStarted       = expireStarted;
    this.unidentified        = unidentified;
    this.reactions           = reactions;
    this.serverTimestamp     = dateServer;
    this.remoteDelete        = remoteDelete;
  }

  public abstract boolean isMms();
  public abstract boolean isMmsNotification();

  public boolean isSecure() {
    return MmsSmsColumns.Types.isSecureType(type);
  }

  public boolean isLegacyMessage() {
    return MmsSmsColumns.Types.isLegacyType(type);
  }

  @Override
  public SpannableString getDisplayBody(@NonNull Context context) {
    UpdateDescription updateDisplayBody = getUpdateDisplayBody(context);

    if (updateDisplayBody != null) {
      return new SpannableString(updateDisplayBody.getString());
    }

    return new SpannableString(getBody());
  }

  public @Nullable UpdateDescription getUpdateDisplayBody(@NonNull Context context) {
    if (isGroupUpdate() && isGroupV2()) {
      return getGv2ChangeDescription(context, getBody());
    } else if (isGroupUpdate() && isOutgoing()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_you_updated_group));
    } else if (isGroupUpdate()) {
      return fromRecipient(getIndividualRecipient(), r -> GroupUtil.getNonV2GroupDescription(context, getBody()).toString(r));
    } else if (isGroupQuit() && isOutgoing()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_left_group));
    } else if (isGroupQuit()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.ConversationItem_group_action_left, r.getDisplayName(context)));
    } else if (isIncomingCall()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_called_you, r.getDisplayName(context)));
    } else if (isOutgoingCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_you_called));
    } else if (isMissedCall()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_missed_call));
    } else if (isJoined()) {
      return staticUpdateDescription(context.getString(R.string.MessageRecord_s_joined_signal, getIndividualRecipient().getDisplayName(context)));
    } else if (isExpirationTimerUpdate()) {
      int seconds = (int)(getExpiresIn() / 1000);
      if (seconds <= 0) {
        return isOutgoing() ? staticUpdateDescription(context.getString(R.string.MessageRecord_you_disabled_disappearing_messages))
                            : fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_disabled_disappearing_messages, r.getDisplayName(context)));
      }
      String time = ExpirationUtil.getExpirationDisplayValue(context, seconds);
      return isOutgoing() ? staticUpdateDescription(context.getString(R.string.MessageRecord_you_set_disappearing_message_time_to_s, time))
                          : fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_s_set_disappearing_message_time_to_s, r.getDisplayName(context), time));
    } else if (isIdentityUpdate()) {
      return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_your_safety_number_with_s_has_changed, r.getDisplayName(context)));
    } else if (isIdentityVerified()) {
      if (isOutgoing()) return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified, r.getDisplayName(context)));
      else              return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_verified_from_another_device, r.getDisplayName(context)));
    } else if (isIdentityDefault()) {
      if (isOutgoing()) return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified, r.getDisplayName(context)));
      else              return fromRecipient(getIndividualRecipient(), r -> context.getString(R.string.MessageRecord_you_marked_your_safety_number_with_s_unverified_from_another_device, r.getDisplayName(context)));
    } else if (isProfileChange()) {
      return staticUpdateDescription(getProfileChangeDescription(context));
    } else if (isEndSession()) {
      if (isOutgoing()) return staticUpdateDescription(context.getString(R.string.SmsMessageRecord_secure_session_reset));
      else              return fromRecipient(getIndividualRecipient(), r-> context.getString(R.string.SmsMessageRecord_secure_session_reset_s, r.getDisplayName(context)));
    }

    return null;
  }

  public static @NonNull UpdateDescription getGv2ChangeDescription(@NonNull Context context, @NonNull String body) {
    try {
      ShortStringDescriptionStrategy descriptionStrategy     = new ShortStringDescriptionStrategy(context);
      byte[]                         decoded                 = Base64.decode(body);
      DecryptedGroupV2Context        decryptedGroupV2Context = DecryptedGroupV2Context.parseFrom(decoded);
      GroupsV2UpdateMessageProducer  updateMessageProducer   = new GroupsV2UpdateMessageProducer(context, descriptionStrategy, Recipient.self().getUuid().get());

      if (decryptedGroupV2Context.hasChange() && decryptedGroupV2Context.getGroupState().getRevision() > 0) {
        return UpdateDescription.concatWithNewLines(updateMessageProducer.describeChanges(decryptedGroupV2Context.getChange()));
      } else {
        return updateMessageProducer.describeNewGroup(decryptedGroupV2Context.getGroupState());
      }
    } catch (IOException e) {
      Log.w(TAG, "GV2 Message update detail could not be read", e);
      return staticUpdateDescription(context.getString(R.string.MessageRecord_group_updated));
    }
  }

  private static @NonNull UpdateDescription fromRecipient(@NonNull Recipient recipient, @NonNull Function<Recipient, String> stringFunction) {
    return UpdateDescription.mentioning(Collections.singletonList(recipient.getUuid().or(UuidUtil.UNKNOWN_UUID)), () -> stringFunction.apply(recipient.resolve()));
  }

  private static @NonNull UpdateDescription staticUpdateDescription(@NonNull String string) {
    return UpdateDescription.staticDescription(string);
  }

  private @NonNull String getProfileChangeDescription(@NonNull Context context) {
    try {
      byte[]               decoded              = Base64.decode(getBody());
      ProfileChangeDetails profileChangeDetails = ProfileChangeDetails.parseFrom(decoded);

      if (profileChangeDetails.hasProfileNameChange()) {
        String displayName  = getIndividualRecipient().getDisplayName(context);
        String newName      = StringUtil.isolateBidi(ProfileName.fromSerialized(profileChangeDetails.getProfileNameChange().getNew()).toString());
        String previousName = StringUtil.isolateBidi(ProfileName.fromSerialized(profileChangeDetails.getProfileNameChange().getPrevious()).toString());

        if (getIndividualRecipient().isSystemContact()) {
          return context.getString(R.string.MessageRecord_changed_their_profile_name_from_to, displayName, previousName, newName);
        } else {
          return context.getString(R.string.MessageRecord_changed_their_profile_name_to, previousName, newName);
        }
      }
    } catch (IOException e) {
      Log.w(TAG, "Profile name change details could not be read", e);
    }

    return context.getString(R.string.MessageRecord_changed_their_profile, getIndividualRecipient().getDisplayName(context));
  }

  /**
   * Describes a UUID by it's corresponding recipient's {@link Recipient#getDisplayName(Context)}.
   */
  private static class ShortStringDescriptionStrategy implements GroupsV2UpdateMessageProducer.DescribeMemberStrategy {

    private final Context context;

   ShortStringDescriptionStrategy(@NonNull Context context) {
      this.context = context;
   }

   @Override
   public @NonNull String describe(@NonNull UUID uuid) {
     if (UuidUtil.UNKNOWN_UUID.equals(uuid)) {
       return context.getString(R.string.MessageRecord_unknown);
     }
     return Recipient.resolved(RecipientId.from(uuid, null)).getDisplayName(context);
   }
 }

  public long getId() {
    return id;
  }

  public boolean isPush() {
    return SmsDatabase.Types.isPushType(type) && !SmsDatabase.Types.isForcedSms(type);
  }

  public long getTimestamp() {
    if (isPush() && getDateSent() < getDateReceived()) {
      return getDateSent();
    }
    return getDateReceived();
  }

  public long getServerTimestamp() {
    return serverTimestamp;
  }

  public boolean isForcedSms() {
    return SmsDatabase.Types.isForcedSms(type);
  }

  public boolean isIdentityVerified() {
    return SmsDatabase.Types.isIdentityVerified(type);
  }

  public boolean isIdentityDefault() {
    return SmsDatabase.Types.isIdentityDefault(type);
  }

  public boolean isIdentityMismatchFailure() {
    return mismatches != null && !mismatches.isEmpty();
  }

  public boolean isBundleKeyExchange() {
    return SmsDatabase.Types.isBundleKeyExchange(type);
  }

  public boolean isContentBundleKeyExchange() {
    return SmsDatabase.Types.isContentBundleKeyExchange(type);
  }

  public boolean isIdentityUpdate() {
    return SmsDatabase.Types.isIdentityUpdate(type);
  }

  public boolean isCorruptedKeyExchange() {
    return SmsDatabase.Types.isCorruptedKeyExchange(type);
  }

  public boolean isInvalidVersionKeyExchange() {
    return SmsDatabase.Types.isInvalidVersionKeyExchange(type);
  }

  public boolean isUpdate() {
    return isGroupAction() || isJoined() || isExpirationTimerUpdate() || isCallLog() ||
           isEndSession()  || isIdentityUpdate() || isIdentityVerified() || isIdentityDefault() || isProfileChange();
  }

  public boolean isMediaPending() {
    return false;
  }

  public Recipient getIndividualRecipient() {
    return individualRecipient.live().get();
  }

  public int getRecipientDeviceId() {
    return recipientDeviceId;
  }

  public long getType() {
    return type;
  }

  public List<IdentityKeyMismatch> getIdentityKeyMismatches() {
    return mismatches;
  }

  public List<NetworkFailure> getNetworkFailures() {
    return networkFailures;
  }

  public boolean hasNetworkFailures() {
    return networkFailures != null && !networkFailures.isEmpty();
  }

  public boolean hasFailedWithNetworkFailures() {
    return isFailed() && ((getRecipient().isPushGroup() && hasNetworkFailures()) || !isIdentityMismatchFailure());
  }

  protected static SpannableString emphasisAdded(String sequence) {
    SpannableString spannable = new SpannableString(sequence);
    spannable.setSpan(new RelativeSizeSpan(0.9f), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
    spannable.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), 0, sequence.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

    return spannable;
  }

  public boolean equals(Object other) {
    return other != null                              &&
           other instanceof MessageRecord             &&
           ((MessageRecord) other).getId() == getId() &&
           ((MessageRecord) other).isMms() == isMms();
  }

  public int hashCode() {
    return (int)getId();
  }

  public int getSubscriptionId() {
    return subscriptionId;
  }

  public long getExpiresIn() {
    return expiresIn;
  }

  public long getExpireStarted() {
    return expireStarted;
  }

  public boolean isUnidentified() {
    return unidentified;
  }

  public boolean isViewOnce() {
    return false;
  }

  public boolean isRemoteDelete() {
    return remoteDelete;
  }

  public @NonNull List<ReactionRecord> getReactions() {
    return reactions;
  }

  public boolean hasSelfMention() {
    return false;
  }
}
