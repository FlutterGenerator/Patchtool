package com.patchtool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.text.TextUtils;
import android.view.View;
import android.widget.*;
import java.io.File;
import java.util.*;

class FilePickerDialog {

  private final Activity activity;

  private FilePickerCallback callback;
  private File currentPath;

  private AlertDialog dialog;
  private CustomFileAdapter fileListAdapter;

  private ListView listView;
  private TextView pathTextView;

  private final List<File> fileList = new ArrayList<>();
  private final List<File> selectedFiles = new ArrayList<>();

  private final HashMap<String, Object> customIcons = new HashMap<>();

  private String title = "Select a File";

  private boolean multiSelectionEnabled = false;
  private boolean useIconBorders = false;

  private final List<String> excludedExtensions = new ArrayList<>();

  private String backgroundColor = "#303030";
  private String titleColor = "#FFFFFF";
  private String pathTextColor = "#D0D0D0";
  private String fileListTextColor = "#B6B6B6";
  private String buttonColor = "#254898";

  public interface FilePickerCallback {
    void onFilesSelected(List<File> files);
  }

  public FilePickerDialog(Activity activity) {

    if (activity == null) {
      throw new IllegalArgumentException("Activity cannot be null");
    }

    this.activity = activity;

    File storage = Environment.getExternalStorageDirectory();

    if (storage != null && storage.exists()) {
      currentPath = storage;
    } else {
      currentPath = new File("/");
    }
  }

  public FilePickerDialog setTitle(String title) {
    this.title = title;
    return this;
  }

  public FilePickerDialog setInitialPath(String path) {

    if (path != null) {

      File file = new File(path);

      if (file.exists() && file.isDirectory()) {
        currentPath = file;
      }
    }

    return this;
  }

  public FilePickerDialog enableMultiSelection(boolean enabled) {
    multiSelectionEnabled = enabled;
    return this;
  }

  public FilePickerDialog excludeExtensions(String... extensions) {

    if (extensions != null) {

      for (String extension : extensions) {

        if (extension != null) {

          extension = extension.toLowerCase(Locale.ROOT).replaceFirst("^\\.", "");

          excludedExtensions.add(extension);
        }
      }
    }

    return this;
  }

  public FilePickerDialog setCallback(FilePickerCallback callback) {
    this.callback = callback;
    return this;
  }

  public FilePickerDialog setBackgroundColor(String color) {
    backgroundColor = color;
    return this;
  }

  public FilePickerDialog setTitleColor(String color) {
    titleColor = color;
    return this;
  }

  public FilePickerDialog setPathTextColor(String color) {
    pathTextColor = color;
    return this;
  }

  public FilePickerDialog setFileListTextColor(String color) {
    fileListTextColor = color;
    return this;
  }

  public FilePickerDialog setButtonColor(String color) {
    buttonColor = color;
    return this;
  }

  public FilePickerDialog enableIconBorders(boolean enabled) {
    useIconBorders = enabled;
    return this;
  }

  public FilePickerDialog setIcon(String extension, int resource) {

    if (extension != null) {
      customIcons.put(extension.toLowerCase(Locale.ROOT), Integer.valueOf(resource));
    }

    return this;
  }

  public FilePickerDialog setIcon(String extension, String color) {

    if (extension != null && color != null) {
      customIcons.put(extension.toLowerCase(Locale.ROOT), color);
    }

    return this;
  }

  public FilePickerDialog setDefaultFileIcon(int resource) {
    customIcons.put("file", Integer.valueOf(resource));
    return this;
  }

  public FilePickerDialog setDefaultFolderIcon(int resource) {
    customIcons.put("folder", Integer.valueOf(resource));
    return this;
  }

  public void show() {

    if (activity.isFinishing()
        || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
      return;
    }

    selectedFiles.clear();

    LinearLayout root = new LinearLayout(activity);
    root.setOrientation(LinearLayout.VERTICAL);

    root.addView(createHeaderView());

    listView = new ListView(activity);

    listView.setDivider(null);
    listView.setDividerHeight(0);
    listView.setSelector(new ColorDrawable(Color.TRANSPARENT));

    fileListAdapter = new CustomFileAdapter(activity, fileList, selectedFiles,
        multiSelectionEnabled, customIcons, fileListTextColor, useIconBorders, R.drawable.file);

    listView.setAdapter(fileListAdapter);

    listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

      @Override
      public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

        if (position < 0 || position >= fileList.size()) {
          return;
        }

        File file = fileList.get(position);

        // Parent directory
        if (file instanceof ParentEntry) {

          navigateTo(file.getParentFile());
          return;
        }

        // Directory navigation
        if (file.isDirectory()) {

          navigateTo(file);
          return;
        }

        // Multiple selection
        if (multiSelectionEnabled) {

          if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
          } else {
            selectedFiles.add(file);
          }

          fileListAdapter.notifyDataSetChanged();
          return;
        }

        // Single selection
        selectedFiles.clear();
        selectedFiles.add(file);

