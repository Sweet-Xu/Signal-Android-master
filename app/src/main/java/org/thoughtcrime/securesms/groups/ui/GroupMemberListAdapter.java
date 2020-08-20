package org.thoughtcrime.securesms.groups.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.AvatarImageView;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.LifecycleRecyclerAdapter;
import org.thoughtcrime.securesms.util.LifecycleViewHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class GroupMemberListAdapter extends LifecycleRecyclerAdapter<GroupMemberListAdapter.ViewHolder> {

  private static final int FULL_MEMBER                = 0;
  private static final int OWN_INVITE_PENDING         = 1;
  private static final int OTHER_INVITE_PENDING_COUNT = 2;
  private static final int NEW_GROUP_CANDIDATE        = 3;

  private final List<GroupMemberEntry>  data                    = new ArrayList<>();
  private final Set<GroupMemberEntry>   selection               = new HashSet<>();
  private final SelectionChangeListener selectionChangeListener = new SelectionChangeListener();

  private final boolean                 selectable;

  @Nullable private AdminActionsListener             adminActionsListener;
  @Nullable private RecipientClickListener           recipientClickListener;
  @Nullable private RecipientLongClickListener       recipientLongClickListener;
  @Nullable private RecipientSelectionChangeListener recipientSelectionChangeListener;

  GroupMemberListAdapter(boolean selectable) {
    this.selectable = selectable;
  }

  void updateData(@NonNull List<? extends GroupMemberEntry> recipients) {
    if (data.isEmpty()) {
      data.addAll(recipients);
      notifyDataSetChanged();
    } else {
      DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffCallback(data, recipients));
      data.clear();
      data.addAll(recipients);

      if (!selection.isEmpty()) {
        Set<GroupMemberEntry> newSelection = new HashSet<>();
        for (GroupMemberEntry entry : recipients) {
          if (selection.contains(entry)) {
            newSelection.add(entry);
          }
        }
        selection.clear();
        selection.addAll(newSelection);
        if (recipientSelectionChangeListener != null) {
          recipientSelectionChangeListener.onSelectionChanged(selection);
        }
      }

      diffResult.dispatchUpdatesTo(this);
    }
  }

  @Override
  public @NonNull ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    switch (viewType) {
      case FULL_MEMBER:
        return new FullMemberViewHolder(LayoutInflater.from(parent.getContext())
                                                      .inflate(R.layout.group_recipient_list_item, parent, false),
                                        recipientClickListener,
                                        recipientLongClickListener,
                                        adminActionsListener,
                                        selectionChangeListener);
      case OWN_INVITE_PENDING:
        return new OwnInvitePendingMemberViewHolder(LayoutInflater.from(parent.getContext())
                                                                  .inflate(R.layout.group_recipient_list_item, parent, false),
                                                    recipientClickListener,
                                                    recipientLongClickListener,
                                                    adminActionsListener,
                                                    selectionChangeListener);
      case OTHER_INVITE_PENDING_COUNT:
        return new UnknownPendingMemberCountViewHolder(LayoutInflater.from(parent.getContext())
                                                                     .inflate(R.layout.group_recipient_list_item, parent, false),
                                                       adminActionsListener,
                                                       selectionChangeListener);
      case NEW_GROUP_CANDIDATE:
        return new NewGroupInviteeViewHolder(LayoutInflater.from(parent.getContext())
                                                           .inflate(R.layout.group_new_candidate_recipient_list_item, parent, false),
                                             recipientClickListener,
                                             recipientLongClickListener,
                                             selectionChangeListener);
      default:
        throw new AssertionError();
    }
  }

  void setAdminActionsListener(@Nullable AdminActionsListener adminActionsListener) {
    this.adminActionsListener = adminActionsListener;
  }

  void setRecipientClickListener(@Nullable RecipientClickListener recipientClickListener) {
    this.recipientClickListener = recipientClickListener;
  }

  void setRecipientLongClickListener(@Nullable RecipientLongClickListener recipientLongClickListener) {
    this.recipientLongClickListener = recipientLongClickListener;
  }

  void setRecipientSelectionChangeListener(@Nullable RecipientSelectionChangeListener recipientSelectionChangeListener) {
    this.recipientSelectionChangeListener = recipientSelectionChangeListener;
  }

  @Override
  public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
    GroupMemberEntry entry = data.get(position);
    holder.bind(entry, selection.contains(entry));
  }

  @Override
  public int getItemViewType(int position) {
    GroupMemberEntry groupMemberEntry = data.get(position);

    if (groupMemberEntry instanceof GroupMemberEntry.FullMember) {
      return FULL_MEMBER;
    } else if (groupMemberEntry instanceof GroupMemberEntry.PendingMember) {
      return OWN_INVITE_PENDING;
    } else if (groupMemberEntry instanceof GroupMemberEntry.UnknownPendingMemberCount) {
      return OTHER_INVITE_PENDING_COUNT;
    } else if (groupMemberEntry instanceof GroupMemberEntry.NewGroupCandidate) {
      return NEW_GROUP_CANDIDATE;
    }

    throw new AssertionError();
  }

  @Override
  public int getItemCount() {
    return data.size();
  }

  static abstract class ViewHolder extends LifecycleViewHolder {

              final Context                    context;
              final AvatarImageView            avatar;
              final TextView                   recipient;
              final CheckBox                   selected;
              final PopupMenuView              popupMenu;
              final View                       popupMenuContainer;
              final ProgressBar                busyProgress;
              final View                       admin;
              final SelectionChangeListener    selectionChangeListener;
    @Nullable final RecipientClickListener     recipientClickListener;
    @Nullable final AdminActionsListener       adminActionsListener;
    @Nullable final RecipientLongClickListener recipientLongClickListener;

    ViewHolder(@NonNull View itemView,
               @Nullable RecipientClickListener recipientClickListener,
               @Nullable RecipientLongClickListener recipientLongClickListener,
               @Nullable AdminActionsListener adminActionsListener,
               @NonNull SelectionChangeListener selectionChangeListener)
    {
      super(itemView);

      this.context                    = itemView.getContext();
      this.avatar                     = itemView.findViewById(R.id.recipient_avatar);
      this.recipient                  = itemView.findViewById(R.id.recipient_name);
      this.selected                   = itemView.findViewById(R.id.recipient_selected);
      this.popupMenu                  = itemView.findViewById(R.id.popupMenu);
      this.popupMenuContainer         = itemView.findViewById(R.id.popupMenuProgressContainer);
      this.busyProgress               = itemView.findViewById(R.id.menuBusyProgress);
      this.admin                      = itemView.findViewById(R.id.admin);
      this.recipientClickListener     = recipientClickListener;
      this.recipientLongClickListener = recipientLongClickListener;
      this.adminActionsListener       = adminActionsListener;
      this.selectionChangeListener    = selectionChangeListener;
    }

    void bindRecipient(@NonNull Recipient recipient) {
      String displayName = recipient.isLocalNumber() ? context.getString(R.string.GroupMembersDialog_you)
                                                     : recipient.getDisplayName(itemView.getContext());
      bindImageAndText(recipient, displayName);
    }

    void bindImageAndText(@NonNull Recipient recipient, @NonNull String displayText) {
      this.recipient.setText(displayText);
      this.avatar.setRecipient(recipient);
    }

    void bindRecipientClick(@NonNull Recipient recipient) {
      if (recipient.equals(Recipient.self())) {
        this.itemView.setEnabled(false);
        return;
      }

      this.itemView.setEnabled(true);
      this.itemView.setOnClickListener(v -> {
        if (getAdapterPosition() != RecyclerView.NO_POSITION) {
          if (recipientClickListener != null) {
            recipientClickListener.onClick(recipient);
          }
          if (selected != null) {
            selectionChangeListener.onSelectionChange(getAdapterPosition(), !selected.isChecked());
          }
        }
      });
      this.itemView.setOnLongClickListener(v -> {
        if (recipientLongClickListener != null && getAdapterPosition() != RecyclerView.NO_POSITION) {
          return recipientLongClickListener.onLongClick(recipient);
        }
        return false;
      });
    }

    void bind(@NonNull GroupMemberEntry memberEntry, boolean isSelected) {
      busyProgress.setVisibility(View.GONE);
      admin.setVisibility(View.GONE);
      hideMenu();

      itemView.setOnClickListener(null);

      memberEntry.getBusy().observe(this, busy -> {
        busyProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        popupMenu.setVisibility(busy ? View.GONE : View.VISIBLE);
      });

      selected.setChecked(isSelected);
    }

    void hideMenu() {
      popupMenuContainer.setVisibility(View.GONE);
      popupMenu.setVisibility(View.GONE);
    }

    void showMenu() {
      popupMenuContainer.setVisibility(View.VISIBLE);
      popupMenu.setVisibility(View.VISIBLE);
    }
  }

  final static class FullMemberViewHolder extends ViewHolder {

    FullMemberViewHolder(@NonNull View itemView,
                         @Nullable RecipientClickListener recipientClickListener,
                         @Nullable RecipientLongClickListener recipientLongClickListener,
                         @Nullable AdminActionsListener adminActionsListener,
                         @NonNull SelectionChangeListener selectionChangeListener)
    {
      super(itemView, recipientClickListener, recipientLongClickListener, adminActionsListener, selectionChangeListener);
    }

    @Override
    void bind(@NonNull GroupMemberEntry memberEntry, boolean isSelected) {
      super.bind(memberEntry, isSelected);

      GroupMemberEntry.FullMember fullMember = (GroupMemberEntry.FullMember) memberEntry;

      bindRecipient(fullMember.getMember());
      bindRecipientClick(fullMember.getMember());
      admin.setVisibility(fullMember.isAdmin() ? View.VISIBLE : View.INVISIBLE);
    }
  }
  final static class NewGroupInviteeViewHolder extends ViewHolder {

    private final View smsContact;
    private final View smsWarning;

    NewGroupInviteeViewHolder(@NonNull View itemView,
                              @Nullable RecipientClickListener recipientClickListener,
                              @Nullable RecipientLongClickListener recipientLongClickListener,
                              @NonNull SelectionChangeListener selectionChangeListener)
    {
      super(itemView, recipientClickListener, recipientLongClickListener, null, selectionChangeListener);

      smsContact = itemView.findViewById(R.id.sms_contact);
      smsWarning = itemView.findViewById(R.id.sms_warning);
    }

    @Override
    void bind(@NonNull GroupMemberEntry memberEntry, boolean isSelected) {
      GroupMemberEntry.NewGroupCandidate newGroupCandidate = (GroupMemberEntry.NewGroupCandidate) memberEntry;

      bindRecipient(newGroupCandidate.getMember());
      bindRecipientClick(newGroupCandidate.getMember());

      int smsWarningVisibility = newGroupCandidate.getMember().isRegistered() ? View.GONE : View.VISIBLE;

      smsContact.setVisibility(smsWarningVisibility);
      smsWarning.setVisibility(smsWarningVisibility);
    }
  }

  final static class OwnInvitePendingMemberViewHolder extends ViewHolder {

    OwnInvitePendingMemberViewHolder(@NonNull View itemView,
                                     @Nullable RecipientClickListener recipientClickListener,
                                     @Nullable RecipientLongClickListener recipientLongClickListener,
                                     @Nullable AdminActionsListener adminActionsListener,
                                     @NonNull SelectionChangeListener selectionChangeListener)
    {
      super(itemView, recipientClickListener, recipientLongClickListener, adminActionsListener, selectionChangeListener);
    }

    @Override
    void bind(@NonNull GroupMemberEntry memberEntry, boolean isSelected) {
      super.bind(memberEntry, isSelected);

      GroupMemberEntry.PendingMember pendingMember = (GroupMemberEntry.PendingMember) memberEntry;

      bindRecipient(pendingMember.getInvitee());
      bindRecipientClick(pendingMember.getInvitee());

      if (pendingMember.isCancellable() && adminActionsListener != null) {
        popupMenu.setMenu(R.menu.own_invite_pending_menu,
                          item -> {
                            if (item == R.id.revoke_invite) {
                              adminActionsListener.onRevokeInvite(pendingMember);
                              return true;
                            }
                            return false;
                          });
        showMenu();
      }
    }
  }

  final static class UnknownPendingMemberCountViewHolder extends ViewHolder {

    UnknownPendingMemberCountViewHolder(@NonNull View itemView,
                                        @Nullable AdminActionsListener adminActionsListener,
                                        @NonNull SelectionChangeListener selectionChangeListener)
    {
      super(itemView, null, null, adminActionsListener, selectionChangeListener);
    }

    @Override
    void bind(@NonNull GroupMemberEntry memberEntry, boolean isSelected) {
      super.bind(memberEntry, isSelected);
      GroupMemberEntry.UnknownPendingMemberCount pendingMembers = (GroupMemberEntry.UnknownPendingMemberCount) memberEntry;

      Recipient inviter     = pendingMembers.getInviter();
      String    displayName = inviter.getDisplayName(itemView.getContext());
      String    displayText = context.getResources().getQuantityString(R.plurals.GroupMemberList_invited,
                                                                       pendingMembers.getInviteCount(),
                                                                       displayName, pendingMembers.getInviteCount());

      bindImageAndText(inviter, displayText);

      if (pendingMembers.isCancellable() && adminActionsListener != null) {
        popupMenu.setMenu(R.menu.others_invite_pending_menu,
                          item -> {
                            if (item.getItemId() == R.id.revoke_invites) {
                              item.setTitle(context.getResources().getQuantityString(R.plurals.PendingMembersActivity_revoke_d_invites, pendingMembers.getInviteCount(),
                                                                                     pendingMembers.getInviteCount()));
                              return true;
                            }
                            return true;
                          },
                          item -> {
                            if (item == R.id.revoke_invites) {
                              adminActionsListener.onRevokeAllInvites(pendingMembers);
                              return true;
                            }
                            return false;
                          });
        showMenu();
      }
    }
  }

  private final class SelectionChangeListener {
    void onSelectionChange(int position, boolean isChecked) {
      if (selectable) {
        if (isChecked) {
          selection.add(data.get(position));
        } else {
          selection.remove(data.get(position));
        }
        notifyItemChanged(position);

        if (recipientSelectionChangeListener != null) {
          recipientSelectionChangeListener.onSelectionChanged(selection);
        }
      }
    }
  }

  private final static class DiffCallback extends DiffUtil.Callback {
    private final List<? extends GroupMemberEntry> oldData;
    private final List<? extends GroupMemberEntry> newData;

    DiffCallback(List<? extends GroupMemberEntry> oldData, List<? extends GroupMemberEntry> newData) {
      this.oldData = oldData;
      this.newData = newData;
    }

    @Override
    public int getOldListSize() {
      return oldData.size();
    }

    @Override
    public int getNewListSize() {
      return newData.size();
    }

    @Override
    public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
      GroupMemberEntry oldItem = oldData.get(oldItemPosition);
      GroupMemberEntry newItem = newData.get(newItemPosition);

      return oldItem.sameId(newItem);
    }

    @Override
    public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
      GroupMemberEntry oldItem = oldData.get(oldItemPosition);
      GroupMemberEntry newItem = newData.get(newItemPosition);

      return oldItem.equals(newItem);
    }
  }
}
