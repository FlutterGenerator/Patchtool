
package com.patchtool;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.method.ScrollingMovementMethod;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class MainActivity extends Activity {

    private static final int FILE_SELECT_CODE_SO = 1001;
    private static final int FILE_SELECT_CODE_INI = 1002;
    private static final int FILE_SELECT_CODE_RESTORE = 1003;

    private EditText edtAsm;
    private EditText edtHex;
    private EditText edtOffset;

    private Spinner spinnerArch;

    private TextView txtLog;
    private TextView txtSelectedFile;

    private String selectedSoPath = "";
    private String originalFileName = "";

    private int currentPickerRequestCode;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private volatile boolean isProcessing = false;

    private Button btnSelect;
    private Button btnPatch;
    private Button btnDump;

    // ---- JNI: libmodmenu.so (symbols are resolved by name: Java_com_patchtool_MainActivity_*) ----

    private static final String ACTION_PATCH_SINGLE = "PATCH_SINGLE";
    private static final String ACTION_PATCH_FROM_INI = "PATCH_FROM_INI";
    private static final String ACTION_RESTORE_FROM_INI = "RESTORE_FROM_INI";

    private native String getElfArchitecture(String path);

    private native String getElfInfo(String path);

    /**
     * Patches / restores an ELF file. Returns a human readable log.
     *
     * @param action     PATCH_SINGLE, PATCH_FROM_INI or RESTORE_FROM_INI
     * @param inputFile  file to patch (for RESTORE_FROM_INI: the patched file)
     * @param outputFile where the resulting file is written
     * @param iniFile    patches .ini (PATCH_FROM_INI) or backup .ini (RESTORE_FROM_INI); "" for PATCH_SINGLE
     * @param offsetHex  patch offset in hex, e.g. "1A2B" or "0x1A2B" (PATCH_SINGLE only)
     * @param hexPayload bytes to write as hex, e.g. "90 90 CC" (used when asmPayload is empty)
     * @param asmPayload assembly to write (Keystone); takes priority over hexPayload
     * @param arch       arm64, arm32, x86 or x86_64
     */
    private native String processFile(
            String action,
            String inputFile,
            String outputFile,
            String iniFile,
            String offsetHex,
            String hexPayload,
            String asmPayload,
            String arch
    );

    /** Dumps functions/symbols of a library into a C++ style text file. Returns a log. */
    private native String dumpLibraryFile(String inputFile, String outputFile);

    static {
        System.loadLibrary("modmenu");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initializeUI();
        checkPermissionsAndHandleSharedIntent();
    }

    private void initializeUI() {

        txtSelectedFile = findViewById(R.id.txtSelectedFile);
        txtLog = findViewById(R.id.txtLog);

        spinnerArch = findViewById(R.id.spinnerArch);

        edtOffset = findViewById(R.id.edtOffset);
        edtHex = findViewById(R.id.edtHex);
        edtAsm = findViewById(R.id.edtAsm);

        btnSelect = findViewById(R.id.btnSelectFile);
        btnPatch = findViewById(R.id.btnPatch);
        btnDump = findViewById(R.id.btnDump);

        txtLog.setMovementMethod(new ScrollingMovementMethod());
        txtLog.setTypeface(Typeface.MONOSPACE);

        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(
                        this,
                        R.array.architectures,
                        android.R.layout.simple_spinner_item
                );

        adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
        );

        spinnerArch.setAdapter(adapter);

        btnSelect.setOnClickListener(v ->
                openCustomFilePicker(FILE_SELECT_CODE_SO)
        );

        btnPatch.setOnClickListener(v -> onPatchClicked());

        btnDump.setOnClickListener(v -> onDumpClicked());
    }

    // ------------------------------------------------------------------
    // Native operations
    // ------------------------------------------------------------------

    private interface NativeCall {
        String run();
    }

    /**
     * Runs a JNI call off the UI thread, locks the buttons meanwhile and prints the
     * returned log. Throwable (not Exception) is caught on purpose: a missing ABI
     * library surfaces as UnsatisfiedLinkError.
     */
    private void runNative(String title, NativeCall call) {

        if (isProcessing) {
            Toast.makeText(
                    this,
                    "Another operation is still running.",
                    Toast.LENGTH_SHORT
            ).show();
            return;
        }

        isProcessing = true;
        setButtonsEnabled(false);
        logResult("[*] " + title + "...\n");

        new Thread(() -> {

            String result;

            try {
                result = call.run();
            } catch (Throwable t) {
                result = "[-] Native call failed: " + t;
            }

            final String log =
                    (result == null || result.isEmpty())
                            ? "[-] Native code returned no result."
                            : result;

            handler.post(() -> {
                isProcessing = false;
                setButtonsEnabled(true);
                logResult(log + "\n");
            });

        }).start();
    }

    private void setButtonsEnabled(boolean enabled) {
        btnSelect.setEnabled(enabled);
        btnPatch.setEnabled(enabled);
        btnDump.setEnabled(enabled);
    }

    private boolean ensureFileSelected() {

        if (selectedSoPath.isEmpty() || !new File(selectedSoPath).isFile()) {
            logError("Select a .so file first.");
            return false;
        }

        return true;
    }

    /** Returns the architecture chosen in the spinner, or null if it is not usable. */
    private String getSelectedArch() {

        Object item = spinnerArch.getSelectedItem();
        String arch = item == null ? "" : item.toString();

        if (arch.isEmpty() || "unsupported".equals(arch)) {
            logError("Unsupported architecture selected.");
            return null;
        }

        return arch;
    }

    private String baseName() {

        String name = originalFileName.isEmpty()
                ? "output.so"
                : new File(originalFileName).getName();

        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(0, dot) : name;
    }

    private String extension() {

        String name = originalFileName.isEmpty()
                ? "output.so"
                : new File(originalFileName).getName();

        int dot = name.lastIndexOf('.');

        return dot > 0 ? name.substring(dot) : "";
    }

    private void onPatchClicked() {

        if (!ensureFileSelected()) {
            return;
        }

        final String arch = getSelectedArch();

        if (arch == null) {
            return;
        }

        final String offset = edtOffset.getText().toString().trim();
        final String hex = edtHex.getText().toString().trim();
        final String asm = edtAsm.getText().toString().trim();

        if (!offset.matches("(?i)(0x)?[0-9a-f]{1,16}")) {
            logError("Offset must be a hex number, e.g. 1A2B or 0x1A2B.");
            return;
        }

        if (hex.isEmpty() && asm.isEmpty()) {
            logError("Enter a HEX or an ASM payload.");
            return;
        }

        final String output = getOutputFilePath("patched_" + originalFileName);

        if (output.isEmpty()) {
            return;
        }

        final String input = selectedSoPath;

        runNative(
                "Patching " + originalFileName,
                () -> processFile(
                        ACTION_PATCH_SINGLE,
                        input,
                        output,
                        "",
                        offset,
                        hex,
                        asm,
                        arch
                )
        );
    }

    private void onDumpClicked() {

        if (!ensureFileSelected()) {
            return;
        }

        final String output = getOutputFilePath("dumped_" + originalFileName + ".cpp");

        if (output.isEmpty()) {
            return;
        }

        final String input = selectedSoPath;

        runNative(
                "Dumping " + originalFileName,
                () -> dumpLibraryFile(input, output)
        );
    }

    private void applyIniPatches(String iniPath) {

        if (!ensureFileSelected()) {
            return;
        }

        final String arch = getSelectedArch();

        if (arch == null) {
            return;
        }

        final String output = getOutputFilePath(baseName() + "_patched" + extension());

        if (output.isEmpty()) {
            return;
        }

        // The native code writes the backup next to the INPUT file (<input>.bak.ini).
        // A file received through a share/VIEW intent lives in the app cache, where the
        // user can't find the backup, so work on a copy in the output folder instead.
        final String input = stageInputForBackup(selectedSoPath);

        if (input == null) {
            return;
        }

        runNative(
                "Applying patches from " + new File(iniPath).getName(),
                () -> processFile(
                        ACTION_PATCH_FROM_INI,
                        input,
                        output,
                        iniPath,
                        "0",
                        "",
                        "",
                        arch
                )
        );
    }

    private void restoreFromBackup(String backupIniPath) {

        if (!ensureFileSelected()) {
            return;
        }

        final String arch = getSelectedArch();

        if (arch == null) {
            return;
        }

        final String output = getOutputFilePath(baseName() + "_restored" + extension());

        if (output.isEmpty()) {
            return;
        }

        final String input = selectedSoPath;

        runNative(
                "Restoring " + originalFileName,
                () -> processFile(
                        ACTION_RESTORE_FROM_INI,
                        input,
                        output,
                        backupIniPath,
                        "0",
                        "",
                        "",
                        arch
                )
        );
    }

    /** Copies cache-dir files into the output folder; other files are used in place. */
    private String stageInputForBackup(String path) {

        try {
            String cache = getCacheDir().getCanonicalPath();

            if (!new File(path).getCanonicalPath().startsWith(cache)) {
                return path;
            }

            String target = getOutputFilePath(baseName() + extension());

            if (target.isEmpty()) {
                return null;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try (InputStream in = Files.newInputStream(Paths.get(path));
                     FileOutputStream out = new FileOutputStream(target)) {

                    byte[] buffer = new byte[8192];
                    int count;

                    while ((count = in.read(buffer)) != -1) {
                        out.write(buffer, 0, count);
                    }
                }
            }

            return target;

        } catch (IOException e) {
            logError("Cannot prepare input file: " + e.getMessage());
            return null;
        }
    }

    private void openCustomFilePicker(int requestCode) {

        currentPickerRequestCode = requestCode;

        FileManagerPermissions.checkAndRequestAllFilesAccess(
                this,
                new FileManagerPermissions.PermissionCallback() {

                    @Override
                    public void onPermissionGranted() {
                        showPickerDialog();
                    }

                    @Override
                    public void onPermissionDenied() {
                        logError("File access permission is required.");
                    }
                }
        );
    }

    private void showPickerDialog() {

        String title = "Select File";
        String initialPath = null;

        if (!selectedSoPath.isEmpty()) {
            File file = new File(selectedSoPath);

            if (file.exists()) {
                initialPath = file.getParent();
            }
        }

        if (currentPickerRequestCode == FILE_SELECT_CODE_SO) {
            title = "Select Library (.so)";
        } else if (currentPickerRequestCode == FILE_SELECT_CODE_INI) {
            title = "Select Patches File (.ini)";
        } else if (currentPickerRequestCode == FILE_SELECT_CODE_RESTORE) {
            title = "Select Backup File";
        }

        new FilePickerDialog(this)
                .setTitle(title)
                .setInitialPath(initialPath)
                .enableIconBorders(true)
                .setCallback(files -> {

                    if (files == null || files.isEmpty()) {
                        return;
                    }

                    handleFileSelectionResult(
                            files.get(0).getAbsolutePath()
                    );
                })
                .show();
    }

    private void handleFileSelectionResult(String path) {

        if (path == null || path.isEmpty()) {
            logError("Invalid file path.");
            return;
        }

        if (currentPickerRequestCode == FILE_SELECT_CODE_SO) {
            File file = new File(path);

            if (!file.isFile() || !file.canRead()) {
                logError("Cannot read selected file.");
                return;
            }

            handleIncomingFile(path, file.getName());

        } else if (currentPickerRequestCode == FILE_SELECT_CODE_INI) {
            applyIniPatches(path);

        } else if (currentPickerRequestCode == FILE_SELECT_CODE_RESTORE) {
            restoreFromBackup(path);

        } else {
            logResult("Selected file: " + path);
        }
    }

    @SuppressLint("SetTextI18n")
    private void handleIncomingFile(String path, String name) {

        selectedSoPath = path;
        originalFileName = name;

        txtSelectedFile.setText("File: " + name);

        autoDetectArchitecture(path);
        showElfInfoDialog(path);
    }

    private void autoDetectArchitecture(String path) {

        new Thread(() -> {

            String architecture;

            try {
                architecture = getElfArchitecture(path);
            } catch (Throwable e) {
                architecture = null;
            }

            final String result = architecture;

            handler.post(() -> {

                if (result == null || result.isEmpty()) {
                    logError("Could not detect ELF architecture.");
                    return;
                }

                @SuppressWarnings("unchecked")
                ArrayAdapter<CharSequence> adapter =
                        (ArrayAdapter<CharSequence>) spinnerArch.getAdapter();

                int position = adapter.getPosition(result);

                if (position >= 0) {
                    spinnerArch.setSelection(position);

                    Toast.makeText(
                            MainActivity.this,
                            "Detected Architecture: " + result,
                            Toast.LENGTH_SHORT
                    ).show();
                } else {
                    logError("Unsupported architecture: " + result);
                }
            });

        }).start();
    }

    private void showElfInfoDialog(String path) {

        new Thread(() -> {

            String info;

            try {
                info = getElfInfo(path);
            } catch (Throwable e) {
                info = "ELF information error: " + e.getMessage();
            }

            final String result = info;

            handler.post(() -> {

                View view = LayoutInflater.from(this).inflate(
                        R.layout.dialog_scrollable_info,
                        null
                );

                TextView text =
                        view.findViewById(R.id.txtScrollableInfo);

                text.setText(result == null ? "No information." : result);

                AlertDialog dialog = new AlertDialog.Builder(this)
                        .setTitle("ELF Summary")
                        .setView(view)
                        .setPositiveButton("OK", null)
                        .create();

                dialog.show();

                if (dialog.getWindow() != null) {
                    dialog.getWindow().setLayout(
                            (int) (getResources()
                                    .getDisplayMetrics().widthPixels * 0.9),
                            (int) (getResources()
                                    .getDisplayMetrics().heightPixels * 0.75)
                    );
                }
            });

        }).start();
    }

    private void checkPermissionsAndHandleSharedIntent() {

        Intent intent = getIntent();

        String action = intent.getAction();

        if (Intent.ACTION_VIEW.equals(action)
                || Intent.ACTION_SEND.equals(action)) {

            FileManagerPermissions.checkAndRequestAllFilesAccess(
                    this,
                    new FileManagerPermissions.PermissionCallback() {

                        @Override
                        public void onPermissionGranted() {
                            handleSharedFileIntent();
                        }

                        @Override
                        public void onPermissionDenied() {
                            logError("Permission required.");
                        }
                    }
            );
        }
    }

    private void handleSharedFileIntent() {

        Intent intent = getIntent();

        Uri uri = intent.getData();

        if (uri == null && intent.getClipData() != null) {

            ClipData clip = intent.getClipData();

            if (clip.getItemCount() > 0) {
                uri = clip.getItemAt(0).getUri();
            }
        }

        if (uri == null) {
            logError("No shared file received.");
            return;
        }

        final Uri fileUri = uri;

        new Thread(() -> {

            String name = getFileNameFromUri(fileUri);
            String path = createTempFileFromUri(fileUri);

            handler.post(() -> {

                if (path == null) {
                    logError("Could not open shared file.");
                    return;
                }

                logResult("[*] File received via Intent...");
                handleIncomingFile(path, name);
            });

        }).start();
    }

    private String getFileNameFromUri(Uri uri) {

        if (uri == null) {
            return "shared_file.so";
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {

            try (Cursor cursor = getContentResolver().query(
                    uri,
                    null,
                    null,
                    null,
                    null
            )) {

                if (cursor != null && cursor.moveToFirst()) {

                    int index = cursor.getColumnIndex(
                            "_display_name"
                    );

                    if (index >= 0) {
                        String name = cursor.getString(index);

                        if (name != null && !name.isEmpty()) {
                            return name;
                        }
                    }
                }

            } catch (Exception e) {
                logError("Filename error: " + e.getMessage());
            }
        }

        String path = uri.getLastPathSegment();

        if (path == null || path.isEmpty()) {
            return "shared_file.so";
        }

        return path;
    }

    private String createTempFileFromUri(Uri uri) {

        File tempFile = null;

        try {

            tempFile = File.createTempFile(
                    "shared_",
                    ".tmp",
                    getCacheDir()
            );

            try (InputStream input =
                         getContentResolver().openInputStream(uri);
                 FileOutputStream output =
                         new FileOutputStream(tempFile)) {

                if (input == null) {
                    return null;
                }

                byte[] buffer = new byte[8192];

                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }

                output.flush();
            }

            return tempFile.getAbsolutePath();

        } catch (IOException e) {

            if (tempFile != null) {
                tempFile.delete();
            }

            logError("Temporary file error: " + e.getMessage());

            return null;
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode ==
                FileManagerPermissions.REQUEST_CODE_MANAGE_FILES) {

            if (FileManagerPermissions.isAllFilesAccessGranted()) {
                showPickerDialog();
            } else {
                logError("File permission denied.");
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        getMenuInflater().inflate(R.menu.main_menu, menu);

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {

        int id = item.getItemId();

        if (id == R.id.action_import_ini) {
            if (ensureFileSelected()) {
                openCustomFilePicker(FILE_SELECT_CODE_INI);
            }
            return true;
        }

        if (id == R.id.action_restore) {
            if (ensureFileSelected()) {
                openCustomFilePicker(FILE_SELECT_CODE_RESTORE);
            }
            return true;
        }

        if (id == R.id.action_set_output) {

            Toast.makeText(
                    this,
                    "Output folder: /PatchTool_Files/",
                    Toast.LENGTH_LONG
            ).show();

            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private String getOutputFilePath(String name) {

        File directory = new File(
                Environment.getExternalStorageDirectory(),
                "PatchTool_Files"
        );

        if (!directory.exists() && !directory.mkdirs()) {
            logError("Cannot create output directory.");
            return "";
        }

        return new File(directory, name).getAbsolutePath();
    }

    private void logError(String message) {

        String html = "<br><font color='#F85149'><b>[-] ERROR:</b> "
                + escapeHtml(message)
                + "</font>";

        appendHtml(html);
    }

    private void logResult(String message) {

        if (message == null) {
            return;
        }

        String html = escapeHtml(message)
                .replace("[+]", "<font color='#3FB950'>[+]</font>")
                .replace("[-]", "<font color='#F85149'>[-]</font>")
                .replace("[*]", "<font color='#58A6FF'>[*]</font>")
                .replace("\n", "<br>");

        appendHtml(html);
    }

    private String escapeHtml(String value) {

        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void appendHtml(String html) {

        if (Build.VERSION.SDK_INT >= 24) {
            txtLog.append(Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY));
        } else {
            txtLog.append(Html.fromHtml(html));
        }
    }
}
