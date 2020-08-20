/*
 * Copyright (C) 2016 Open Whisper Systems
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

package org.thoughtcrime.securesms;

import android.Manifest;
import android.app.PictureInPictureParams;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.util.Rational;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProviders;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.thoughtcrime.securesms.components.TooltipPopup;
import org.thoughtcrime.securesms.components.webrtc.WebRtcAudioOutput;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallView;
import org.thoughtcrime.securesms.components.webrtc.WebRtcCallViewModel;
import org.thoughtcrime.securesms.crypto.storage.TextSecureIdentityKeyStore;
import org.thoughtcrime.securesms.events.WebRtcViewModel;
import org.thoughtcrime.securesms.logging.Log;
import org.thoughtcrime.securesms.messagerequests.CalleeMustAcceptMessageRequestActivity;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.recipients.Recipient;
import org.thoughtcrime.securesms.ringrtc.RemotePeer;
import org.thoughtcrime.securesms.service.WebRtcCallService;
import org.thoughtcrime.securesms.util.EllapsedTimeFormatter;
import org.thoughtcrime.securesms.util.FeatureFlags;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.VerifySpan;
import org.thoughtcrime.securesms.util.ViewUtil;
import org.whispersystems.libsignal.IdentityKey;
import org.whispersystems.libsignal.SignalProtocolAddress;
import org.whispersystems.signalservice.api.messages.calls.HangupMessage;

import static org.whispersystems.libsignal.SessionCipher.SESSION_LOCK;

public class WebRtcCallActivity extends AppCompatActivity {


  private static final String TAG = WebRtcCallActivity.class.getSimpleName();

  private static final int STANDARD_DELAY_FINISH    = 1000;
  public  static final int BUSY_SIGNAL_DELAY_FINISH = 5500;

  public static final String ANSWER_ACTION   = WebRtcCallActivity.class.getCanonicalName() + ".ANSWER_ACTION";
  public static final String DENY_ACTION     = WebRtcCallActivity.class.getCanonicalName() + ".DENY_ACTION";
  public static final String END_CALL_ACTION = WebRtcCallActivity.class.getCanonicalName() + ".END_CALL_ACTION";

  public static final String EXTRA_ENABLE_VIDEO_IF_AVAILABLE = WebRtcCallActivity.class.getCanonicalName() + ".ENABLE_VIDEO_IF_AVAILABLE";

  private WebRtcCallView      callScreen;
  private TooltipPopup        videoTooltip;
  private WebRtcCallViewModel viewModel;
  private boolean             enableVideoIfAvailable;

  @Override
  public void onCreate(Bundle savedInstanceState) {
    Log.i(TAG, "onCreate()");
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    super.onCreate(savedInstanceState);

    requestWindowFeature(Window.FEATURE_NO_TITLE);
    setContentView(R.layout.webrtc_call_activity);
    getSupportActionBar().hide();

    setVolumeControlStream(AudioManager.STREAM_VOICE_CALL);

    initializeResources();
    initializeViewModel();

    processIntent(getIntent());

    enableVideoIfAvailable = getIntent().getBooleanExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE, false);
    getIntent().removeExtra(EXTRA_ENABLE_VIDEO_IF_AVAILABLE);
  }

  @Override
  public void onResume() {
    Log.i(TAG, "onResume()");
    super.onResume();
    initializeScreenshotSecurity();

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this);
    }
  }

  @Override
  public void onNewIntent(Intent intent){
    Log.i(TAG, "onNewIntent");
    super.onNewIntent(intent);
    processIntent(intent);
  }

  @Override
  public void onPause() {
    Log.i(TAG, "onPause");
    super.onPause();

    if (!isInPipMode()) {
      EventBus.getDefault().unregister(this);
    }
  }

  @Override
  protected void onStop() {
    Log.i(TAG, "onStop");
    super.onStop();

    EventBus.getDefault().unregister(this);
  }

  @Override
  public void onConfigurationChanged(Configuration newConfiguration) {
    super.onConfigurationChanged(newConfiguration);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    Permissions.onRequestPermissionsResult(this, requestCode, permissions, grantResults);
  }

  @Override
  protected void onUserLeaveHint() {
    enterPipModeIfPossible();
  }

  @Override
  public void onBackPressed() {
    if (!enterPipModeIfPossible()) {
      super.onBackPressed();
    }
  }

  @Override
  public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
    viewModel.setIsInPipMode(isInPictureInPictureMode);
  }

  private boolean enterPipModeIfPossible() {
    if (isSystemPipEnabledAndAvailable()) {
      PictureInPictureParams params = new PictureInPictureParams.Builder()
              .setAspectRatio(new Rational(9, 16))
              .build();
      enterPictureInPictureMode(params);
      return true;
    }
    return false;
  }

  private boolean isInPipMode() {
    return isSystemPipEnabledAndAvailable() && isInPictureInPictureMode();
  }

  private void processIntent(@NonNull Intent intent) {
    if (ANSWER_ACTION.equals(intent.getAction())) {
      viewModel.setRecipient(EventBus.getDefault().getStickyEvent(WebRtcViewModel.class).getRecipient());
      handleAnswerWithAudio();
    } else if (DENY_ACTION.equals(intent.getAction())) {
      handleDenyCall();
    } else if (END_CALL_ACTION.equals(intent.getAction())) {
      handleEndCall();
    }
  }

  private void initializeScreenshotSecurity() {
    if (TextSecurePreferences.isScreenSecurityEnabled(this)) {
      getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    } else {
      getWindow().clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
  }

  private void initializeResources() {
    callScreen = ViewUtil.findById(this, R.id.callScreen);
    callScreen.setControlsListener(new ControlsListener());
  }

  private void initializeViewModel() {
    viewModel = ViewModelProviders.of(this).get(WebRtcCallViewModel.class);
    viewModel.setIsInPipMode(isInPipMode());
    viewModel.getRemoteVideoEnabled().observe(this,callScreen::setRemoteVideoEnabled);
    viewModel.getMicrophoneEnabled().observe(this, callScreen::setMicEnabled);
    viewModel.getCameraDirection().observe(this, callScreen::setCameraDirection);
    viewModel.getLocalRenderState().observe(this, callScreen::setLocalRenderState);
    viewModel.getWebRtcControls().observe(this, callScreen::setWebRtcControls);
    viewModel.getEvents().observe(this, this::handleViewModelEvent);
    viewModel.getCallTime().observe(this, this::handleCallTime);
    viewModel.displaySquareCallCard().observe(this, callScreen::showCallCard);
  }

  private void handleViewModelEvent(@NonNull WebRtcCallViewModel.Event event) {
    if (isInPipMode()) {
      return;
    }

    switch (event) {
      case SHOW_VIDEO_TOOLTIP:
        if (videoTooltip == null) {
          videoTooltip = TooltipPopup.forTarget(callScreen.getVideoTooltipTarget())
                                     .setBackgroundTint(ContextCompat.getColor(this, R.color.core_ultramarine))
                                     .setTextColor(ContextCompat.getColor(this, R.color.core_white))
                                     .setText(R.string.WebRtcCallActivity__tap_here_to_turn_on_your_video)
                                     .setOnDismissListener(() -> viewModel.onDismissedVideoTooltip())
                                     .show(TooltipPopup.POSITION_ABOVE);
          return;
        }
        break;
      case DISMISS_VIDEO_TOOLTIP:
        if (videoTooltip != null) {
          videoTooltip.dismiss();
          videoTooltip = null;
        }
        break;
      default:
        throw new IllegalArgumentException("Unknown event: " + event);
    }
  }

  private void handleCallTime(long callTime) {
    EllapsedTimeFormatter ellapsedTimeFormatter = EllapsedTimeFormatter.fromDurationMillis(callTime);

    if (ellapsedTimeFormatter == null) {
      return;
    }

    callScreen.setStatus(getString(R.string.WebRtcCallActivity__signal_s, ellapsedTimeFormatter.toString()));
  }

  private void handleSetAudioHandset() {
    Intent intent = new Intent(this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_SET_AUDIO_SPEAKER);
    startService(intent);
  }

  private void handleSetAudioSpeaker() {
    Intent intent = new Intent(this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_SET_AUDIO_SPEAKER);
    intent.putExtra(WebRtcCallService.EXTRA_SPEAKER, true);
    startService(intent);
  }

  private void handleSetAudioBluetooth() {
    Intent intent = new Intent(this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_SET_AUDIO_BLUETOOTH);
    intent.putExtra(WebRtcCallService.EXTRA_BLUETOOTH, true);
    startService(intent);
  }

  private void handleSetMuteAudio(boolean enabled) {
    Intent intent = new Intent(this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_SET_MUTE_AUDIO);
    intent.putExtra(WebRtcCallService.EXTRA_MUTE, enabled);
    startService(intent);
  }

  private void handleSetMuteVideo(boolean muted) {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      String recipientDisplayName = recipient.getDisplayName(this);

      Permissions.with(this)
                 .request(Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName), R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity__to_call_s_signal_needs_access_to_your_camera, recipientDisplayName))
                 .onAllGranted(() -> {
                   Intent intent = new Intent(this, WebRtcCallService.class);
                   intent.setAction(WebRtcCallService.ACTION_SET_ENABLE_VIDEO);
                   intent.putExtra(WebRtcCallService.EXTRA_ENABLE, !muted);
                   startService(intent);
                 })
                 .execute();
    }
  }

  private void handleFlipCamera() {
    Intent intent = new Intent(this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_FLIP_CAMERA);
    startService(intent);
  }

  private void handleAnswerWithAudio() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Permissions.with(this)
                 .request(Manifest.permission.RECORD_AUDIO)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_from_s_give_signal_access_to_your_microphone, recipient.getDisplayName(this)),
                                      R.drawable.ic_mic_solid_24)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
                 .onAllGranted(() -> {
                   callScreen.setRecipient(recipient);
                   callScreen.setStatus(getString(R.string.RedPhone_answering));

                   Intent intent = new Intent(this, WebRtcCallService.class);
                   intent.setAction(WebRtcCallService.ACTION_ACCEPT_CALL);
                   startService(intent);
                 })
                 .onAnyDenied(this::handleDenyCall)
                 .execute();
    }
  }

  private void handleAnswerWithVideo() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Permissions.with(this)
                 .request(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                 .ifNecessary()
                 .withRationaleDialog(getString(R.string.WebRtcCallActivity_to_answer_the_call_from_s_give_signal_access_to_your_microphone, recipient.getDisplayName(this)),
                     R.drawable.ic_mic_solid_24, R.drawable.ic_video_solid_24_tinted)
                 .withPermanentDenialDialog(getString(R.string.WebRtcCallActivity_signal_requires_microphone_and_camera_permissions_in_order_to_make_or_receive_calls))
                 .onAllGranted(() -> {
                   callScreen.setRecipient(recipient);
                   callScreen.setStatus(getString(R.string.RedPhone_answering));

                   Intent intent = new Intent(this, WebRtcCallService.class);
                   intent.setAction(WebRtcCallService.ACTION_ACCEPT_CALL);
                   intent.putExtra(WebRtcCallService.EXTRA_ANSWER_WITH_VIDEO, true);
                   startService(intent);

                   handleSetMuteVideo(false);
                 })
                 .onAnyDenied(this::handleDenyCall)
                 .execute();
    }
  }

  private void handleDenyCall() {
    Recipient recipient = viewModel.getRecipient().get();

    if (!recipient.equals(Recipient.UNKNOWN)) {
      Intent intent = new Intent(this, WebRtcCallService.class);
      intent.setAction(WebRtcCallService.ACTION_DENY_CALL);
      startService(intent);

      callScreen.setRecipient(recipient);
      callScreen.setStatus(getString(R.string.RedPhone_ending_call));
      delayedFinish();
    }
  }

  private void handleEndCall() {
    Log.i(TAG, "Hangup pressed, handling termination now...");
    Intent intent = new Intent(WebRtcCallActivity.this, WebRtcCallService.class);
    intent.setAction(WebRtcCallService.ACTION_LOCAL_HANGUP);
    startService(intent);
  }

  private void handleIncomingCall(@NonNull WebRtcViewModel event) {
    callScreen.setRecipient(event.getRecipient());
  }

  private void handleOutgoingCall(@NonNull WebRtcViewModel event) {
    callScreen.setRecipient(event.getRecipient());
    callScreen.setStatus(getString(R.string.WebRtcCallActivity__calling));
  }

  private void handleTerminate(@NonNull Recipient recipient, @NonNull HangupMessage.Type hangupType) {
    Log.i(TAG, "handleTerminate called: " + hangupType.name());

    callScreen.setRecipient(recipient);
    callScreen.setStatusFromHangupType(hangupType);

    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);

    if (hangupType == HangupMessage.Type.NEED_PERMISSION) {
      startActivity(CalleeMustAcceptMessageRequestActivity.createIntent(this, recipient.getId()));
    }
    delayedFinish();
  }

  private void handleCallRinging(@NonNull WebRtcViewModel event) {
    callScreen.setRecipient(event.getRecipient());
    callScreen.setStatus(getString(R.string.RedPhone_ringing));
  }

  private void handleCallBusy(@NonNull WebRtcViewModel event) {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setRecipient(event.getRecipient());
    callScreen.setStatus(getString(R.string.RedPhone_busy));

    delayedFinish(BUSY_SIGNAL_DELAY_FINISH);
  }

  private void handleCallConnected(@NonNull WebRtcViewModel event) {
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_IGNORE_CHEEK_PRESSES);
    callScreen.setRecipient(event.getRecipient());
  }

  private void handleRecipientUnavailable(@NonNull WebRtcViewModel event) {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setRecipient(event.getRecipient());
    callScreen.setStatus(getString(R.string.RedPhone_recipient_unavailable));
    delayedFinish();
  }

  private void handleServerFailure(@NonNull WebRtcViewModel event) {
    EventBus.getDefault().removeStickyEvent(WebRtcViewModel.class);
    callScreen.setRecipient(event.getRecipient());
    callScreen.setStatus(getString(R.string.RedPhone_network_failed));
    delayedFinish();
  }

  private void handleNoSuchUser(final @NonNull WebRtcViewModel event) {
    if (isFinishing()) return; // XXX Stuart added this check above, not sure why, so I'm repeating in ignorance. - moxie
    AlertDialog.Builder dialog = new AlertDialog.Builder(this);
    dialog.setTitle(R.string.RedPhone_number_not_registered);
    dialog.setIconAttribute(R.attr.dialog_alert_icon);
    dialog.setMessage(R.string.RedPhone_the_number_you_dialed_does_not_support_secure_voice);
    dialog.setCancelable(true);
    dialog.setPositiveButton(R.string.RedPhone_got_it, new OnClickListener() {
      @Override
      public void onClick(DialogInterface dialog, int which) {
        WebRtcCallActivity.this.handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL);
      }
    });
    dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
      @Override
      public void onCancel(DialogInterface dialog) {
        WebRtcCallActivity.this.handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL);
      }
    });
    dialog.show();
  }

  private void handleUntrustedIdentity(@NonNull WebRtcViewModel event) {
    final IdentityKey theirKey  = event.getIdentityKey();
    final Recipient   recipient = event.getRecipient();

    if (theirKey == null) {
      handleTerminate(recipient, HangupMessage.Type.NORMAL);
    }

    String          name            = recipient.getDisplayName(this);
    String          introduction    = getString(R.string.WebRtcCallScreen_new_safety_numbers, name, name);
    SpannableString spannableString = new SpannableString(introduction + " " + getString(R.string.WebRtcCallScreen_you_may_wish_to_verify_this_contact));

    spannableString.setSpan(new VerifySpan(this, recipient.getId(), theirKey), introduction.length() + 1, spannableString.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

    AppCompatTextView untrustedIdentityExplanation = new AppCompatTextView(this);
    untrustedIdentityExplanation.setText(spannableString);
    untrustedIdentityExplanation.setMovementMethod(LinkMovementMethod.getInstance());

    new AlertDialog.Builder(this)
                   .setView(untrustedIdentityExplanation)
                   .setPositiveButton(R.string.WebRtcCallScreen_accept, (d, w) -> {
                     synchronized (SESSION_LOCK) {
                       TextSecureIdentityKeyStore identityKeyStore = new TextSecureIdentityKeyStore(WebRtcCallActivity.this);
                       identityKeyStore.saveIdentity(new SignalProtocolAddress(recipient.requireServiceId(), 1), theirKey, true);
                     }

                     d.dismiss();

                     Intent intent = new Intent(WebRtcCallActivity.this, WebRtcCallService.class);
                     intent.setAction(WebRtcCallService.ACTION_OUTGOING_CALL)
                           .putExtra(WebRtcCallService.EXTRA_REMOTE_PEER, new RemotePeer(recipient.getId()));

                     startService(intent);
                   })
                   .setNegativeButton(R.string.WebRtcCallScreen_end_call, (d, w) -> {
                     d.dismiss();
                     handleTerminate(recipient, HangupMessage.Type.NORMAL);
                   })
                   .show();
  }

  private boolean isSystemPipEnabledAndAvailable() {
    return Build.VERSION.SDK_INT >= 26 &&
           getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE);
  }

  private void delayedFinish() {
    delayedFinish(STANDARD_DELAY_FINISH);
  }

  private void delayedFinish(int delayMillis) {
    callScreen.postDelayed(WebRtcCallActivity.this::finish, delayMillis);
  }

  @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
  public void onEventMainThread(final WebRtcViewModel event) {
    Log.i(TAG, "Got message from service: " + event);

    viewModel.setRecipient(event.getRecipient());

    switch (event.getState()) {
      case CALL_CONNECTED:          handleCallConnected(event);                                                break;
      case NETWORK_FAILURE:         handleServerFailure(event);                                                break;
      case CALL_RINGING:            handleCallRinging(event);                                                  break;
      case CALL_DISCONNECTED:       handleTerminate(event.getRecipient(), HangupMessage.Type.NORMAL);          break;
      case CALL_ACCEPTED_ELSEWHERE: handleTerminate(event.getRecipient(), HangupMessage.Type.ACCEPTED);        break;
      case CALL_DECLINED_ELSEWHERE: handleTerminate(event.getRecipient(), HangupMessage.Type.DECLINED);        break;
      case CALL_ONGOING_ELSEWHERE:  handleTerminate(event.getRecipient(), HangupMessage.Type.BUSY);            break;
      case CALL_NEEDS_PERMISSION:   handleTerminate(event.getRecipient(), HangupMessage.Type.NEED_PERMISSION); break;
      case NO_SUCH_USER:            handleNoSuchUser(event);                                                   break;
      case RECIPIENT_UNAVAILABLE:   handleRecipientUnavailable(event);                                         break;
      case CALL_INCOMING:           handleIncomingCall(event);                                                 break;
      case CALL_OUTGOING:           handleOutgoingCall(event);                                                 break;
      case CALL_BUSY:               handleCallBusy(event);                                                     break;
      case UNTRUSTED_IDENTITY:      handleUntrustedIdentity(event);                                            break;
    }

    callScreen.setLocalRenderer(event.getLocalRenderer());
    callScreen.setRemoteRenderer(event.getRemoteRenderer());

    boolean enableVideo = event.getLocalCameraState().getCameraCount() > 0 && enableVideoIfAvailable;

    viewModel.updateFromWebRtcViewModel(event, enableVideo);

    if (enableVideo) {
      enableVideoIfAvailable = false;
      handleSetMuteVideo(false);
    }
  }

  private final class ControlsListener implements WebRtcCallView.ControlsListener {

    @Override
    public void onControlsFadeOut() {
      if (videoTooltip != null) {
        videoTooltip.dismiss();
      }
    }

    @Override
    public void onAudioOutputChanged(@NonNull WebRtcAudioOutput audioOutput) {
      switch (audioOutput) {
        case HANDSET:
          handleSetAudioHandset();
          break;
        case HEADSET:
          handleSetAudioBluetooth();
          break;
        case SPEAKER:
          handleSetAudioSpeaker();
          break;
        default:
          throw new IllegalStateException("Unknown output: " + audioOutput);
      }
    }

    @Override
    public void onVideoChanged(boolean isVideoEnabled) {
      handleSetMuteVideo(!isVideoEnabled);
    }

    @Override
    public void onMicChanged(boolean isMicEnabled) {
      handleSetMuteAudio(!isMicEnabled);
    }

    @Override
    public void onCameraDirectionChanged() {
      handleFlipCamera();
    }

    @Override
    public void onEndCallPressed() {
      handleEndCall();
    }

    @Override
    public void onDenyCallPressed() {
      handleDenyCall();
    }

    @Override
    public void onAcceptCallWithVoiceOnlyPressed() {
      handleAnswerWithAudio();
    }

    @Override
    public void onAcceptCallPressed() {
      if (viewModel.isAnswerWithVideoAvailable()) {
        handleAnswerWithVideo();
      } else {
        handleAnswerWithAudio();
      }
    }

    @Override
    public void onDownCaretPressed() {

    }
  }

}
