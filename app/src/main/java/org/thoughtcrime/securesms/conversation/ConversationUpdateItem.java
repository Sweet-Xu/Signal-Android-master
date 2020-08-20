package org.thoughtcrime.securesms.conversation;

import android.content.Context;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.SpannableString;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;

import org.thoughtcrime.securesms.BindableConversationItem;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.VerifyIdentityActivity;
import org.thoughtcrime.securesms.database.IdentityDatabase.IdentityRecord;
import org.thoughtcrime.securesms.database.model.LiveUpdateMessage;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.database.model.UpdateDescription;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.LiveRecipient;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientForeverObserver;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.ExpirationUtil;
import org.thoughtcrime.securesms.util.IdentityUtil;
import org.thoughtcrime.securesms.util.ThemeUtil;
import org.thoughtcrime.securesms.util.concurrent.ListenableFuture;
import org.whispersystems.libsignal.util.guava.Optional;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;

public final class ConversationUpdateItem extends LinearLayout
                                          implements RecipientForeverObserver,
                                                     BindableConversationItem,
                                                     Observer<SpannableString>
{
  private static final String TAG = ConversationUpdateItem.class.getSimpleName();

  private Set<ConversationMessage> batchSelected;

  private ImageView                 icon;
  private TextView                  title;
  private TextView                  body;
  private TextView                  date;
  private LiveRecipient             sender;
  private ConversationMessage       conversationMessage;
  private MessageRecord             messageRecord;
  private Locale                    locale;
  private LiveData<SpannableString> displayBody;

  private final Debouncer bodyClearDebouncer = new Debouncer(150);

  public ConversationUpdateItem(Context context) {
    super(context);
  }

  public ConversationUpdateItem(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  @Override
  public void onFinishInflate() {
    super.onFinishInflate();

    this.icon  = findViewById(R.id.conversation_update_icon);
    this.title = findViewById(R.id.conversation_update_title);
    this.body  = findViewById(R.id.conversation_update_body);
    this.date  = findViewById(R.id.conversation_update_date);

    this.setOnClickListener(new InternalClickListener(null));
  }

  @Override
  public void bind(@NonNull ConversationMessage conversationMessage,
                   @NonNull Optional<MessageRecord> previousMessageRecord,
                   @NonNull Optional<MessageRecord> nextMessageRecord,
                   @NonNull GlideRequests glideRequests,
                   @NonNull Locale locale,
                   @NonNull Set<ConversationMessage> batchSelected,
                   @NonNull Recipient conversationRecipient,
                   @Nullable String searchQuery,
                   boolean pulseMention)
  {
    this.batchSelected = batchSelected;

    bind(conversationMessage, locale);
  }

  @Override
  protected void onDetachedFromWindow() {
    unbind();
    super.onDetachedFromWindow();
  }

  @Override
  public void setEventListener(@Nullable EventListener listener) {
    // No events to report yet
  }

  @Override
  public ConversationMessage getConversationMessage() {
    return conversationMessage;
  }

  private void bind(@NonNull ConversationMessage conversationMessage, @NonNull Locale locale) {
    if (this.sender != null) {
      this.sender.removeForeverObserver(this);
    }

    observeDisplayBody(null);
    setBodyText(null);

    this.conversationMessage = conversationMessage;
    this.messageRecord       = conversationMessage.getMessageRecord();
    this.sender              = messageRecord.getIndividualRecipient().live();
    this.locale              = locale;

    this.sender.observeForever(this);

    UpdateDescription         updateDescription      = Objects.requireNonNull(messageRecord.getUpdateDisplayBody(getContext()));
    LiveData<String>          liveUpdateMessage      = LiveUpdateMessage.fromMessageDescription(updateDescription);
    LiveData<SpannableString> spannableStringMessage = Transformations.map(liveUpdateMessage, SpannableString::new);

    present(conversationMessage);

    observeDisplayBody(spannableStringMessage);
  }

  private void observeDisplayBody(@Nullable LiveData<SpannableString> displayBody) {
    if (this.displayBody != displayBody) {
      if (this.displayBody != null) {
        this.displayBody.removeObserver(this);
      }

      this.displayBody = displayBody;

      if (this.displayBody != null) {
        this.displayBody.observeForever(this);
      }
    }
  }

  private void setBodyText(@Nullable CharSequence text) {
    if (text == null) {
      bodyClearDebouncer.publish(() -> body.setText(null));
    } else {
      bodyClearDebouncer.clear();
      body.setText(text);
      body.setVisibility(VISIBLE);
    }
  }

  private void present(ConversationMessage conversationMessage) {
    MessageRecord messageRecord = conversationMessage.getMessageRecord();
    if      (messageRecord.isGroupAction())           setGroupRecord();
    else if (messageRecord.isCallLog())               setCallRecord(messageRecord);
    else if (messageRecord.isJoined())                setJoinedRecord();
    else if (messageRecord.isExpirationTimerUpdate()) setTimerRecord(messageRecord);
    else if (messageRecord.isEndSession())            setEndSessionRecord();
    else if (messageRecord.isIdentityUpdate())        setIdentityRecord();
    else if (messageRecord.isIdentityVerified() ||
             messageRecord.isIdentityDefault())       setIdentityVerifyUpdate(messageRecord);
    else if (messageRecord.isProfileChange())         setProfileNameChangeRecord();
    else                                              throw new AssertionError("Neither group nor log nor joined.");

    if (batchSelected.contains(conversationMessage)) setSelected(true);
    else                                             setSelected(false);
  }

  private void setCallRecord(MessageRecord messageRecord) {
    if      (messageRecord.isIncomingCall()) icon.setImageResource(R.drawable.ic_call_received_grey600_24dp);
    else if (messageRecord.isOutgoingCall()) icon.setImageResource(R.drawable.ic_call_made_grey600_24dp);
    else                                     icon.setImageResource(R.drawable.ic_call_missed_grey600_24dp);

    date.setText(DateUtils.getExtendedRelativeTimeSpanString(getContext(), locale, messageRecord.getDateReceived()));

    title.setVisibility(GONE);
    date.setVisibility(View.VISIBLE);
  }

  private void setTimerRecord(final MessageRecord messageRecord) {
    if (messageRecord.getExpiresIn() > 0) {
      icon.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_timer_24));
    } else {
      icon.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_timer_disabled_24));
    }

    icon.setColorFilter(getIconTintFilter());
    title.setText(ExpirationUtil.getExpirationDisplayValue(getContext(), (int)(messageRecord.getExpiresIn() / 1000)));

    title.setVisibility(VISIBLE);
    date.setVisibility(GONE);
  }

  private ColorFilter getIconTintFilter() {
    return new PorterDuffColorFilter(ThemeUtil.getThemedColor(getContext(), R.attr.icon_tint), PorterDuff.Mode.SRC_IN);
  }

  private void setIdentityRecord() {
    icon.setImageDrawable(ThemeUtil.getThemedDrawable(getContext(), R.attr.safety_number_icon));
    icon.setColorFilter(getIconTintFilter());

    title.setVisibility(GONE);
    date.setVisibility(GONE);
  }

  private void setIdentityVerifyUpdate(final MessageRecord messageRecord) {
    if (messageRecord.isIdentityVerified()) icon.setImageResource(R.drawable.ic_check_white_24dp);
    else                                    icon.setImageResource(R.drawable.ic_info_outline_white_24);

    icon.setColorFilter(getIconTintFilter());

    title.setVisibility(GONE);
    date.setVisibility(GONE);
  }

  private void setProfileNameChangeRecord() {
    icon.setImageDrawable(ContextCompat.getDrawable(getContext(), R.drawable.ic_profile_outline_20));
    icon.setColorFilter(getIconTintFilter());

    title.setVisibility(GONE);
    date.setVisibility(GONE);
  }

  private void setGroupRecord() {
    icon.setImageDrawable(ThemeUtil.getThemedDrawable(getContext(), R.attr.menu_group_icon));
    icon.clearColorFilter();

    title.setVisibility(GONE);
    date.setVisibility(GONE);
  }

  private void setJoinedRecord() {
    icon.setImageResource(R.drawable.ic_favorite_grey600_24dp);
    icon.clearColorFilter();

    title.setVisibility(GONE);
    date.setVisibility(GONE);
  }

  private void setEndSessionRecord() {
    icon.setImageResource(R.drawable.ic_refresh_white_24dp);
    icon.setColorFilter(getIconTintFilter());
  }

  @Override
  public void onRecipientChanged(@NonNull Recipient recipient) {
    present(conversationMessage);
  }

  @Override
  public void setOnClickListener(View.OnClickListener l) {
    super.setOnClickListener(new InternalClickListener(l));
  }

  @Override
  public void unbind() {
    if (sender != null) {
      sender.removeForeverObserver(this);
    }

    observeDisplayBody(null);
  }

  @Override
  public void onChanged(SpannableString update) {
    setBodyText(update);
  }

  private class InternalClickListener implements View.OnClickListener {

    @Nullable private final View.OnClickListener parent;

    InternalClickListener(@Nullable View.OnClickListener parent) {
      this.parent = parent;
    }

    @Override
    public void onClick(View v) {
      if ((!messageRecord.isIdentityUpdate()  &&
           !messageRecord.isIdentityDefault() &&
           !messageRecord.isIdentityVerified()) ||
          !batchSelected.isEmpty())
      {
        if (parent != null) parent.onClick(v);
        return;
      }

      final Recipient sender = ConversationUpdateItem.this.sender.get();

      IdentityUtil.getRemoteIdentityKey(getContext(), sender).addListener(new ListenableFuture.Listener<Optional<IdentityRecord>>() {
        @Override
        public void onSuccess(Optional<IdentityRecord> result) {
          if (result.isPresent()) {
            getContext().startActivity(VerifyIdentityActivity.newIntent(getContext(), result.get()));
          }
        }

        @Override
        public void onFailure(ExecutionException e) {
          Log.w(TAG, e);
        }
      });
    }
  }
}
