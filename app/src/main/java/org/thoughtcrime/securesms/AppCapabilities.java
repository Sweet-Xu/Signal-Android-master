package org.thoughtcrime.securesms;

import org.thoughtcrime.securesms.util.FeatureFlags;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;

public final class AppCapabilities {

  private AppCapabilities() {
  }

  private static final boolean UUID_CAPABLE = false;

  /**
   * @param storageCapable Whether or not the user can use storage service. This is another way of
   *                       asking if the user has set a Signal PIN or not.
   */
  public static SignalServiceProfile.Capabilities getCapabilities(boolean storageCapable) {
    return new SignalServiceProfile.Capabilities(UUID_CAPABLE, FeatureFlags.groupsV2(), storageCapable);
  }
}
