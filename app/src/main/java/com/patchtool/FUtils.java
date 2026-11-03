package com.patchtool;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.*;

public class FUtils {

  public static String getPath(Context context, Uri uri) {

    if (context == null || uri == null) {
      return null;
    }

    try {

      if (DocumentsContract.isDocumentUri(context, uri)) {

        // External Storage Provider
        if (isExternalStorageDocument(uri)) {

          String documentId = DocumentsContract.getDocumentId(uri);

          String[] split = documentId.split(":", 2);

          if (split.length < 2) {
            return null;
          }

          String type = split[0];
          String path = split[1];

          if ("primary".equalsIgnoreCase(type)) {

            return new File("/storage/emulated/0", path).getAbsolutePath();
          }

          // Secondary storage paths cannot be resolved
          // reliably on all Android devices.
          return null;
        }

        // Downloads Provider
        if (isDownloadsDocument(uri)) {

          String documentId = DocumentsContract.getDocumentId(uri);

          if (documentId.startsWith("raw:")) {
            return documentId.substring(4);
          }

          if (documentId.startsWith("msf:")) {
            return null;
          }

          try {

            long id = Long.parseLong(documentId);

            Uri contentUri = ContentUris
                .withAppendedId(Uri.parse("content://downloads/public_downloads"), id);

            return getDataColumn(context, contentUri, null, null);

          } catch (NumberFormatException e) {
            return null;
          }
        }

        // Media Provider
        if (isMediaDocument(uri)) {

          String documentId = DocumentsContract.getDocumentId(uri);

          String[] split = documentId.split(":", 2);

          if (split.length < 2) {
            return null;
          }

          String type = split[0];

          Uri contentUri = null;

          if ("image".equals(type)) {

            contentUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

          } else if ("video".equals(type)) {

            contentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;

          } else if ("audio".equals(type)) {

            contentUri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
          }

          if (contentUri != null) {

            return getDataColumn(context, contentUri, "_id=?", new String[] { split[1] });
          }

          return null;
        }

        return null;
      }

      // Content URI
      if ("content".equalsIgnoreCase(uri.getScheme())) {

        return getDataColumn(context, uri, null, null);
      }

      // File URI
      if ("file".equalsIgnoreCase(uri.getScheme())) {

        return uri.getPath();
      }

    } catch (Exception e) {
      return null;
    }

    return null;
  }

  private static String getDataColumn(Context context, Uri uri, String selection,
      String[] selectionArgs) {

    if (uri == null) {
      return null;
    }

    Cursor cursor = null;

    try {

      cursor = context.getContentResolver().query(uri, new String[] { "_data" }, selection,
          selectionArgs, null);

      if (cursor != null && cursor.moveToFirst()) {

        int columnIndex = cursor.getColumnIndex("_data");

        if (columnIndex >= 0) {

          String path = cursor.getString(columnIndex);

          if (path != null && !path.isEmpty()) {
            return path;
          }
        }
      }

    } catch (Exception e) {
      return null;

    } finally {

      if (cursor != null) {
        cursor.close();
      }
    }

    return null;
  }

  public static String getFileName(Context context, Uri uri) {

    if (context == null || uri == null) {
      return null;
    }

    Cursor cursor = null;

    try {

      cursor = context.getContentResolver().query(uri,
          new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);

      if (cursor != null && cursor.moveToFirst()) {

        int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);

        if (index >= 0) {
          return cursor.getString(index);
        }
      }

    } catch (Exception ignored) {

    } finally {

      if (cursor != null) {
        cursor.close();
      }
    }

    String path = uri.getLastPathSegment();

    return path;
  }

  public static boolean isExternalStorageDocument(Uri uri) {

    return "com.android.externalstorage.documents".equals(uri.getAuthority());
  }

  public static boolean isDownloadsDocument(Uri uri) {

    return "com.android.providers.downloads.documents".equals(uri.getAuthority());
  }

  public static boolean isMediaDocument(Uri uri) {

    return "com.android.providers.media.documents".equals(uri.getAuthority());
  }

  public static boolean isContentUri(Uri uri) {

    return uri != null && "content".equalsIgnoreCase(uri.getScheme());
  }

  public static boolean isFileUri(Uri uri) {

    return uri != null && "file".equalsIgnoreCase(uri.getScheme());
  }

  public static boolean copyUriToFile(Context context, Uri uri, File destination) {

    if (context == null || uri == null || destination == null) {
      return false;
    }

    InputStream input = null;
    OutputStream output = null;

    try {

      input = context.getContentResolver().openInputStream(uri);

      if (input == null) {
        return false;
      }

      output = new FileOutputStream(destination);

      byte[] buffer = new byte[8192];

      int length;

      while ((length = input.read(buffer)) != -1) {

        output.write(buffer, 0, length);
      }

      output.flush();

      return true;

    } catch (Exception e) {

      return false;

    } finally {

      try {
        if (input != null) {
          input.close();
        }
      } catch (IOException ignored) {
      }

      try {
        if (output != null) {
          output.close();
        }
      } catch (IOException ignored) {
      }
    }
  }
}
