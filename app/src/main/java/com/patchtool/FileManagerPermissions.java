package com.patchtool;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

public class FileManagerPermissions {

  public static final int REQUEST_CODE_MANAGE_FILES = 101;

  public interface PermissionCallback {
    void onPermissionDenied();

    void onPermissionGranted();
  }

  public static void checkAndRequestAllFilesAccess(final Activity activity,
      final PermissionCallback callback) {

    if (activity == null || activity.isFinishing()) {
      return;
    }

    // Android 11+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

      if (Environment.isExternalStorageManager()) {

        if (callback != null) {
          callback.onPermissionGranted();
        }

        return;
      }

      new AlertDialog.Builder(activity).setTitle("صلاحية مطلوبة")
          .setMessage("للتصفح وإدارة الملفات بشكل صحيح على هذا الجهاز، "
              + "يحتاج التطبيق إلى صلاحية الوصول إلى جميع الملفات. "
              + "يرجى النقر على منح ثم تمكين الصلاحية للتطبيق.")
          .setPositiveButton("منح", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

              try {

                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));

                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_FILES);

              } catch (Exception e) {

                try {

                  Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);

                  activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_FILES);

                } catch (Exception ex) {

                  if (callback != null) {
                    callback.onPermissionDenied();
                  }
                }
              }
            }
          }).setNegativeButton("إلغاء", new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {

              dialog.dismiss();

              if (callback != null) {
                callback.onPermissionDenied();
              }
            }
          }).setCancelable(false).show();

      return;
    }

    // Android 6 - Android 10
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

      if (activity.checkSelfPermission(
          Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

        activity.requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_CODE_MANAGE_FILES);

        return;
      }

      if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && activity.checkSelfPermission(
          Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

        activity.requestPermissions(new String[] { Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE }, REQUEST_CODE_MANAGE_FILES);

        return;
      }
    }

    if (callback != null) {
      callback.onPermissionGranted();
    }
  }

  public static boolean isAllFilesAccessGranted() {

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
      return Environment.isExternalStorageManager();
    }

    return true;
  }

  public static void onActivityResult(int requestCode, PermissionCallback callback) {

    if (requestCode == REQUEST_CODE_MANAGE_FILES) {

      if (isAllFilesAccessGranted()) {

        if (callback != null) {
          callback.onPermissionGranted();
        }

      } else {

        if (callback != null) {
          callback.onPermissionDenied();
        }
      }
    }
  }
}
