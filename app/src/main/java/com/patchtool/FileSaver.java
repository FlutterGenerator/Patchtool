package com.patchtool;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

public class FileSaver {

  public static final int REQUEST_CODE_CREATE_FILE = 201;

  private final Activity activity;

  private FileSaverCallback callback;

  private boolean requestInProgress = false;

  public interface FileSaverCallback {

    void onFileCreated(Uri uri);

    void onSaveCancelled();
  }

  public FileSaver(Activity activity) {

    if (activity == null) {
      throw new IllegalArgumentException("Activity cannot be null");
    }

    this.activity = activity;
  }

  public FileSaver setCallback(FileSaverCallback callback) {

    this.callback = callback;

    return this;
  }

  public void createFile(String fileName, String mimeType) {

    if (activity.isFinishing()
        || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
      notifyCancelled();
      return;
    }

    if (requestInProgress) {
      return;
    }

    if (fileName == null || fileName.trim().isEmpty()) {
      notifyCancelled();
      return;
    }

    if (mimeType == null || mimeType.trim().isEmpty()) {
      mimeType = "application/octet-stream";
    }

    try {

      Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);

      intent.addCategory(Intent.CATEGORY_OPENABLE);

      intent.setType(mimeType);

      intent.putExtra(Intent.EXTRA_TITLE, fileName);

      intent
          .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

      requestInProgress = true;

      activity.startActivityForResult(intent, REQUEST_CODE_CREATE_FILE);

    } catch (Exception e) {

      requestInProgress = false;

      notifyCancelled();
    }
  }

  public void handleActivityResult(int requestCode, int resultCode, Intent data) {

    if (requestCode != REQUEST_CODE_CREATE_FILE) {
      return;
    }

    requestInProgress = false;

    if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {

      Uri uri = data.getData();

      if (callback != null) {
        callback.onFileCreated(uri);
      }

    } else {

      notifyCancelled();
    }
  }

  private void notifyCancelled() {

    if (callback != null) {
      callback.onSaveCancelled();
    }
  }
}
