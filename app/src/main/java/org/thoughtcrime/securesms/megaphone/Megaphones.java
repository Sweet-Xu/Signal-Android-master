package org.thoughtcrime.securesms.megaphone;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.annimon.stream.Stream;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.conversationlist.ConversationListFragment;
import org.thoughtcrime.securesms.database.model.MegaphoneRecord;
import org.thoughtcrime.securesms.dependencies.ApplicationDependencies;
import org.thoughtcrime.securesms.jobmanager.impl.NetworkConstraint;
import org.thoughtcrime.securesms.keyvalue.SignalStore;
import org.thoughtcrime.securesms.lock.SignalPinReminderDialog;
import org.thoughtcrime.securesms.lock.SignalPinReminders;
import org.thoughtcrime.securesms.lock.v2.CreateKbsPinActivity;
import org.thoughtcrime.securesms.lock.v2.KbsMigrationActivity;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.messagerequests.MessageRequestMegaphoneActivity;
import org.thoughtcrime.securesms.profiles.ProfileName;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Creating a new megaphone:
 * - Add an enum to {@link Event}
 * - Return a megaphone in {@link #forRecord(Context, MegaphoneRecord)}
 * - Include the event in {@link #buildDisplayOrder()}
 *
 * Common patterns:
 * - For events that have a snooze-able recurring display schedule, use a {@link RecurringSchedule}.
 * - For events guarded by feature flags, set a {@link ForeverSchedule} with false in
 *   {@link #buildDisplayOrder()}.
 * - For events that change, return different megaphones in {@link #forRecord(Context, MegaphoneRecord)}
 *   based on whatever properties you're interested in.
 */
public final class Megaphones {

  private static final String TAG = Log.tag(Megaphones.class);

  private static final MegaphoneSchedule ALWAYS = new ForeverSchedule(true);
  private static final MegaphoneSchedule NEVER  = new ForeverSchedule(false);

  private Megaphones() {}

  static @Nullable Megaphone getNextMegaphone(@NonNull Context context, @NonNull Map<Event, MegaphoneRecord> records) {
    long currentTime = System.currentTimeMillis();

    List<Megaphone> megaphones = Stream.of(buildDisplayOrder(context))
                                       .filter(e -> {
                                         MegaphoneRecord   record = Objects.requireNonNull(records.get(e.getKey()));
                                         MegaphoneSchedule schedule = e.getValue();

                                         return !record.isFinished() && schedule.shouldDisplay(record.getSeenCount(), record.getLastSeen(), record.getFirstVisible(), currentTime);
                                       })
                                       .map(Map.Entry::getKey)
                                       .map(records::get)
                                       .map(record -> Megaphones.forRecord(context, record))
                                       .toList();

    boolean hasOptional  = Stream.of(megaphones).anyMatch(m -> !m.isMandatory());
    boolean hasMandatory = Stream.of(megaphones).anyMatch(Megaphone::isMandatory);

    if (hasOptional && hasMandatory) {
      megaphones = Stream.of(megaphones).filter(Megaphone::isMandatory).toList();
    }

    if (megaphones.size() > 0) {
      return megaphones.get(0);
    } else {
      return null;
    }
  }

  /**
   * This is when you would hide certain megaphones based on {@link FeatureFlags}. You could
   * conditionally set a {@link ForeverSchedule} set to false for disabled features.
   */
  private static Map<Event, MegaphoneSchedule> buildDisplayOrder(@NonNull Context context) {
    return new LinkedHashMap<Event, MegaphoneSchedule>() {{
      put(Event.REACTIONS, ALWAYS);
      put(Event.PINS_FOR_ALL, new PinsForAllSchedule());
      put(Event.PIN_REMINDER, new SignalPinReminderSchedule());
      put(Event.MESSAGE_REQUESTS, shouldShowMessageRequestsMegaphone() ? ALWAYS : NEVER);
      put(Event.MENTIONS, shouldShowMentionsMegaphone() ? ALWAYS : NEVER);
      put(Event.LINK_PREVIEWS, shouldShowLinkPreviewsMegaphone(context) ? ALWAYS : NEVER);
    }};
  }

  private static @NonNull Megaphone forRecord(@NonNull Context context, @NonNull MegaphoneRecord record) {
    switch (record.getEvent()) {
      case REACTIONS:
        return buildReactionsMegaphone();
      case PINS_FOR_ALL:
        return buildPinsForAllMegaphone(record);
      case PIN_REMINDER:
        return buildPinReminderMegaphone(context);
      case MESSAGE_REQUESTS:
        return buildMessageRequestsMegaphone(context);
      case MENTIONS:
        return buildMentionsMegaphone();
      case LINK_PREVIEWS:
        return buildLinkPreviewsMegaphone();
      default:
        throw new IllegalArgumentException("Event not handled!");
    }
  }

  private static @NonNull Megaphone buildReactionsMegaphone() {
    return new Megaphone.Builder(Event.REACTIONS, Megaphone.Style.REACTIONS)
                        .setMandatory(false)
                        .build();
  }

  private static @NonNull Megaphone buildPinsForAllMegaphone(@NonNull MegaphoneRecord record) {
    if (PinsForAllSchedule.shouldDisplayFullScreen(record.getFirstVisible(), System.currentTimeMillis())) {
      return new Megaphone.Builder(Event.PINS_FOR_ALL, Megaphone.Style.FULLSCREEN)
                          .setMandatory(true)
                          .enableSnooze(null)
                          .setOnVisibleListener((megaphone, listener) -> {
                            if (new NetworkConstraint.Factory(ApplicationDependencies.getApplication()).create().isMet()) {
                              listener.onMegaphoneNavigationRequested(KbsMigrationActivity.createIntent(), KbsMigrationActivity.REQUEST_NEW_PIN);
                            }
                          })
                          .build();
    } else {
      return new Megaphone.Builder(Event.PINS_FOR_ALL, Megaphone.Style.BASIC)
                          .setMandatory(true)
                          .setImage(R.drawable.kbs_pin_megaphone)
                          .setTitle(R.string.KbsMegaphone__create_a_pin)
                          .setBody(R.string.KbsMegaphone__pins_keep_information_thats_stored_with_signal_encrytped)
                          .setActionButton(R.string.KbsMegaphone__create_pin, (megaphone, listener) -> {
                            Intent intent = CreateKbsPinActivity.getIntentForPinCreate(ApplicationDependencies.getApplication());

                            listener.onMegaphoneNavigationRequested(intent, CreateKbsPinActivity.REQUEST_NEW_PIN);
                          })
                          .build();
    }
  }

  @SuppressWarnings("CodeBlock2Expr")
  private static @NonNull Megaphone buildPinReminderMegaphone(@NonNull Context context) {
    return new Megaphone.Builder(Event.PIN_REMINDER, Megaphone.Style.BASIC)
                        .setTitle(R.string.Megaphones_verify_your_signal_pin)
                        .setBody(R.string.Megaphones_well_occasionally_ask_you_to_verify_your_pin)
                        .setImage(R.drawable.kbs_pin_megaphone)
                        .setActionButton(R.string.Megaphones_verify_pin, (megaphone, controller) -> {
                          SignalPinReminderDialog.show(controller.getMegaphoneActivity(), controller::onMegaphoneNavigationRequested, new SignalPinReminderDialog.Callback() {
                            @Override
                            public void onReminderDismissed(boolean includedFailure) {
                              Log.i(TAG, "[PinReminder] onReminderDismissed(" + includedFailure + ")");
                              if (includedFailure) {
                                SignalStore.pinValues().onEntrySkipWithWrongGuess();
                              }
                            }

                            @Override
                            public void onReminderCompleted(@NonNull String pin, boolean includedFailure) {
                              Log.i(TAG, "[PinReminder] onReminderCompleted(" + includedFailure + ")");
                              if (includedFailure) {
                                SignalStore.pinValues().onEntrySuccessWithWrongGuess(pin);
                              } else {
                                SignalStore.pinValues().onEntrySuccess(pin);
                              }

                              controller.onMegaphoneSnooze(Event.PIN_REMINDER);
                              controller.onMegaphoneToastRequested(context.getString(SignalPinReminders.getReminderString(SignalStore.pinValues().getCurrentInterval())));
                            }
                          });
                        })
                        .build();
  }

  @SuppressWarnings("CodeBlock2Expr")
  private static @NonNull Megaphone buildMessageRequestsMegaphone(@NonNull Context context) {
    return new Megaphone.Builder(Event.MESSAGE_REQUESTS, Megaphone.Style.FULLSCREEN)
                        .disableSnooze()
                        .setMandatory(true)
                        .setOnVisibleListener(((megaphone, listener) -> {
                          listener.onMegaphoneNavigationRequested(new Intent(context, MessageRequestMegaphoneActivity.class),
                                                                  ConversationListFragment.MESSAGE_REQUESTS_REQUEST_CODE_CREATE_NAME);
                        }))
                        .build();
  }

  private static Megaphone buildMentionsMegaphone() {
    return new Megaphone.Builder(Event.MENTIONS, Megaphone.Style.POPUP)
                        .setTitle(R.string.MentionsMegaphone__introducing_mentions)
                        .setBody(R.string.MentionsMegaphone__get_someones_attention_in_a_group_by_typing)
                        .setImage(R.drawable.mention_megaphone)
                        .build();
  }

  private static @NonNull Megaphone buildLinkPreviewsMegaphone() {
    return new Megaphone.Builder(Event.LINK_PREVIEWS, Megaphone.Style.LINK_PREVIEWS)
                        .setMandatory(true)
                        .build();
  }

  private static boolean shouldShowMessageRequestsMegaphone() {
    return Recipient.self().getProfileName() == ProfileName.EMPTY;
  }

  private static boolean shouldShowMentionsMegaphone() {
    return FeatureFlags.mentions();
  }

  private static boolean shouldShowLinkPreviewsMegaphone(@NonNull Context context) {
    return TextSecurePreferences.wereLinkPreviewsEnabled(context) && !SignalStore.settings().isLinkPreviewsEnabled();
  }

  public enum Event {
    REACTIONS("reactions"),
    PINS_FOR_ALL("pins_for_all"),
    PIN_REMINDER("pin_reminder"),
    MESSAGE_REQUESTS("message_requests"),
    MENTIONS("mentions"),
    LINK_PREVIEWS("link_previews");

    private final String key;

    Event(@NonNull String key) {
      this.key = key;
    }

    public @NonNull String getKey() {
      return key;
    }

    public static Event fromKey(@NonNull String key) {
      for (Event event : values()) {
        if (event.getKey().equals(key)) {
          return event;
        }
      }
      throw new IllegalArgumentException("No event for key: " + key);
    }

    public static boolean hasKey(@NonNull String key) {
      for (Event event : values()) {
        if (event.getKey().equals(key)) {
          return true;
        }
      }
      return false;
    }
  }
}
