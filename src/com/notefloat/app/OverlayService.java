package com.notefloat.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class OverlayService extends Service {

    public static final String ACTION_TOGGLE_TAB = "com.notefloat.app.TOGGLE_TAB";
    public static final String ACTION_STOP = "com.notefloat.app.STOP";
    public static final String ACTION_NEW_NOTE = "com.notefloat.app.NEW_NOTE";

    public static volatile boolean isRunning = false;

    private static final String CHANNEL_ID = "notefloat";
    private static final int NOTIF_ID = 4201;
    private static final int ACCENT = 0xFF4C5FD5;
    private static final int PANEL_BG = 0xFF16181D;
    private static final int PANEL_TEXT = 0xFFF2F4F8;
    private static final int PANEL_HINT = 0xFF8D97A8;

    private WindowManager wm;
    private NoteStore store;

    private View tabView;
    private WindowManager.LayoutParams tabParams;
    private View panelView;
    private EditText panelInput;
    private boolean panelShown;

    private int tabSize;
    private int screenW;
    private int screenH;

    private float downRawX;
    private float downRawY;
    private int downX;
    private int downY;
    private boolean dragging;
    private long downTime;
    private int touchSlop;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        store = NoteStore.get(this);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        touchSlop = (int) (8 * getResources().getDisplayMetrics().density);
        measureScreen();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isRunning = true;
        startForeground(NOTIF_ID, buildNotification());

        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, "Allow \"Display over other apps\" first.", Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_TOGGLE_TAB.equals(action)) {
            if (tabView == null) {
                showTab();
            } else {
                hideTab();
            }
            return START_STICKY;
        }
        if (tabView == null) {
            showTab();
        }
        if (ACTION_NEW_NOTE.equals(action)) {
            openPanel();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        closePanel();
        hideTab();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        measureScreen();
        if (tabView != null && tabParams != null) {
            clampTab();
            try {
                wm.updateViewLayout(tabView, tabParams);
            } catch (Exception ignored) {
            }
        }
        closePanel();
    }

    private void measureScreen() {
        screenW = getResources().getDisplayMetrics().widthPixels;
        screenH = getResources().getDisplayMetrics().heightPixels;
    }

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= 23) {
            return android.provider.Settings.canDrawOverlays(this);
        }
        return true;
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= 26) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- floating tab

    private void showTab() {
        if (tabView != null) {
            return;
        }
        tabSize = dp(52);

        TextView bubble = new TextView(this);
        bubble.setText("N");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        bubble.setTypeface(Typeface.DEFAULT_BOLD);
        bubble.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(ACCENT);
        bg.setCornerRadius(tabSize / 2f);
        bubble.setBackground(bg);

        tabView = bubble;
        tabParams = new WindowManager.LayoutParams(
                tabSize,
                tabSize,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        tabParams.gravity = Gravity.TOP | Gravity.START;
        tabParams.x = 0;
        tabParams.y = screenH / 3;

        attachTouch(bubble);

        try {
            wm.addView(tabView, tabParams);
        } catch (Exception e) {
            tabView = null;
            Toast.makeText(this, "Could not draw the floating tab.", Toast.LENGTH_LONG).show();
        }
    }

    private void hideTab() {
        closePanel();
        if (tabView != null) {
            try {
                wm.removeView(tabView);
            } catch (Exception ignored) {
            }
            tabView = null;
        }
    }

    private void attachTouch(final View bubble) {
        bubble.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = tabParams.x;
                        downY = tabParams.y;
                        dragging = false;
                        downTime = System.currentTimeMillis();
                        scheduleLongPress();
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging
                                && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                            dragging = true;
                            cancelLongPress();
                        }
                        if (dragging) {
                            tabParams.x = (int) (downX + dx);
                            tabParams.y = (int) (downY + dy);
                            clampTab();
                            try {
                                wm.updateViewLayout(tabView, tabParams);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        cancelLongPress();
                        boolean tap = !dragging
                                && (System.currentTimeMillis() - downTime) < 400;
                        if (dragging) {
                            snapToEdge();
                        } else if (tap) {
                            if (panelShown) {
                                closePanel();
                            } else {
                                openPanel();
                            }
                        }
                        dragging = false;
                        return true;
                }
                return false;
            }
        });
    }

    private void scheduleLongPress() {
        cancelLongPress();
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                if (!dragging) {
                    hideTab();
                    Toast.makeText(OverlayService.this,
                            "Tab hidden. Bring it back from the NoteFloat notification.",
                            Toast.LENGTH_LONG).show();
                }
            }
        };
        handler.postDelayed(longPressRunnable, 700);
    }

    private void cancelLongPress() {
        if (longPressRunnable != null) {
            handler.removeCallbacks(longPressRunnable);
            longPressRunnable = null;
        }
    }

    private void clampTab() {
        if (tabParams == null) {
            return;
        }
        int maxY = screenH - tabSize - dp(80);
        if (tabParams.y < -dp(8)) {
            tabParams.y = -dp(8);
        }
        if (tabParams.y > maxY) {
            tabParams.y = maxY;
        }
        int maxX = screenW - tabSize;
        if (tabParams.x < -tabSize / 3) {
            tabParams.x = -tabSize / 3;
        }
        if (tabParams.x > maxX + tabSize / 4) {
            tabParams.x = maxX + tabSize / 4;
        }
    }

    private void snapToEdge() {
        int maxX = screenW - tabSize;
        if (tabParams.x + tabSize / 2 < screenW / 2) {
            tabParams.x = 0;
        } else {
            tabParams.x = maxX;
        }
        try {
            wm.updateViewLayout(tabView, tabParams);
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- quick note panel

    private void openPanel() {
        if (panelShown || wm == null) {
            return;
        }
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(PANEL_BG);
        bg.setCornerRadius(dp(20));
        card.setBackground(bg);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Quick note");
        title.setTextColor(PANEL_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        TextView close = new TextView(this);
        close.setText("Close");
        close.setTextColor(PANEL_HINT);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        close.setPadding(dp(10), dp(4), dp(2), dp(6));
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePanel();
            }
        });
        header.addView(close);
        card.addView(header);

        panelInput = new EditText(this);
        panelInput.setHint("Write it down...");
        panelInput.setHintTextColor(PANEL_HINT);
        panelInput.setTextColor(PANEL_TEXT);
        panelInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        panelInput.setBackgroundColor(Color.TRANSPARENT);
        panelInput.setGravity(Gravity.TOP | Gravity.START);
        panelInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        panelInput.setMinLines(3);
        panelInput.setMaxLines(8);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        inputLp.topMargin = dp(6);
        card.addView(panelInput, inputLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(12);

        TextView openApp = new TextView(this);
        openApp.setText("All notes");
        openApp.setTextColor(PANEL_HINT);
        openApp.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        openApp.setPadding(dp(2), dp(10), dp(12), dp(10));
        openApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(OverlayService.this, MainActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                closePanel();
            }
        });
        actions.addView(openApp);

        View spacer = new View(this);
        actions.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView save = new TextView(this);
        save.setText("Save");
        save.setTextColor(Color.WHITE);
        save.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        save.setTypeface(Typeface.DEFAULT_BOLD);
        save.setGravity(Gravity.CENTER);
        save.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setShape(GradientDrawable.RECTANGLE);
        saveBg.setColor(ACCENT);
        saveBg.setCornerRadius(dp(12));
        save.setBackground(saveBg);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveFromPanel();
            }
        });
        actions.addView(save);

        card.addView(actions, actionsLp);

        panelView = card;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                Math.min(screenW - dp(32), dp(440)),
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE;

        card.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                    closePanel();
                    return true;
                }
                return false;
            }
        });

        try {
            wm.addView(panelView, lp);
            panelShown = true;
        } catch (Exception e) {
            panelView = null;
            panelInput = null;
            Toast.makeText(this, "Could not open the quick note panel.", Toast.LENGTH_SHORT).show();
            return;
        }

        panelInput.requestFocus();
        panelInput.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    InputMethodManager imm =
                            (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (imm != null && panelInput != null) {
                        imm.showSoftInput(panelInput, InputMethodManager.SHOW_IMPLICIT);
                    }
                } catch (Exception ignored) {
                }
            }
        }, 120);
    }

    private void saveFromPanel() {
        if (panelInput == null) {
            return;
        }
        String text = panelInput.getText().toString().trim();
        if (text.length() == 0) {
            Toast.makeText(this, "Nothing to save yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        store.insert(text);
        panelInput.setText("");
        Toast.makeText(this, "Saved to NoteFloat", Toast.LENGTH_SHORT).show();
        closePanel();
    }

    private void closePanel() {
        if (panelInput != null) {
            try {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.hideSoftInputFromWindow(panelInput.getWindowToken(), 0);
                }
            } catch (Exception ignored) {
            }
        }
        if (panelView != null) {
            try {
                wm.removeView(panelView);
            } catch (Exception ignored) {
            }
        }
        panelView = null;
        panelInput = null;
        panelShown = false;
    }

    // ---------------------------------------------------------------- notification

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                        "Floating note tab", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps the NoteFloat tab available over other apps.");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }
        }
    }

    private PendingIntent serviceIntent(String action, int requestCode) {
        Intent intent = new Intent(this, OverlayService.class);
        intent.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getService(this, requestCode, intent, flags);
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent openIntent = PendingIntent.getActivity(this, 11, open, flags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("NoteFloat is on")
                .setContentText("Tap the tab to write a note over any app.")
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setShowWhen(false);

        builder.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif),
                "Show / hide tab", serviceIntent(ACTION_TOGGLE_TAB, 21)).build());
        builder.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif),
                "New note", serviceIntent(ACTION_NEW_NOTE, 22)).build());
        builder.addAction(new Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notif),
                "Stop", serviceIntent(ACTION_STOP, 23)).build());

        return builder.build();
    }
}
