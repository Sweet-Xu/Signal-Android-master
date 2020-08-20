package org.thoughtcrime.securesms.messagerequests;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Group;
import androidx.core.text.HtmlCompat;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.Debouncer;
import org.thoughtcrime.securesms.util.HtmlUtil;

public class MessageRequestsBottomView extends ConstraintLayout {

  private final Debouncer showProgressDebouncer = new Debouncer(250);

  private TextView question;
  private View     accept;
  private View     block;
  private View     delete;
  private View     bigDelete;
  private View     bigUnblock;
  private View     busyIndicator;

  private Group normalButtons;
  private Group blockedButtons;
  private Group activeGroup;

  public MessageRequestsBottomView(Context context) {
    super(context);
  }

  public MessageRequestsBottomView(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  public MessageRequestsBottomView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
  }

  @Override
  protected void onFinishInflate() {
    super.onFinishInflate();

    inflate(getContext(), R.layout.message_request_bottom_bar, this);

    question       = findViewById(R.id.message_request_question);
    accept         = findViewById(R.id.message_request_accept);
    block          = findViewById(R.id.message_request_block);
    delete         = findViewById(R.id.message_request_delete);
    bigDelete      = findViewById(R.id.message_request_big_delete);
    bigUnblock     = findViewById(R.id.message_request_big_unblock);
    normalButtons  = findViewById(R.id.message_request_normal_buttons);
    blockedButtons = findViewById(R.id.message_request_blocked_buttons);
    busyIndicator  = findViewById(R.id.message_request_busy_indicator);
  }

  public void setRecipient(@NonNull Recipient recipient) {
    if (recipient.isBlocked()) {
      if (recipient.isGroup()) {
        question.setText(R.string.MessageRequestBottomView_unblock_this_group_and_share_your_name_and_photo_with_its_members);
      } else {
        String name = recipient.getShortDisplayName(getContext());
        question.setText(HtmlCompat.fromHtml(getContext().getString(R.string.MessageRequestBottomView_do_you_want_to_let_s_message_you_wont_receive_any_messages_until_you_unblock_them, HtmlUtil.bold(name)), 0));
      }
      setActiveInactiveGroups(blockedButtons, normalButtons);
    } else {
      if (recipient.isGroup()) {
        question.setText(R.string.MessageRequestBottomView_do_you_want_to_join_this_group_they_wont_know_youve_seen_their_messages_until_you_accept);
      } else {
        String name = recipient.getShortDisplayName(getContext());
        question.setText(HtmlCompat.fromHtml(getContext().getString(R.string.MessageRequestBottomView_do_you_want_to_let_s_message_you_they_wont_know_youve_seen_their_messages_until_you_accept, HtmlUtil.bold(name)), 0));
      }
      setActiveInactiveGroups(normalButtons, blockedButtons);
    }
  }

  private void setActiveInactiveGroups(@NonNull Group activeGroup, @NonNull Group inActiveGroup) {
    int initialVisibility = this.activeGroup != null ? this.activeGroup.getVisibility() : VISIBLE;

    this.activeGroup = activeGroup;

    inActiveGroup.setVisibility(GONE);
    activeGroup.setVisibility(initialVisibility);
  }

  public void showBusy() {
    showProgressDebouncer.publish(() -> busyIndicator.setVisibility(VISIBLE));
    if (activeGroup != null) {
      activeGroup.setVisibility(INVISIBLE);
    }
  }

  public void hideBusy() {
    showProgressDebouncer.clear();
    busyIndicator.setVisibility(GONE);
    if (activeGroup != null) {
      activeGroup.setVisibility(VISIBLE);
    }
  }

  public void setAcceptOnClickListener(OnClickListener acceptOnClickListener) {
    accept.setOnClickListener(acceptOnClickListener);
  }

  public void setDeleteOnClickListener(OnClickListener deleteOnClickListener) {
    delete.setOnClickListener(deleteOnClickListener);
    bigDelete.setOnClickListener(deleteOnClickListener);
  }

  public void setBlockOnClickListener(OnClickListener blockOnClickListener) {
    block.setOnClickListener(blockOnClickListener);
  }

  public void setUnblockOnClickListener(OnClickListener unblockOnClickListener) {
    bigUnblock.setOnClickListener(unblockOnClickListener);
  }
}
