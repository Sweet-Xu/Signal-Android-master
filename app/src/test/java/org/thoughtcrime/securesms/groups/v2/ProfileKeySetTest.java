package org.thoughtcrime.securesms.groups.v2;

import org.junit.Test;
import org.signal.zkgroup.profiles.ProfileKey;
import org.thoughtcrime.securesms.crypto.ProfileKeyUtil;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.testutil.LogRecorder;

import java.util.Collection;
import java.util.UUID;

import edu.emory.mathcs.backport.java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.thoughtcrime.securesms.groups.v2.ChangeBuilder.changeBy;
import static org.thoughtcrime.securesms.groups.v2.ChangeBuilder.changeByUnknown;
import static org.thoughtcrime.securesms.testutil.LogRecorder.hasMessages;

public final class ProfileKeySetTest {

  @Test
  public void empty_change() {
    UUID          editor        = UUID.randomUUID();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
  }

  @Test
  public void new_member_is_not_authoritative() {
    UUID          editor        = UUID.randomUUID();
    UUID          newMember     = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).addMember(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_self_is_authoritative() {
    UUID          newMember     = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(newMember).addMember(newMember, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_self_promote_is_authoritative() {
    UUID          newMember     = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(newMember).promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_promote_by_other_editor_is_not_authoritative() {
    UUID          editor        = UUID.randomUUID();
    UUID          newMember     = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void new_member_by_promote_by_unknown_editor_is_not_authoritative() {
    UUID          newMember     = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeByUnknown().promote(newMember, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(newMember, profileKey)));
  }

  @Test
  public void profile_key_update_by_self_is_authoritative() {
    UUID          member        = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey)));
  }

  @Test
  public void profile_key_update_by_another_is_not_authoritative() {
    UUID          editor        = UUID.randomUUID();
    UUID          member        = UUID.randomUUID();
    ProfileKey    profileKey    = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(member, profileKey)));
  }

  @Test
  public void multiple_updates_overwrite() {
    UUID          editor        = UUID.randomUUID();
    UUID          member        = UUID.randomUUID();
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(profileKeySet.getProfileKeys(), is(Collections.singletonMap(member, profileKey2)));
  }

  @Test
  public void authoritative_takes_priority_when_seen_first() {
    UUID          editor        = UUID.randomUUID();
    UUID          member        = UUID.randomUUID();
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey1)));
  }

  @Test
  public void authoritative_takes_priority_when_seen_second() {
    UUID          editor        = UUID.randomUUID();
    UUID          member        = UUID.randomUUID();
    ProfileKey    profileKey1   = ProfileKeyUtil.createNew();
    ProfileKey    profileKey2   = ProfileKeyUtil.createNew();
    ProfileKeySet profileKeySet = new ProfileKeySet();

    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, profileKey1).build());
    profileKeySet.addKeysFromGroupChange(changeBy(member).profileKeyUpdate(member, profileKey2).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertThat(profileKeySet.getAuthoritativeProfileKeys(), is(Collections.singletonMap(member, profileKey2)));
  }

  @Test
  public void bad_profile_key() {
    LogRecorder   logRecorder   = new LogRecorder();
    UUID          editor        = UUID.randomUUID();
    UUID          member        = UUID.randomUUID();
    byte[]        badProfileKey = new byte[10];
    ProfileKeySet profileKeySet = new ProfileKeySet();

    Log.initialize(logRecorder);
    profileKeySet.addKeysFromGroupChange(changeBy(editor).profileKeyUpdate(member, badProfileKey).build());

    assertTrue(profileKeySet.getProfileKeys().isEmpty());
    assertTrue(profileKeySet.getAuthoritativeProfileKeys().isEmpty());
    assertThat(logRecorder.getWarnings(), hasMessages("Bad profile key in group"));
  }
}
