package org.thoughtcrime.securesms;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ContextThemeWrapper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.thoughtcrime.securesms.util.DynamicNoActionBarTheme;
import org.thoughtcrime.securesms.util.DynamicTheme;
import org.thoughtcrime.securesms.util.ThemeUtil;

public class ClearProfileAvatarActivity extends Activity {

  private static final String ARG_TITLE = "arg_title";

  public static Intent createForUserProfilePhoto() {
    return new Intent("org.thoughtcrime.securesms.action.CLEAR_PROFILE_PHOTO");
  }

  public static Intent createForGroupProfilePhoto() {
    Intent intent = new Intent("org.thoughtcrime.securesms.action.CLEAR_PROFILE_PHOTO");
    intent.putExtra(ARG_TITLE, R.string.ClearProfileActivity_remove_group_photo);
    return intent;
  }

  @Override
  public void onResume() {
    super.onResume();

    int titleId = getIntent().getIntExtra(ARG_TITLE, R.string.ClearProfileActivity_remove_profile_photo);

    new AlertDialog.Builder(new ContextThemeWrapper(this, DynamicTheme.isDarkTheme(this) ? R.style.TextSecure_DarkTheme : R.style.TextSecure_LightTheme))
                   .setMessage(titleId)
                   .setNegativeButton(android.R.string.cancel, (dialog, which) -> finish())
                   .setPositiveButton(R.string.ClearProfileActivity_remove, (dialog, which) -> {
                     Intent result = new Intent();
                     result.putExtra("delete", true);
                     setResult(Activity.RESULT_OK, result);
                     finish();
                   })
                   .setOnCancelListener(dialog -> finish())
                   .show();
  }

}
