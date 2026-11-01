package com.patchtool;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;

public class GlobalApplication extends Application {

    private static final String TAG = "GlobalApplication";

    @Override
    public void onCreate() {
        super.onCreate();

        CrashHandler.getInstance().registerGlobal(this);
    }

    // Write InputStream to OutputStream
    public static void write(
            InputStream input,
            OutputStream output
    ) throws IOException {

        if (input == null || output == null) {
            throw new IllegalArgumentException(
                    "InputStream and OutputStream cannot be null"
            );
        }

        byte[] buffer = new byte[8192];

        int length;

        while ((length = input.read(buffer)) != -1) {
            output.write(buffer, 0, length);
        }
    }

    // Write byte array to file
    public static void write(
            File file,
            byte[] data
    ) throws IOException {

        if (file == null || data == null) {
            throw new IllegalArgumentException(
                    "File and data cannot be null"
            );
        }

        File parent = file.getParentFile();

        if (parent != null && !parent.exists()) {

            if (!parent.mkdirs() && !parent.isDirectory()) {
                throw new IOException(
                        "Cannot create directory: " + parent
                );
            }
        }

        ByteArrayInputStreamWrapper input =
                new ByteArrayInputStreamWrapper(data);

        FileOutputStream output = null;

        try {

            output = new FileOutputStream(file);

            write(input, output);

            output.flush();

        } finally {

            closeIO(input, output);
        }
    }

    // Read InputStream as UTF-8 String
    public static String toString(
            InputStream input
    ) throws IOException {

        if (input == null) {
            return "";
        }

        ByteArrayOutputStream output =
                new ByteArrayOutputStream();

        try {

            write(input, output);

            return output.toString("UTF-8");

        } finally {

            closeIO(input, output);
        }
    }

