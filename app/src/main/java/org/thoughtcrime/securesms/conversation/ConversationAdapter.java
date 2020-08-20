/*
 * Copyright (C) 2011 Whisper Systems
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
package org.thoughtcrime.securesms.conversation;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.AnyThread;
import androidx.annotation.LayoutRes;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.paging.PagedList;
import androidx.paging.PagedListAdapter;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.BindableConversationItem;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.database.model.MessageRecord;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.mms.GlideRequests;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.util.CachedInflater;
import org.thoughtcrime.securesms.util.DateUtils;
import org.thoughtcrime.securesms.util.StickyHeaderDecoration;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.util.guava.Optional;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Adapter that renders a conversation.
 *
 * Important spacial thing to keep in mind: The adapter is intended to be shown on a reversed layout
 * manager, so position 0 is at the bottom of the screen. That's why the "header" is at the bottom,
 * the "footer" is at the top, and we refer to the "next" record as having a lower index.
 */
public class ConversationAdapter
    extends PagedListAdapter<ConversationMessage, RecyclerView.ViewHolder>
    implements StickyHeaderDecoration.StickyHeaderAdapter<ConversationAdapter.StickyHeaderViewHolder>
{

  private static final String TAG = Log.tag(ConversationAdapter.class);

  private static final int MESSAGE_TYPE_OUTGOING_MULTIMEDIA = 0;
  private static final int MESSAGE_TYPE_OUTGOING_TEXT       = 1;
  private static final int MESSAGE_TYPE_INCOMING_MULTIMEDIA = 2;
  private static final int MESSAGE_TYPE_INCOMING_TEXT       = 3;
  private static final int MESSAGE_TYPE_UPDATE              = 4;
  private static final int MESSAGE_TYPE_HEADER              = 5;
  private static final int MESSAGE_TYPE_FOOTER              = 6;
  private static final int MESSAGE_TYPE_PLACEHOLDER         = 7;

  private static final long HEADER_ID = Long.MIN_VALUE;
  private static final long FOOTER_ID = Long.MIN_VALUE + 1;

  private final ItemClickListener clickListener;
  private final GlideRequests     glideRequests;
  private final Locale            locale;
  private final Recipient         recipient;

  private final Set<ConversationMessage>  selected;
  private final List<ConversationMessage> fastRecords;
  private final Set<Long>                 releasedFastRecords;
  private final Calendar                  calendar;
  private final MessageDigest             digest;

  private String              searchQuery;
  private ConversationMessage recordToPulse;
  private View                headerView;
  private View                footerView;

  ConversationAdapter(@NonNull GlideRequests glideRequests,
                      @NonNull Locale locale,
                      @Nullable ItemClickListener clickListener,
                      @NonNull Recipient recipient)
  {
    super(new DiffCallback());

    this.glideRequests       = glideRequests;
    this.locale              = locale;
    this.clickListener       = clickListener;
    this.recipient           = recipient;
    this.selected            = new HashSet<>();
    this.fastRecords         = new ArrayList<>();
    this.releasedFastRecords = new HashSet<>();
    this.calendar            = Calendar.getInstance();
    this.digest              = getMessageDigestOrThrow();

    setHasStableIds(true);
  }

  @Override
  public int getItemViewType(int position) {
    if (hasHeader() && position == 0) {
      return MESSAGE_TYPE_HEADER;
    }

    if (hasFooter() && position == getItemCount() - 1) {
      return MESSAGE_TYPE_FOOTER;
    }

    ConversationMessage conversationMessage = getItem(position);
    MessageRecord       messageRecord       = (conversationMessage != null) ? conversationMessage.getMessageRecord() : null;

    if (messageRecord == null) {
      return MESSAGE_TYPE_PLACEHOLDER;
    } else if (messageRecord.isUpdate()) {
      return MESSAGE_TYPE_UPDATE;
    } else if (messageRecord.isOutgoing()) {
      return messageRecord.isMms() ? MESSAGE_TYPE_OUTGOING_MULTIMEDIA : MESSAGE_TYPE_OUTGOING_TEXT;
    } else {
      return messageRecord.isMms() ? MESSAGE_TYPE_INCOMING_MULTIMEDIA : MESSAGE_TYPE_INCOMING_TEXT;
    }
  }

  @Override
  public long getItemId(int position) {
    if (hasHeader() && position == 0) {
      return HEADER_ID;
    }

    if (hasFooter() && position == getItemCount() - 1) {
      return FOOTER_ID;
    }

    ConversationMessage message = getItem(position);

    if (message == null) {
      return -1;
    }

    return message.getUniqueId(digest);
  }

  @Override
  public @NonNull RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    switch (viewType) {
      case MESSAGE_TYPE_INCOMING_TEXT:
      case MESSAGE_TYPE_INCOMING_MULTIMEDIA:
      case MESSAGE_TYPE_OUTGOING_TEXT:
      case MESSAGE_TYPE_OUTGOING_MULTIMEDIA:
      case MESSAGE_TYPE_UPDATE:
        long start = System.currentTimeMillis();

        View                     itemView = CachedInflater.from(parent.getContext()).inflate(getLayoutForViewType(viewType), parent, false);
        BindableConversationItem bindable = (BindableConversationItem) itemView;

        itemView.setOnClickListener(view -> {
          if (clickListener != null) {
            clickListener.onItemClick(bindable.getConversationMessage());
          }
        });

        itemView.setOnLongClickListener(view -> {
          if (clickListener != null) {
            clickListener.onItemLongClick(itemView, bindable.getConversationMessage());
          }
          return true;
        });

        bindable.setEventListener(clickListener);

        Log.d(TAG, String.format(Locale.US, "Inflate time: %d ms for View type: %d", System.currentTimeMillis() - start, viewType));
        return new ConversationViewHolder(itemView);
      case MESSAGE_TYPE_PLACEHOLDER:
        View v = new FrameLayout(parent.getContext());
        v.setLayoutParams(new FrameLayout.LayoutParams(1, ViewUtil.dpToPx(100)));
        return new PlaceholderViewHolder(v);
      case MESSAGE_TYPE_HEADER:
      case MESSAGE_TYPE_FOOTER:
        return new HeaderFooterViewHolder(CachedInflater.from(parent.getContext()).inflate(R.layout.cursor_adapter_header_footer_view, parent, false));
      default:
        throw new IllegalStateException("Cannot create viewholder for type: " + viewType);
    }
  }

  @Override
  public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
    switch (getItemViewType(position)) {
      case MESSAGE_TYPE_INCOMING_TEXT:
      case MESSAGE_TYPE_INCOMING_MULTIMEDIA:
      case MESSAGE_TYPE_OUTGOING_TEXT:
      case MESSAGE_TYPE_OUTGOING_MULTIMEDIA:
      case MESSAGE_TYPE_UPDATE:
        ConversationViewHolder conversationViewHolder = (ConversationViewHolder) holder;
        ConversationMessage    conversationMessage    = Objects.requireNonNull(getItem(position));
        int                    adapterPosition        = holder.getAdapterPosition();

        ConversationMessage previousMessage = adapterPosition < getItemCount() - 1  && !isFooterPosition(adapterPosition + 1) ? getItem(adapterPosition + 1) : null;
        ConversationMessage nextMessage     = adapterPosition > 0                   && !isHeaderPosition(adapterPosition - 1) ? getItem(adapterPosition - 1) : null;

        conversationViewHolder.getBindable().bind(conversationMessage,
                                                  Optional.fromNullable(previousMessage != null ? previousMessage.getMessageRecord() : null),
                                                  Optional.fromNullable(nextMessage != null ? nextMessage.getMessageRecord() : null),
                                                  glideRequests,
                                                  locale,
                                                  selected,
                                                  recipient,
                                                  searchQuery,
                                                  conversationMessage == recordToPulse);

        if (conversationMessage == recordToPulse) {
          recordToPulse = null;
        }
        break;
      case MESSAGE_TYPE_HEADER:
        ((HeaderFooterViewHolder) holder).bind(headerView);
        break;
      case MESSAGE_TYPE_FOOTER:
        ((HeaderFooterViewHolder) holder).bind(footerView);
        break;
    }
  }

  @Override
  public void submitList(@Nullable PagedList<ConversationMessage> pagedList) {
    cleanFastRecords();
    super.submitList(pagedList);
  }

  @Override
  protected @Nullable ConversationMessage getItem(int position) {
    position = hasHeader() ? position - 1 : position;

    if (position == -1) {
      return null;
    } else if (position < fastRecords.size()) {
      return fastRecords.get(position);
    } else {
      int correctedPosition = position - fastRecords.size();
      return super.getItem(correctedPosition);
    }
  }

  @Override
  public int getItemCount() {
    boolean hasHeader = headerView != null;
    boolean hasFooter = footerView != null;
    return super.getItemCount() + fastRecords.size() + (hasHeader ? 1 : 0) + (hasFooter ? 1 : 0);
  }

  @Override
  public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
    if (holder instanceof ConversationViewHolder) {
      ((ConversationViewHolder) holder).getBindable().unbind();
    } else if (holder instanceof HeaderFooterViewHolder) {
      ((HeaderFooterViewHolder) holder).unbind();
    }
  }

  @Override
  public long getHeaderId(int position) {
    if (isHeaderPosition(position)) return -1;
    if (isFooterPosition(position)) return -1;
    if (position >= getItemCount()) return -1;
    if (position < 0)               return -1;

    ConversationMessage conversationMessage = getItem(position);

    if (conversationMessage == null) return -1;

    calendar.setTime(new Date(conversationMessage.getMessageRecord().getDateSent()));
    return Util.hashCode(calendar.get(Calendar.YEAR), calendar.get(Calendar.DAY_OF_YEAR));
  }

  @Override
  public StickyHeaderViewHolder onCreateHeaderViewHolder(ViewGroup parent, int position) {
    return new StickyHeaderViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.conversation_item_header, parent, false));
  }

  @Override
  public void onBindHeaderViewHolder(StickyHeaderViewHolder viewHolder, int position) {
    ConversationMessage conversationMessage = Objects.requireNonNull(getItem(position));
    viewHolder.setText(DateUtils.getRelativeDate(viewHolder.itemView.getContext(), locale, conversationMessage.getMessageRecord().getDateReceived()));
  }

  void onBindLastSeenViewHolder(StickyHeaderViewHolder viewHolder, int position) {
    viewHolder.setText(viewHolder.itemView.getContext().getResources().getQuantityString(R.plurals.ConversationAdapter_n_unread_messages, (position + 1), (position + 1)));
  }

  boolean hasNoConversationMessages() {
    return super.getItemCount() + fastRecords.size() == 0;
  }

  /**
   * The presence of a header may throw off the position you'd like to jump to. This will return
   * an adjusted message position based on adapter state.
   */
  @MainThread
  int getAdapterPositionForMessagePosition(int messagePosition) {
    return hasHeader() ? messagePosition + 1 : messagePosition;
  }

  /**
   * Finds the received timestamp for the item at the requested adapter position. Will return 0 if
   * the position doesn't refer to an incoming message.
   */
  @MainThread
  long getReceivedTimestamp(int position) {
    if (isHeaderPosition(position)) return 0;
    if (isFooterPosition(position)) return 0;
    if (position >= getItemCount()) return 0;
    if (position < 0)               return 0;

    ConversationMessage conversationMessage = getItem(position);

    if (conversationMessage == null || conversationMessage.getMessageRecord().isOutgoing()) {
      return 0;
    } else {
      return conversationMessage.getMessageRecord().getDateReceived();
    }
  }

  /**
   * Sets the view the appears at the top of the list (because the list is reversed).
   */
  void setFooterView(@Nullable View view) {
    boolean hadFooter = hasFooter();

    this.footerView = view;

    if (view == null && hadFooter) {
      notifyItemRemoved(getItemCount());
    } else if (view != null && hadFooter) {
      notifyItemChanged(getItemCount() - 1);
    } else if (view != null) {
      notifyItemInserted(getItemCount() - 1);
    }
  }

  /**
   * Sets the view that appears at the bottom of the list (because the list is reversed).
   */
  void setHeaderView(@Nullable View view) {
    boolean hadHeader = hasHeader();

    this.headerView = view;

    if (view == null && hadHeader) {
      notifyItemRemoved(0);
    } else if (view != null && hadHeader) {
      notifyItemChanged(0);
    } else if (view != null) {
      notifyItemInserted(0);
    }
  }

  /**
   * Returns the header view, if one was set.
   */
  @Nullable View getHeaderView() {
    return headerView;
  }

  /**
   * Momentarily highlights a mention at the requested position.
   */
  void pulseAtPosition(int position) {
    if (position >= 0 && position < getItemCount()) {
      int correctedPosition = isHeaderPosition(position) ? position + 1 : position;

      recordToPulse = getItem(correctedPosition);
      notifyItemChanged(correctedPosition);
    }
  }

  /**
   * Conversation search query updated. Allows rendering of text highlighting.
   */
  void onSearchQueryUpdated(String query) {
    this.searchQuery = query;
    notifyDataSetChanged();
  }

  /**
   * Adds a record to a memory cache to allow it to be rendered immediately, as opposed to waiting
   * for a database change.
   */
  @MainThread
  void addFastRecord(ConversationMessage conversationMessage) {
    fastRecords.add(0, conversationMessage);
    notifyDataSetChanged();
  }

  /**
   * Marks a record as no-longer-needed. Will be removed from the adapter the next time the database
   * changes.
   */
  @AnyThread
  void releaseFastRecord(long id) {
    synchronized (releasedFastRecords) {
      releasedFastRecords.add(id);
    }
  }

  /**
   * Returns set of records that are selected in multi-select mode.
   */
  Set<ConversationMessage> getSelectedItems() {
    return new HashSet<>(selected);
  }

  /**
   * Clears all selected records from multi-select mode.
   */
  void clearSelection() {
    selected.clear();
  }

  /**
   * Toggles the selected state of a record in multi-select mode.
   */
  void toggleSelection(ConversationMessage conversationMessage) {
    if (selected.contains(conversationMessage)) {
      selected.remove(conversationMessage);
    } else {
      selected.add(conversationMessage);
    }
  }

  /**
   * Provided a pool, this will initialize it with view counts that make sense.
   */
  @MainThread
  static void initializePool(@NonNull RecyclerView.RecycledViewPool pool) {
    pool.setMaxRecycledViews(MESSAGE_TYPE_INCOMING_TEXT, 15);
    pool.setMaxRecycledViews(MESSAGE_TYPE_INCOMING_MULTIMEDIA, 15);
    pool.setMaxRecycledViews(MESSAGE_TYPE_OUTGOING_TEXT, 15);
    pool.setMaxRecycledViews(MESSAGE_TYPE_OUTGOING_MULTIMEDIA, 15);
    pool.setMaxRecycledViews(MESSAGE_TYPE_PLACEHOLDER, 15);
    pool.setMaxRecycledViews(MESSAGE_TYPE_HEADER, 1);
    pool.setMaxRecycledViews(MESSAGE_TYPE_FOOTER, 1);
    pool.setMaxRecycledViews(MESSAGE_TYPE_UPDATE, 5);
  }

  @MainThread
  private void cleanFastRecords() {
    Util.assertMainThread();

    synchronized (releasedFastRecords) {
      Iterator<ConversationMessage> messageIterator = fastRecords.iterator();
      while (messageIterator.hasNext()) {
        long id = messageIterator.next().getMessageRecord().getId();
        if (releasedFastRecords.contains(id)) {
          messageIterator.remove();
          releasedFastRecords.remove(id);
        }
      }
    }
  }

  private boolean hasHeader() {
    return headerView != null;
  }

  public boolean hasFooter() {
    return footerView != null;
  }

  private boolean isHeaderPosition(int position) {
    return hasHeader() && position == 0;
  }

  private boolean isFooterPosition(int position) {
    return hasFooter() && position == (getItemCount() - 1);
  }

  private static @LayoutRes int getLayoutForViewType(int viewType) {
    switch (viewType) {
      case MESSAGE_TYPE_OUTGOING_TEXT:       return R.layout.conversation_item_sent_text_only;
      case MESSAGE_TYPE_OUTGOING_MULTIMEDIA: return R.layout.conversation_item_sent_multimedia;
      case MESSAGE_TYPE_INCOMING_TEXT:       return R.layout.conversation_item_received_text_only;
      case MESSAGE_TYPE_INCOMING_MULTIMEDIA: return R.layout.conversation_item_received_multimedia;
      case MESSAGE_TYPE_UPDATE:              return R.layout.conversation_item_update;
      default:                               throw new IllegalArgumentException("Unknown type!");
    }
  }

  private static MessageDigest getMessageDigestOrThrow() {
    try {
      return MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  public @Nullable ConversationMessage getLastVisibleConversationMessage(int position) {
    return getItem(position - ((hasFooter() && position == getItemCount() - 1) ? 1 : 0));
  }

  static class ConversationViewHolder extends RecyclerView.ViewHolder {
    public ConversationViewHolder(final @NonNull View itemView) {
      super(itemView);
    }

    public BindableConversationItem getBindable() {
      return (BindableConversationItem) itemView;
    }
  }

  static class StickyHeaderViewHolder extends RecyclerView.ViewHolder {
    TextView textView;

    StickyHeaderViewHolder(View itemView) {
      super(itemView);
      textView = itemView.findViewById(R.id.text);
    }

    StickyHeaderViewHolder(TextView textView) {
      super(textView);
      this.textView = textView;
    }

    public void setText(CharSequence text) {
      textView.setText(text);
    }
  }

  private static class HeaderFooterViewHolder extends RecyclerView.ViewHolder {

    private ViewGroup container;

    HeaderFooterViewHolder(@NonNull View itemView) {
      super(itemView);
      this.container = (ViewGroup) itemView;
    }

    void bind(@Nullable View view) {
      unbind();

      if (view != null) {
        container.addView(view);
      }
    }

    void unbind() {
      container.removeAllViews();
    }
  }

  private static class PlaceholderViewHolder extends RecyclerView.ViewHolder {
    PlaceholderViewHolder(@NonNull View itemView) {
      super(itemView);
    }
  }

  private static class DiffCallback extends DiffUtil.ItemCallback<ConversationMessage> {
    @Override
    public boolean areItemsTheSame(@NonNull ConversationMessage oldItem, @NonNull ConversationMessage newItem) {
      return oldItem.getMessageRecord().isMms() == newItem.getMessageRecord().isMms() && oldItem.getMessageRecord().getId() == newItem.getMessageRecord().getId();
    }

    @Override
    public boolean areContentsTheSame(@NonNull ConversationMessage oldItem, @NonNull ConversationMessage newItem) {
      // Corner rounding is not part of the model, so we can't use this yet
      return false;
    }
  }

  interface ItemClickListener extends BindableConversationItem.EventListener {
    void onItemClick(ConversationMessage item);
    void onItemLongClick(View maskTarget, ConversationMessage item);
  }
}
