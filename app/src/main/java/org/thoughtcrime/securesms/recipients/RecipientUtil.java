package org.thoughtcrime.securesms.recipients;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.contacts.sync.DirectoryHelper;
import org.thoughtcrime.securesms.database.DatabaseFactory;
import org.thoughtcrime.securesms.database.RecipientDatabase.RegisteredState;
import org.thoughtcrime.securesms.database.ThreadDatabase;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.groups.GroupChangeBusyException;
import org.thoughtcrime.securesms.groups.GroupChangeException;
import org.thoughtcrime.securesms.groups.GroupChangeFailedException;
import org.thoughtcrime.securesms.groups.GroupManager;
import org.thoughtcrime.securesms.jobs.DirectoryRefreshJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceBlockedUpdateJob;
import org.thoughtcrime.securesms.jobs.MultiDeviceMessageRequestResponseJob;
import org.thoughtcrime.securesms.jobs.RotateProfileKeyJob;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.storage.StorageSyncHelper;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class RecipientUtil {

  private static final String TAG = Log.tag(RecipientUtil.class);

  /**
   * This method will do it's best to craft a fully-populated {@link SignalServiceAddress} based on
   * the provided recipient. This includes performing a possible network request if no UUID is
   * available.
   */
  @WorkerThread
  public static @NonNull SignalServiceAddress toSignalServiceAddressBestEffort(@NonNull Context context, @NonNull Recipient recipient) {
    try {
      return toSignalServiceAddress(context, recipient);
    } catch (IOException e) {
      Log.w(TAG, "Failed to populate address!", e);
      return new SignalServiceAddress(recipient.getUuid().orNull(), recipient.getE164().orNull());
    }
  }

  /**
   * This method will do it's best to craft a fully-populated {@link SignalServiceAddress} based on
   * the provided recipient. This includes performing a possible network request if no UUID is
   * available.
   */
  @WorkerThread
  public static @NonNull SignalServiceAddress toSignalServiceAddress(@NonNull Context context, @NonNull Recipient recipient)
      throws IOException
  {
    recipient = recipient.resolve();

    if (!recipient.getUuid().isPresent() && !recipient.getE164().isPresent()) {
      throw new AssertionError(recipient.getId() + " - No UUID or phone number!");
    }

    if (FeatureFlags.cds() && !recipient.getUuid().isPresent()) {
      Log.i(TAG, recipient.getId() + " is missing a UUID...");
      RegisteredState state = DirectoryHelper.refreshDirectoryFor(context, recipient, false);

      recipient = Recipient.resolved(recipient.getId());
      Log.i(TAG, "Successfully performed a UUID fetch for " + recipient.getId() + ". Registered: " + state);
    }

    return new SignalServiceAddress(Optional.fromNullable(recipient.getUuid().orNull()), Optional.fromNullable(recipient.resolve().getE164().orNull()));
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddresses(@NonNull Context context, @NonNull List<RecipientId> recipients)
      throws IOException
  {
    List<SignalServiceAddress> addresses = new ArrayList<>(recipients.size());

    for (RecipientId id : recipients) {
      addresses.add(toSignalServiceAddress(context, Recipient.resolved(id)));
    }

    return addresses;
  }

  public static @NonNull List<SignalServiceAddress> toSignalServiceAddressesFromResolved(@NonNull Context context, @NonNull List<Recipient> recipients)
      throws IOException
  {
    List<SignalServiceAddress> addresses = new ArrayList<>(recipients.size());

    for (Recipient recipient : recipients) {
      addresses.add(toSignalServiceAddress(context, recipient));
    }

    return addresses;
  }

  public static boolean isBlockable(@NonNull Recipient recipient) {
    Recipient resolved = recipient.resolve();
    return resolved.isPushGroup() || resolved.hasServiceIdentifier();
  }

  /**
   * You can call this for non-groups and not have to handle any network errors.
   */
  @WorkerThread
  public static void blockNonGroup(@NonNull Context context, @NonNull Recipient recipient) {
    if (recipient.isGroup()) {
      throw new AssertionError();
    }

    try {
      block(context, recipient);
    } catch (GroupChangeException | IOException e) {
      throw new AssertionError(e);
    }
  }

  /**
   * You can call this for any type of recipient but must handle network errors that can occur from
   * GV2.
   * <p>
   * GV2 operations can also take longer due to the network.
   */
  @WorkerThread
  public static void block(@NonNull Context context, @NonNull Recipient recipient)
      throws GroupChangeBusyException, IOException, GroupChangeFailedException
  {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }

    recipient = recipient.resolve();

    if (recipient.isGroup() && recipient.getGroupId().get().isPush()) {
      GroupManager.leaveGroupFromBlockOrMessageRequest(context, recipient.getGroupId().get().requirePush());
    }

    DatabaseFactory.getRecipientDatabase(context).setBlocked(recipient.getId(), true);

    if (recipient.isSystemContact() || recipient.isProfileSharing() || isProfileSharedViaGroup(context, recipient)) {
      ApplicationDependencies.getJobManager().add(new RotateProfileKeyJob());
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient.getId(), false);
    }

    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
  }

  @WorkerThread
  public static void unblock(@NonNull Context context, @NonNull Recipient recipient) {
    if (!isBlockable(recipient)) {
      throw new AssertionError("Recipient is not blockable!");
    }

    DatabaseFactory.getRecipientDatabase(context).setBlocked(recipient.getId(), false);
    ApplicationDependencies.getJobManager().add(new MultiDeviceBlockedUpdateJob());
    StorageSyncHelper.scheduleSyncForDataChange();
    ApplicationDependencies.getJobManager().add(MultiDeviceMessageRequestResponseJob.forAccept(recipient.getId()));
  }

  /**
   * If true, the new message request UI does not need to be shown, and it's safe to send read
   * receipts.
   *
   * Note that this does not imply that a user has explicitly accepted a message request -- it could
   * also be the case that the thread in question is for a system contact or something of the like.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, long threadId) {
    if (threadId < 0) {
      return true;
    }

    ThreadDatabase threadDatabase  = DatabaseFactory.getThreadDatabase(context);
    Recipient      threadRecipient = threadDatabase.getRecipientForThreadId(threadId);

    if (threadRecipient == null) {
      return true;
    }

    return isMessageRequestAccepted(context, threadId, threadRecipient);
  }

  /**
   * See {@link #isMessageRequestAccepted(Context, long)}.
   */
  @WorkerThread
  public static boolean isMessageRequestAccepted(@NonNull Context context, @Nullable Recipient threadRecipient) {
    if (threadRecipient == null) {
      return true;
    }

    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdFor(threadRecipient);
    return isMessageRequestAccepted(context, threadId, threadRecipient);
  }

  /**
   * @return True if a conversation existed before we enabled message requests, otherwise false.
   */
  @WorkerThread
  public static boolean isPreMessageRequestThread(@NonNull Context context, long threadId) {
    long beforeTime = SignalStore.misc().getMessageRequestEnableTime();
    return DatabaseFactory.getMmsSmsDatabase(context).getConversationCount(threadId, beforeTime) > 0;
  }

  @WorkerThread
  public static void shareProfileIfFirstSecureMessage(@NonNull Context context, @NonNull Recipient recipient) {
    long threadId = DatabaseFactory.getThreadDatabase(context).getThreadIdIfExistsFor(recipient);

    if (isPreMessageRequestThread(context, threadId)) {
      return;
    }

    boolean firstMessage = DatabaseFactory.getMmsSmsDatabase(context).getOutgoingSecureConversationCount(threadId) == 0;

    if (firstMessage) {
      DatabaseFactory.getRecipientDatabase(context).setProfileSharing(recipient.getId(), true);
    }
  }

  public static boolean isLegacyProfileSharingAccepted(@NonNull Recipient threadRecipient) {
    return threadRecipient.isLocalNumber()    ||
           threadRecipient.isProfileSharing() ||
           threadRecipient.isSystemContact()  ||
           !threadRecipient.isRegistered();
  }

  @WorkerThread
  private static boolean isMessageRequestAccepted(@NonNull Context context, long threadId, @NonNull Recipient threadRecipient) {
    return threadRecipient.isLocalNumber()                       ||
           threadRecipient.isProfileSharing()                    ||
           threadRecipient.isSystemContact()                     ||
           threadRecipient.isForceSmsSelection()                 ||
           !threadRecipient.isRegistered()                       ||
           hasSentMessageInThread(context, threadId)             ||
           noSecureMessagesAndNoCallsInThread(context, threadId) ||
           isPreMessageRequestThread(context, threadId);
  }

  @WorkerThread
  private static boolean hasSentMessageInThread(@NonNull Context context, long threadId) {
    return DatabaseFactory.getMmsSmsDatabase(context).getOutgoingSecureConversationCount(threadId) != 0;
  }

  @WorkerThread
  private static boolean noSecureMessagesAndNoCallsInThread(@NonNull Context context, long threadId) {
    return DatabaseFactory.getMmsSmsDatabase(context).getSecureConversationCount(threadId) == 0 &&
           !DatabaseFactory.getThreadDatabase(context).hasReceivedAnyCallsSince(threadId, 0);
  }

  @WorkerThread
  private static boolean isProfileSharedViaGroup(@NonNull Context context, @NonNull Recipient recipient) {
    return Stream.of(DatabaseFactory.getGroupDatabase(context).getPushGroupsContainingMember(recipient.getId()))
                 .anyMatch(group -> Recipient.resolved(group.getRecipientId()).isProfileSharing());
  }
}
