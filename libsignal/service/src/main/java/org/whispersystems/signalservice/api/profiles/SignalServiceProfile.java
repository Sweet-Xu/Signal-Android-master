package org.whispersystems.signalservice.api.profiles;


import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.profiles.ProfileKeyCredentialResponse;
import org.whispersystems.libsignal.logging.Log;
import org.whispersystems.signalservice.internal.util.JsonUtil;

import java.util.UUID;

public class SignalServiceProfile {

  public enum RequestType {
    PROFILE,
    PROFILE_AND_CREDENTIAL
  }

  private static final String TAG = SignalServiceProfile.class.getSimpleName();

  @JsonProperty
  private String identityKey;

  @JsonProperty
  private String name;

  @JsonProperty
  private String avatar;

  @JsonProperty
  private String unidentifiedAccess;

  @JsonProperty
  private boolean unrestrictedUnidentifiedAccess;

  @JsonProperty
  private Capabilities capabilities;

  @JsonProperty
  private String username;

  @JsonProperty
  @JsonSerialize(using = JsonUtil.UuidSerializer.class)
  @JsonDeserialize(using = JsonUtil.UuidDeserializer.class)
  private UUID uuid;

  @JsonProperty
  private byte[] credential;

  @JsonIgnore
  private RequestType requestType;

  public SignalServiceProfile() {}

  public String getIdentityKey() {
    return identityKey;
  }

  public String getName() {
    return name;
  }

  public String getAvatar() {
    return avatar;
  }

  public String getUnidentifiedAccess() {
    return unidentifiedAccess;
  }

  public boolean isUnrestrictedUnidentifiedAccess() {
    return unrestrictedUnidentifiedAccess;
  }

  public Capabilities getCapabilities() {
    return capabilities;
  }

  public String getUsername() {
    return username;
  }

  public UUID getUuid() {
    return uuid;
  }

  public RequestType getRequestType() {
    return requestType;
  }

  public void setRequestType(RequestType requestType) {
    this.requestType = requestType;
  }

  public static class Capabilities {
    @JsonProperty
    private boolean uuid;

    @JsonProperty
    private boolean gv2;

    @JsonProperty
    private boolean storage;

    @JsonCreator
    public Capabilities() {}

    public Capabilities(boolean uuid, boolean gv2, boolean storage) {
      this.uuid    = uuid;
      this.gv2     = gv2;
      this.storage = storage;
    }

    public boolean isUuid() {
      return uuid;
    }

    public boolean isGv2() {
      return gv2;
    }

    public boolean isStorage() {
      return storage;
    }
  }

  public ProfileKeyCredentialResponse getProfileKeyCredentialResponse() {
    if (credential == null) return null;

    try {
      return new ProfileKeyCredentialResponse(credential);
    } catch (InvalidInputException e) {
      Log.w(TAG, e);
      return null;
    }
  }
}