    // Close streams safely
    public static void closeIO(Closeable... streams) {

        if (streams == null) {
            return;
        }

        for (Closeable stream : streams) {

            if (stream != null) {

                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    // Simple byte array InputStream wrapper
    private static class ByteArrayInputStreamWrapper
            extends java.io.ByteArrayInputStream {

        ByteArrayInputStreamWrapper(byte[] data) {
            super(data);
        }
    }

    // =====================================================
    // CRASH HANDLER
    // =====================================================

    public static class CrashHandler {

        public static final Thread.UncaughtExceptionHandler
                DEFAULT_UNCAUGHT_EXCEPTION_HANDLER =
                Thread.getDefaultUncaughtExceptionHandler();

        private static CrashHandler instance;

        private Thread.UncaughtExceptionHandler previousHandler;

        private CrashHandler() {
        }

        public static synchronized CrashHandler getInstance() {

            if (instance == null) {
                instance = new CrashHandler();
            }

            return instance;
        }

        public void registerGlobal(Context context) {
            registerGlobal(context, null);
        }

        public void registerGlobal(
                Context context,
                String crashDirectory
        ) {

            Context appContext =
                    context.getApplicationContext();

            previousHandler =
                    Thread.getDefaultUncaughtExceptionHandler();

            File directory;

            if (!TextUtils.isEmpty(crashDirectory)) {

                directory = new File(crashDirectory);

            } else {

                File cache = appContext.getExternalCacheDir();

                if (cache == null) {
                    cache = appContext.getCacheDir();
                }

                directory = new File(cache, "crash");
            }

            Thread.setDefaultUncaughtExceptionHandler(
                    new UncaughtExceptionHandlerImpl(
                            appContext,
                            directory,
                            previousHandler
                    )
            );
        }

        public void unregister() {

            if (previousHandler != null) {

                Thread.setDefaultUncaughtExceptionHandler(
                        previousHandler
                );
            } else {

                Thread.setDefaultUncaughtExceptionHandler(null);
            }
        }

        // =================================================
        // UNCAUGHT EXCEPTION HANDLER
        // =================================================

        private static class UncaughtExceptionHandlerImpl
                implements Thread.UncaughtExceptionHandler {

            private final Context context;
            private final File crashDirectory;
            private final Thread.UncaughtExceptionHandler defaultHandler;

            UncaughtExceptionHandlerImpl(
                    Context context,
                    File crashDirectory,
                    Thread.UncaughtExceptionHandler defaultHandler
            ) {

                this.context = context;
                this.crashDirectory = crashDirectory;
                this.defaultHandler = defaultHandler;
            }

            @Override
            public void uncaughtException(
                    Thread thread,
                    Throwable error
            ) {

                try {

                    String crashLog = buildLog(error);

                    writeLog(crashLog);

                    Log.e(
                            TAG,
                            "Application crashed",
                            error
                    );

                } catch (Throwable loggingError) {

                    Log.e(
                            TAG,
                            "Failed to save crash log",
                            loggingError
                    );
                }

                // Let Android handle process termination normally.
                if (defaultHandler != null) {

                    defaultHandler.uncaughtException(
                            thread,
                            error
                    );

                } else {

                    Process.killProcess(Process.myPid());
                    System.exit(1);
                }
            }

            private String buildLog(Throwable error) {

                String time = new SimpleDateFormat(
                        "yyyy_MM_dd-HH_mm_ss",
                        Locale.US
                ).format(new Date());

                String versionName = "Unknown";
                long versionCode = 0;

                try {

                    PackageInfo info =
                            context.getPackageManager()
                                    .getPackageInfo(
                                            context.getPackageName(),
                                            0
                                    );

                    versionName = info.versionName;

                    if (Build.VERSION.SDK_INT >= 28) {
                        versionCode = info.getLongVersionCode();
                    } else {
                        versionCode = info.versionCode;
                    }

                } catch (Exception ignored) {
                }

                LinkedHashMap<String, String> info =
                        new LinkedHashMap<>();

                info.put("Time Of Crash", time);

                info.put(
                        "Device",
                        Build.MANUFACTURER + ", " + Build.MODEL
                );

                info.put(
                        "Android Version",
                        Build.VERSION.RELEASE +
                                " (" + Build.VERSION.SDK_INT + ")"
                );

                info.put(
                        "App Version",
                        versionName + " (" + versionCode + ")"
                );

                info.put("Kernel", getKernel());

                info.put(
                        "Support ABIs",
                        Build.VERSION.SDK_INT >= 21
                                ? Arrays.toString(Build.SUPPORTED_ABIS)
                                : Build.CPU_ABI
                );

                info.put("Fingerprint", Build.FINGERPRINT);

                StringBuilder result = new StringBuilder();

                for (String key : info.keySet()) {

                    result.append(key)
                            .append(" :    ")
                            .append(info.get(key))
                            .append("\n");
                }

                result.append("\n");
                result.append(Log.getStackTraceString(error));

                return result.toString();
            }

            private void writeLog(String log) throws IOException {

                String filename = "crash_" +
                        new SimpleDateFormat(
                                "yyyy_MM_dd-HH_mm_ss",
                                Locale.US
                        ).format(new Date()) +
                        ".txt";

                File file = new File(crashDirectory, filename);

                GlobalApplication.write(
                        file,
                        log.getBytes("UTF-8")
                );
            }

            private static String getKernel() {

                FileInputStream input = null;

                try {

                    input = new FileInputStream("/proc/version");

                    return GlobalApplication.toString(input).trim();

                } catch (Exception e) {

                    return "Unknown";

                } finally {

                    GlobalApplication.closeIO(input);
                }
            }
        }
    }

    // =====================================================
    // CRASH ACTIVITY
    // =====================================================

    public static final class CrashActivity extends Activity {

        private String crashLog;

        @Override
        protected void onCreate(Bundle savedInstanceState) {

            super.onCreate(savedInstanceState);

            setTheme(android.R.style.Theme_DeviceDefault);

            setTitle("App Crash");

            crashLog = getIntent().getStringExtra(
                    Intent.EXTRA_TEXT
            );

            if (crashLog == null) {
                crashLog = "No crash information available.";
            }

            ScrollView scrollView = new ScrollView(this);

            scrollView.setFillViewport(true);

            HorizontalScrollView horizontalScrollView =
                    new HorizontalScrollView(this);

            TextView textView = new TextView(this);

            int padding = dp2px(16);

            textView.setPadding(
                    padding,
                    padding,
                    padding,
                    padding
            );

            textView.setText(crashLog);

            textView.setTextIsSelectable(true);

            textView.setTypeface(Typeface.MONOSPACE);

            textView.setTextSize(13);

            horizontalScrollView.addView(textView);

            scrollView.addView(
                    horizontalScrollView,
                    new ScrollView.LayoutParams(
                            -1,
                            -1
                    )
            );

            setContentView(scrollView);
        }

        private void restart() {

            Intent launchIntent =
                    getPackageManager().getLaunchIntentForPackage(
                            getPackageName()
                    );

            if (launchIntent != null) {

                launchIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TASK
                );

                startActivity(launchIntent);
            }

            finish();
        }

        private static int dp2px(float dp) {

            return (int) (
                    dp * Resources.getSystem()
                            .getDisplayMetrics().density + 0.5f
            );
        }

        @Override
        public boolean onCreateOptionsMenu(Menu menu) {

            menu.add(
                    0,
                    android.R.id.copy,
                    0,
                    android.R.string.copy
            ).setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS
            );

            menu.add(
                    0,
                    1001,
                    1,
                    "Restart"
            ).setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_NEVER
            );

            return true;
        }

        @Override
        public boolean onOptionsItemSelected(MenuItem item) {

            if (item.getItemId() == android.R.id.copy) {

                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(
                                CLIPBOARD_SERVICE
                        );

                if (clipboard != null) {

                    clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                    getPackageName(),
                                    crashLog
                            )
                    );
                }

                return true;
            }

            if (item.getItemId() == 1001) {

                restart();

                return true;
            }

            return super.onOptionsItemSelected(item);
        }

        @Override
        public void onBackPressed() {
            restart();
        }
    }
}
