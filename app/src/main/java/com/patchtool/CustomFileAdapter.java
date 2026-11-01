package com.patchtool;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class CustomFileAdapter extends ArrayAdapter<File> {

    private final Context context;
    private final HashMap<String, Object> customIcons;
    private final int defaultFileIconRes;
    private final String fileListTextColor;
    private final boolean multiSelectionEnabled;
    private final List<File> selectedFiles;
    private final boolean useIconBorders;

    private static class ViewHolder {
        CheckBox checkBox;
        TextView fileName;
        ImageView icon;
    }

    public CustomFileAdapter(
            Context context,
            List<File> list,
            List<File> selectedFiles,
            boolean multiSelectionEnabled,
            HashMap<String, Object> customIcons,
            String fileListTextColor,
            boolean useIconBorders,
            int defaultFileIconRes) {

        super(context, -1, list);

        this.context = context;
        this.selectedFiles = selectedFiles;
        this.multiSelectionEnabled = multiSelectionEnabled;
        this.customIcons = customIcons != null
                ? customIcons : new HashMap<String, Object>();
        this.fileListTextColor = fileListTextColor;
        this.useIconBorders = useIconBorders;
        this.defaultFileIconRes = defaultFileIconRes;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {

        ViewHolder holder;

        if (convertView == null) {
            holder = new ViewHolder();
            convertView = createRowView(holder);
            convertView.setTag(holder);
        } else {
            holder = (ViewHolder) convertView.getTag();
        }

        populateRowView(holder, getItem(position));

        return convertView;
    }

    private View createRowView(ViewHolder holder) {

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setPadding(
                dpToPx(8),
                dpToPx(12),
                dpToPx(8),
                dpToPx(12)
        );

        layout.setGravity(16);
        layout.setDescendantFocusability(393216);

        holder.icon = new ImageView(context);

        LinearLayout.LayoutParams iconParams =
                new LinearLayout.LayoutParams(
                        dpToPx(40),
                        dpToPx(40)
                );

        iconParams.setMargins(0, 0, dpToPx(16), 0);
        layout.addView(holder.icon, iconParams);

        holder.fileName = new TextView(context);
        holder.fileName.setTextSize(16);

        try {
            holder.fileName.setTextColor(
                    Color.parseColor(fileListTextColor)
            );
        } catch (Exception e) {
            holder.fileName.setTextColor(Color.BLACK);
        }

        layout.addView(
                holder.fileName,
                new LinearLayout.LayoutParams(0, -2, 1f)
        );

        holder.checkBox = new CheckBox(context);

        layout.addView(
                holder.checkBox,
                new LinearLayout.LayoutParams(-2, -2)
        );

        return layout;
    }

    private void populateRowView(ViewHolder holder, File file) {

        if (file == null) {
            holder.fileName.setText("");
            holder.icon.setImageDrawable(null);
            holder.checkBox.setVisibility(View.GONE);
            return;
        }

        holder.fileName.setText(file.getName());

        setIcon(holder.icon, file);

        holder.checkBox.setOnCheckedChangeListener(null);

        if (multiSelectionEnabled && !file.getName().equals("..")) {

            holder.checkBox.setVisibility(View.VISIBLE);

            holder.checkBox.setChecked(
                    selectedFiles != null &&
                            selectedFiles.contains(file)
            );

            holder.checkBox.setOnCheckedChangeListener(
                    new CompoundButton.OnCheckedChangeListener() {

                        @Override
                        public void onCheckedChanged(
                                CompoundButton button,
                                boolean isChecked) {

                            if (selectedFiles == null) {
                                return;
                            }

                            if (isChecked) {
                                if (!selectedFiles.contains(file)) {
                                    selectedFiles.add(file);
                                }
                            } else {
                                selectedFiles.remove(file);
                            }
                        }
                    });

        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.checkBox.setChecked(false);
        }
    }

    private void setIcon(ImageView imageView, File file) {

        String extension = getFileExtension(file);
        Object icon;

        if (file.isDirectory()) {

            icon = customIcons.get("folder");

            if (icon == null) {
                icon = Integer.valueOf(R.drawable.folder);
            }

        } else if (customIcons.containsKey(extension)) {

            icon = customIcons.get(extension);

        } else {

            icon = customIcons.get("file");

            if (icon == null) {
                icon = Integer.valueOf(R.drawable.file);
            }
        }

        applyIcon(imageView, icon, file.isDirectory());
    }

    private void applyIcon(
            ImageView imageView,
            Object icon,
            boolean isDirectory) {

        Drawable drawable = null;

        if (icon instanceof Integer) {

            drawable = ContextCompat.getDrawable(
                    context,
                    ((Integer) icon).intValue()
            );

        } else if (icon instanceof String) {

            int resource = isDirectory
                    ? R.drawable.folder
                    : defaultFileIconRes;

            drawable = ContextCompat.getDrawable(context, resource);

            if (drawable != null) {

                drawable = DrawableCompat.wrap(drawable).mutate();

                try {
                    DrawableCompat.setTint(
                            drawable,
                            Color.parseColor((String) icon)
                    );
                } catch (Exception e) {
                    // Keep original drawable color.
                }
            }
        }

        if (drawable == null) {
            imageView.setImageDrawable(null);
            return;
        }

        if (useIconBorders) {
            imageView.setImageDrawable(
                    createBorderedIcon(drawable)
            );
        } else {
            imageView.setImageDrawable(drawable);
        }
    }

    private Drawable createBorderedIcon(Drawable drawable) {

        GradientDrawable background = new GradientDrawable();

        background.setShape(GradientDrawable.RECTANGLE);
        background.setCornerRadius(dpToPx(12));
        background.setStroke(
                dpToPx(2),
                Color.parseColor("#44FFFFFF")
        );

        return new LayerDrawable(new Drawable[]{
                background,
                new InsetDrawable(drawable, dpToPx(8))
        });
    }

    private String getFileExtension(File file) {

        String name = file.getName();

        if (name.equals("..")) {
            return "";
        }

        int index = name.lastIndexOf(".");

        if (index <= 0 || index == name.length() - 1) {
            return "";
        }

        return name.substring(index + 1)
                .toLowerCase(Locale.ROOT);
    }

    private int dpToPx(int dp) {

        return (int) (
                dp * context.getResources()
                        .getDisplayMetrics().density
        );
    }
}