        finishSelection();
      }
    });

    root.addView(listView,
        new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    AlertDialog.Builder builder = new AlertDialog.Builder(activity);

    builder.setView(root);
    builder.setCancelable(true);

    if (multiSelectionEnabled) {

      builder.setPositiveButton("Select", null);
    }

    builder.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {

      @Override
      public void onClick(DialogInterface dialogInterface, int which) {

        dialogInterface.dismiss();
      }
    });

    dialog = builder.create();

    dialog.setOnShowListener(new DialogInterface.OnShowListener() {

      @Override
      public void onShow(DialogInterface dialogInterface) {

        if (dialog.getWindow() != null) {

          dialog.getWindow()
              .setBackgroundDrawable(new ColorDrawable(safeColor(backgroundColor, "#303030")));
        }

        dialog.getButton(DialogInterface.BUTTON_NEGATIVE)
            .setTextColor(safeColor(buttonColor, "#254898"));

        if (multiSelectionEnabled) {

          dialog.getButton(DialogInterface.BUTTON_POSITIVE)
              .setTextColor(safeColor(buttonColor, "#254898"));

          dialog.getButton(DialogInterface.BUTTON_POSITIVE)
              .setOnClickListener(new View.OnClickListener() {

                @Override
                public void onClick(View view) {

                  if (selectedFiles.isEmpty()) {

                    Toast.makeText(activity, "No files selected.", Toast.LENGTH_SHORT).show();

                    return;
                  }

                  finishSelection();
                }
              });
        }
      }
    });

    dialog.show();

    navigateTo(currentPath);
  }

  private View createHeaderView() {

    LinearLayout layout = new LinearLayout(activity);

    layout.setOrientation(LinearLayout.VERTICAL);

    layout.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

    TextView titleView = new TextView(activity);

    titleView.setText(title);
    titleView.setTextSize(20);
    titleView.setTypeface(Typeface.DEFAULT_BOLD);

    titleView.setTextColor(safeColor(titleColor, "#FFFFFF"));

    layout.addView(titleView);

    pathTextView = new TextView(activity);

    pathTextView.setTextSize(14);

    pathTextView.setEllipsize(TextUtils.TruncateAt.START);

    pathTextView.setSingleLine(true);

    pathTextView.setPadding(0, dpToPx(8), 0, 0);

    pathTextView.setTextColor(safeColor(pathTextColor, "#D0D0D0"));

    pathTextView.setOnLongClickListener(new View.OnLongClickListener() {

      @Override
      public boolean onLongClick(View view) {

        if (currentPath == null) {
          return true;
        }

        ClipboardManager clipboard = (ClipboardManager) activity
            .getSystemService(Activity.CLIPBOARD_SERVICE);

        if (clipboard != null) {

          ClipData clip = ClipData.newPlainText("FilePath", currentPath.getAbsolutePath());

          clipboard.setPrimaryClip(clip);

          Toast.makeText(activity, "Path copied to clipboard", Toast.LENGTH_SHORT).show();
        }

        return true;
      }
    });

    layout.addView(pathTextView);

    return layout;
  }

  private void navigateTo(File directory) {

    if (directory == null || !directory.exists() || !directory.isDirectory()
        || !directory.canRead()) {

      Toast.makeText(activity, "Cannot read this directory.", Toast.LENGTH_SHORT).show();

      return;
    }

    File[] files;

    try {
      files = directory.listFiles();
    } catch (SecurityException e) {
      files = null;
    }

    if (files == null) {

      Toast.makeText(activity, "Cannot access this directory.", Toast.LENGTH_SHORT).show();

      return;
    }

    currentPath = directory;

    pathTextView.setText(directory.getAbsolutePath());

    fileList.clear();

    Arrays.sort(files, new Comparator<File>() {

      @Override
      public int compare(File first, File second) {

        if (first.isDirectory() && !second.isDirectory()) {
          return -1;
        }

        if (!first.isDirectory() && second.isDirectory()) {
          return 1;
        }

        return first.getName().compareToIgnoreCase(second.getName());
      }
    });

    // Add parent directory
    File parent = directory.getParentFile();

    if (parent != null) {

      fileList.add(new ParentEntry(directory.getAbsolutePath(), parent));
    }

    for (File file : files) {

      try {

        if (file.isHidden()) {
          continue;
        }

        if (file.isDirectory()) {

          fileList.add(file);

        } else if (file.isFile()) {

          String extension = getFileExtension(file);

          if (!excludedExtensions.contains(extension)) {
            fileList.add(file);
          }
        }

      } catch (SecurityException ignored) {
        // Skip inaccessible files.
      }
    }

    fileListAdapter.notifyDataSetChanged();

    listView.setSelection(0);
  }

  private void finishSelection() {

    if (callback != null) {

      callback.onFilesSelected(new ArrayList<>(selectedFiles));
    }

    if (dialog != null && dialog.isShowing()) {
      dialog.dismiss();
    }
  }

  private String getFileExtension(File file) {

    String name = file.getName();

    if (name.equals("..")) {
      return "";
    }

    int index = name.lastIndexOf('.');

    if (index <= 0 || index == name.length() - 1) {
      return "";
    }

    return name.substring(index + 1).toLowerCase(Locale.ROOT);
  }

  private int safeColor(String color, String fallback) {

    try {
      return Color.parseColor(color);
    } catch (Exception e) {
      return Color.parseColor(fallback);
    }
  }

  private int dpToPx(int dp) {

    return (int) (dp * activity.getResources().getDisplayMetrics().density);
  }

  // Special entry for navigating to parent directory
  private static class ParentEntry extends File {

    private final File parent;

    ParentEntry(String path, File parent) {
      super(path);
      this.parent = parent;
    }

    @Override
    public String getName() {
      return "..";
    }

    @Override
    public boolean isDirectory() {
      return true;
    }

    @Override
    public File getParentFile() {
      return parent;
    }
  }
}
