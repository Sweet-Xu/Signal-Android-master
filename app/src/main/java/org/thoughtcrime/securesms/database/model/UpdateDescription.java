package org.thoughtcrime.securesms.database.model;

import androidx.annotation.AnyThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.thoughtcrime.securesms.util.Util;
import org.whispersystems.signalservice.api.util.UuidUtil;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contains a list of people mentioned in an update message and a function to create the update message.
 */
public final class UpdateDescription {

  public interface StringFactory {
    @WorkerThread
    String create();
  }

  private final Collection<UUID> mentioned;
  private final StringFactory    stringFactory;
  private final String           staticString;

  private UpdateDescription(@NonNull Collection<UUID> mentioned,
                            @Nullable StringFactory stringFactory,
                            @Nullable String staticString)
  {
    if (staticString == null && stringFactory == null) {
      throw new AssertionError();
    }
    this.mentioned     = mentioned;
    this.stringFactory = stringFactory;
    this.staticString  = staticString;
  }

  /**
   * Create an update description which has a string value created by a supplied factory method that
   * will be run on a background thread.
   *
   * @param mentioned     UUIDs of recipients that are mentioned in the string.
   * @param stringFactory The background method for generating the string.
   */
  public static UpdateDescription mentioning(@NonNull Collection<UUID> mentioned,
                                             @NonNull StringFactory stringFactory)
  {
    return new UpdateDescription(UuidUtil.filterKnown(mentioned),
                                 stringFactory,
                                 null);
  }

  /**
   * Create an update description that's string value is fixed.
   */
  public static UpdateDescription staticDescription(@NonNull String staticString) {
    return new UpdateDescription(Collections.emptyList(), null, staticString);
  }

  public boolean isStringStatic() {
    return staticString != null;
  }

  @AnyThread
  public @NonNull String getStaticString() {
    if (staticString == null) {
      throw new UnsupportedOperationException();
    }

    return staticString;
  }

  @WorkerThread
  public @NonNull String getString() {
    if (staticString != null) {
      return staticString;
    }

    Util.assertNotMainThread();

    //noinspection ConstantConditions
    return stringFactory.create();
  }

  @AnyThread
  public Collection<UUID> getMentioned() {
    return mentioned;
  }

  public static UpdateDescription concatWithNewLines(@NonNull List<UpdateDescription> updateDescriptions) {
    if (updateDescriptions.size() == 0) {
      throw new AssertionError();
    }

    if (updateDescriptions.size() == 1) {
      return updateDescriptions.get(0);
    }

    if (allAreStatic(updateDescriptions)) {
      return UpdateDescription.staticDescription(concatStaticLines(updateDescriptions));
    }

    Set<UUID> allMentioned = new HashSet<>();

    for (UpdateDescription updateDescription : updateDescriptions) {
      allMentioned.addAll(updateDescription.getMentioned());
    }

    return UpdateDescription.mentioning(allMentioned, () -> concatLines(updateDescriptions));
  }

  private static boolean allAreStatic(@NonNull Collection<UpdateDescription> updateDescriptions) {
    for (UpdateDescription description : updateDescriptions) {
      if (!description.isStringStatic()) {
        return false;
      }
    }

    return true;
  }

  @WorkerThread
  private static String concatLines(@NonNull List<UpdateDescription> updateDescriptions) {
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < updateDescriptions.size(); i++) {
      if (i > 0) result.append('\n');
      result.append(updateDescriptions.get(i).getString());
    }

    return result.toString();
  }

  @AnyThread
  private static String concatStaticLines(@NonNull List<UpdateDescription> updateDescriptions) {
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < updateDescriptions.size(); i++) {
      if (i > 0) result.append('\n');
      result.append(updateDescriptions.get(i).getStaticString());
    }

    return result.toString();
  }
}
