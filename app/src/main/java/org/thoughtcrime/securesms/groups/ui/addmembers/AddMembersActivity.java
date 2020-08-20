package org.thoughtcrime.securesms.groups.ui.addmembers;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.ViewModelProviders;

import org.thoughtcrime.securesms.ContactSelectionActivity;
import org.thoughtcrime.securesms.PushContactSelectionActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.groups.GroupId;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.recipients.RecipientId;
import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.libsignal.util.guava.Optional;

public class AddMembersActivity extends PushContactSelectionActivity {

  public static final String GROUP_ID = "group_id";

  private View                done;
  private AlertDialog         alert;
  private AddMembersViewModel viewModel;

  @Override
  protected void onCreate(Bundle icicle, boolean ready) {
    getIntent().putExtra(ContactSelectionActivity.EXTRA_LAYOUT_RES_ID, R.layout.add_members_activity);
    super.onCreate(icicle, ready);

    AddMembersViewModel.Factory factory = new AddMembersViewModel.Factory(getGroupId());

    done      = findViewById(R.id.done);
    alert     = buildConfirmationAlertDialog();
    viewModel = ViewModelProviders.of(this, factory)
                                  .get(AddMembersViewModel.class);

    viewModel.getAddMemberDialogState().observe(this, state -> AddMembersActivity.updateAlertMessage(alert, state));

    //noinspection CodeBlock2Expr
    done.setOnClickListener(v -> {
      viewModel.setDialogStateForSelectedContacts(contactsFragment.getSelectedContacts());
      alert.show();
    });

    disableDone();
  }

  @Override
  protected void initializeToolbar() {
    getToolbar().setNavigationIcon(R.drawable.ic_arrow_left_24);
    getToolbar().setNavigationOnClickListener(v -> {
      setResult(RESULT_CANCELED);
      finish();
    });
  }

  @Override
  public boolean onContactSelected(Optional<RecipientId> recipientId, String number) {
    if (getGroupId().isV1() && recipientId.isPresent() && !Recipient.resolved(recipientId.get()).hasE164()) {
      Toast.makeText(this, R.string.AddMembersActivity__this_person_cant_be_added_to_legacy_groups, Toast.LENGTH_SHORT).show();
      return false;
    }

    if (contactsFragment.hasQueryFilter()) {
      getToolbar().clear();
    }

    enableDone();

    return true;
  }

  @Override
  public void onContactDeselected(Optional<RecipientId> recipientId, String number) {
    if (contactsFragment.hasQueryFilter()) {
      getToolbar().clear();
    }

    if (contactsFragment.getSelectedContactsCount() < 1) {
      disableDone();
    }
  }

  private void enableDone() {
    done.setEnabled(true);
    done.animate().alpha(1f);
  }

  private void disableDone() {
    done.setEnabled(false);
    done.animate().alpha(0.5f);
  }

  private GroupId getGroupId() {
    return GroupId.parseOrThrow(getIntent().getStringExtra(GROUP_ID));
  }

  private AlertDialog buildConfirmationAlertDialog() {
    return new AlertDialog.Builder(this)
                          .setMessage(" ")
                          .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.cancel())
                          .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            dialog.dismiss();
                            onFinishedSelection();
                          })
                          .setCancelable(true)
                          .create();
  }

  private static void updateAlertMessage(@NonNull AlertDialog alertDialog, @NonNull AddMembersViewModel.AddMemberDialogMessageState state) {
    Context   context   = alertDialog.getContext();
    Recipient recipient = Util.firstNonNull(state.getRecipient(), Recipient.UNKNOWN);

    alertDialog.setMessage(context.getResources().getQuantityString(R.plurals.AddMembersActivity__add_d_members_to_s, state.getSelectionCount(),
                                                                    recipient.getDisplayName(context), state.getGroupTitle(), state.getSelectionCount()));
  }
}
