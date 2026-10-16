package com.example.gamepad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private static boolean isOverlayRunning = false;
    private static WindowManager wm;
    private static final List<PadItem> padItems = new ArrayList<>();
    private static View topBarView;
    private static View popupDialogView;
    private static DatagramSocket udpSocket;

    private SharedPreferences prefs;
    private String serverIp = "192.168.0.10";
    private final int serverPort = 5005;

    private boolean isEditMode = false;
    private boolean isPadHidden = false;
    private float globalAlpha = 0.65f;
    private float globalScale = 1.0f;

    private PadItem selectedItem = null;
    private TextView tvSelectedInfo;
    private SeekBar seekIndividualSize;

    private static class PadItem {
        String cmd;
        boolean isStick;
        View view;
        WindowManager.LayoutParams params;
        int baseW, baseH;
        float itemScale = 1.0f;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getApplicationContext().getSharedPreferences("ArgonOverlayPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "문라이트 위에 패드를 띄우려면 '다른 앱 위에 표시' 권한을 켜주세요!", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 1001);
            return;
        }

        startFloatingPad();
        finish();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startFloatingPad();
            } else {
                Toast.makeText(this, "권한이 거부되어 오버레이를 실행할 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }

    private int getOverlayType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void startFloatingPad() {
        if (isOverlayRunning) {
            Toast.makeText(this, "아르곤 패드가 이미 화면에 켜져 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        isOverlayRunning = true;

        Context appCtx = getApplicationContext();
        wm = (WindowManager) appCtx.getSystemService(WINDOW_SERVICE);
        serverIp = prefs.getString("server_ip", "192.168.0.10");
        globalScale = prefs.getFloat("global_scale", 1.0f);
        globalAlpha = prefs.getFloat("global_alpha", 0.65f);

        new Thread(() -> {
            try {
                if (udpSocket == null || udpSocket.isClosed()) {
                    udpSocket = new DatagramSocket();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        createTopControlOverlay(appCtx);
        loadAllPadItems(appCtx);
    }

    @SuppressLint("SetTextI18n")
    private void createTopControlOverlay(Context ctx) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#AA000000"));
        container.setPadding(14, 8, 14, 8);

        // 1줄: 기본 상단 메뉴 바
        LinearLayout mainRow = new LinearLayout(ctx);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);

        Button btnHide = makeMenuBtn(ctx, "👁️ 숨김");
        Button btnEdit = makeMenuBtn(ctx, "⚙️ 편집:OFF");
        Button btnAdd = makeMenuBtn(ctx, "➕ 키추가");
        Button btnIp = makeMenuBtn(ctx, "🌐 IP:" + serverIp);
        Button btnExit = makeMenuBtn(ctx, "❌ 종료");
        btnExit.setTextColor(Color.parseColor("#FF6E6E"));

        mainRow.addView(btnHide);
        mainRow.addView(btnEdit);
        mainRow.addView(btnAdd);
        mainRow.addView(btnIp);
        mainRow.addView(btnExit);
        container.addView(mainRow);

        // 2줄: 편집 모드 전용 상세 도구창 (전체 크기 / 개별 크기 / 투명도 / 삭제)
        LinearLayout editPanel = new LinearLayout(ctx);
        editPanel.setOrientation(LinearLayout.VERTICAL);
        editPanel.setPadding(8, 10, 8, 4);
        editPanel.setVisibility(View.GONE);

        LinearLayout infoRow = new LinearLayout(ctx);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        tvSelectedInfo = new TextView(ctx);
        tvSelectedInfo.setText("선택된 버튼: 없음 (크기 조절할 버튼을 터치하세요)");
        tvSelectedInfo.setTextColor(Color.YELLOW);
        tvSelectedInfo.setTextSize(11);
        infoRow.addView(tvSelectedInfo);

        Button btnDeleteSelected = makeMenuBtn(ctx, "🗑️ 선택삭제");
        btnDeleteSelected.setOnClickListener(v -> {
            if (selectedItem != null) {
                removePadItem(selectedItem);
            } else {
                Toast.makeText(ctx, "먼저 삭제할 버튼을 터치해 선택하세요.", Toast.LENGTH_SHORT).show();
            }
        });
        infoRow.addView(btnDeleteSelected);

        Button btnReset = makeMenuBtn(ctx, "🔄 초기화");
        btnReset.setOnClickListener(v -> resetAllPositionsAndSizes(ctx));
        infoRow.addView(btnReset);
        editPanel.addView(infoRow);

        // 개별 버튼 크기 슬라이더
        LinearLayout indRow = new LinearLayout(ctx);
        indRow.setOrientation(LinearLayout.HORIZONTAL);
        indRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvInd = new TextView(ctx);
        tvInd.setText("개별 크기: ");
        tvInd.setTextColor(Color.WHITE);
        tvInd.setTextSize(11);
        indRow.addView(tvInd);

        seekIndividualSize = new SeekBar(ctx);
        seekIndividualSize.setMax(170); // 50% ~ 220%
        seekIndividualSize.setProgress(50);
        seekIndividualSize.setLayoutParams(new LinearLayout.LayoutParams(280, -2));
        seekIndividualSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && selectedItem != null) {
                    selectedItem.itemScale = (50 + p) / 100f;
                    prefs.edit().putFloat("scale_" + selectedItem.cmd, selectedItem.itemScale).apply();
                    updateItemSize(selectedItem);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        indRow.addView(seekIndividualSize);

        // 전체 패드 크기 슬라이더
        TextView tvGlobal = new TextView(ctx);
        tvGlobal.setText("  전체 크기: ");
        tvGlobal.setTextColor(Color.WHITE);
        tvGlobal.setTextSize(11);
        indRow.addView(tvGlobal);

        SeekBar seekGlobalSize = new SeekBar(ctx);
        seekGlobalSize.setMax(130); // 50% ~ 180%
        seekGlobalSize.setProgress(Math.round(globalScale * 100) - 50);
        seekGlobalSize.setLayoutParams(new LinearLayout.LayoutParams(260, -2));
        seekGlobalSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    globalScale = (50 + p) / 100f;
                    prefs.edit().putFloat("global_scale", globalScale).apply();
                    for (PadItem item : padItems) {
                        updateItemSize(item);
                    }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        indRow.addView(seekGlobalSize);

        // 투명도 슬라이더
        TextView tvAlpha = new TextView(ctx);
        tvAlpha.setText("  투명도: ");
        tvAlpha.setTextColor(Color.WHITE);
        tvAlpha.setTextSize(11);
        indRow.addView(tvAlpha);

        SeekBar seekAlpha = new SeekBar(ctx);
        seekAlpha.setMax(85);
        seekAlpha.setProgress(Math.round(globalAlpha * 100) - 15);
        seekAlpha.setLayoutParams(new LinearLayout.LayoutParams(200, -2));
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                globalAlpha = (15 + p) / 100f;
                prefs.edit().putFloat("global_alpha", globalAlpha).apply();
                for (PadItem item : padItems) {
                    item.view.setAlpha(globalAlpha);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        indRow.addView(seekAlpha);

        editPanel.addView(indRow);
        container.addView(editPanel);

        // 상단 바 버튼 이벤트
        btnHide.setOnClickListener(v -> {
            isPadHidden = !isPadHidden;
            btnHide.setText(isPadHidden ? "👁️ 표시" : "👁️ 숨김");
            for (PadItem item : padItems) {
                item.view.setVisibility(isPadHidden ? View.GONE : View.VISIBLE);
            }
        });

        btnEdit.setOnClickListener(v -> {
            isEditMode = !isEditMode;
            btnEdit.setText(isEditMode ? "🔒 잠금(완료)" : "⚙️ 편집:OFF");
            btnEdit.setTextColor(isEditMode ? Color.YELLOW : Color.WHITE);
            editPanel.setVisibility(isEditMode ? View.VISIBLE : View.GONE);
            if (!isEditMode) {
                selectPadItem(null);
            }
        });

        btnAdd.setOnClickListener(v -> showFloatingAddDialog(ctx));
        btnIp.setOnClickListener(v -> showFloatingIpDialog(ctx, btnIp));
        btnExit.setOnClickListener(v -> stopFloatingPad());

        WindowManager.LayoutParams topParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH,
                PixelFormat.TRANSLUCENT);
        topParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topParams.y = 10;

        topBarView = container;
        wm.addView(topBarView, topParams);
    }

    private Button makeMenuBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setPadding(16, 4, 16, 4);
        b.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, 72);
        lp.setMargins(4, 0, 4, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void loadAllPadItems(Context ctx) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels);
        int sh = Math.min(dm.widthPixels, dm.heightPixels);

        // 화면 비율에 맞춘 기본 배치
        addButtonOverlay(ctx, "LT", (int)(sw * 0.04), (int)(sh * 0.14), 150, 115);
        addButtonOverlay(ctx, "LB", (int)(sw * 0.14), (int)(sh * 0.14), 150, 115);
        addButtonOverlay(ctx, "RB", (int)(sw * 0.76), (int)(sh * 0.14), 150, 115);
        addButtonOverlay(ctx, "RT", (int)(sw * 0.86), (int)(sh * 0.14), 150, 115);

        addButtonOverlay(ctx, "Y", (int)(sw * 0.82), (int)(sh * 0.38), 125, 125);
        addButtonOverlay(ctx, "X", (int)(sw * 0.75), (int)(sh * 0.52), 125, 125);
        addButtonOverlay(ctx, "B", (int)(sw * 0.89), (int)(sh * 0.52), 125, 125);
        addButtonOverlay(ctx, "A", (int)(sw * 0.82), (int)(sh * 0.66), 125, 125);

        addButtonOverlay(ctx, "UP", (int)(sw * 0.31), (int)(sh * 0.44), 100, 100);
        addButtonOverlay(ctx, "DOWN", (int)(sw * 0.31), (int)(sh * 0.66), 100, 100);
        addButtonOverlay(ctx, "LEFT", (int)(sw * 0.25), (int)(sh * 0.55), 100, 100);
        addButtonOverlay(ctx, "RIGHT", (int)(sw * 0.37), (int)(sh * 0.55), 100, 100);

        addButtonOverlay(ctx, "BACK", (int)(sw * 0.38), (int)(sh * 0.82), 145, 85);
        addButtonOverlay(ctx, "START", (int)(sw * 0.52), (int)(sh * 0.82), 145, 85);

        addButtonOverlay(ctx, "RT+A", (int)(sw * 0.71), (int)(sh * 0.30), 140, 95);
        addButtonOverlay(ctx, "LB+RT+A", (int)(sw * 0.61), (int)(sh * 0.30), 150, 95);
        addButtonOverlay(ctx, "KEY:Q", (int)(sw * 0.45), (int)(sh * 0.65), 105, 105);
        addButtonOverlay(ctx, "KEY:E", (int)(sw * 0.53), (int)(sh * 0.65), 105, 105);

        addStickOverlay(ctx, "LS", (int)(sw * 0.05), (int)(sh * 0.44), 310);
        addStickOverlay(ctx, "RS", (int)(sw * 0.56), (int)(sh * 0.46), 280);

        Set<String> customSet = prefs.getStringSet("custom_buttons", new HashSet<>());
        for (String cmd : customSet) {
            addButtonOverlay(ctx, cmd, (int)(sw * 0.45), (int)(sh * 0.35), 140, 100);
        }
    }

    private GradientDrawable makeCircleDrawable(boolean pressed, boolean selected) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(pressed ? Color.parseColor("#7700E5FF") : Color.parseColor("#22FFFFFF"));
        gd.setStroke(selected ? 7 : 4, selected ? Color.YELLOW : Color.parseColor("#CCFFFFFF"));
        return gd;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addButtonOverlay(Context ctx, String cmd, int defX, int defY, int baseW, int baseH) {
        if (prefs.getBoolean("deleted_" + cmd, false)) return;

        Button btn = new Button(ctx);
        btn.setText(cmd);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(11);
        btn.setAllCaps(false);
        btn.setPadding(2, 2, 2, 2);
        btn.setBackground(makeCircleDrawable(false, false));
        btn.setAlpha(globalAlpha);

        PadItem item = new PadItem();
        item.cmd = cmd;
        item.isStick = false;
        item.view = btn;
        item.baseW = baseW;
        item.baseH = baseH;
        item.itemScale = prefs.getFloat("scale_" + cmd, 1.0f);

        int w = Math.max(60, Math.round(baseW * globalScale * item.itemScale));
        int h = Math.max(60, Math.round(baseH * globalScale * item.itemScale));
        int x = prefs.getInt("x_" + cmd, defX);
        int y = prefs.getInt("y_" + cmd, defY);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, h, getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;

        item.params = lp;
        padItems.add(item);
        wm.addView(btn, lp);

        final float[] touchOffset = new float[2];
        btn.setOnTouchListener((v, e) -> {
            if (isEditMode) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        selectPadItem(item);
                        touchOffset[0] = lp.x - e.getRawX();
                        touchOffset[1] = lp.y - e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = Math.round(e.getRawX() + touchOffset[0]);
                        lp.y = Math.round(e.getRawY() + touchOffset[1]);
                        wm.updateViewLayout(v, lp);
                        prefs.edit().putInt("x_" + cmd, lp.x).putInt("y_" + cmd, lp.y).apply();
                        return true;
                }
                return true;
            } else {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        v.setBackground(makeCircleDrawable(true, false));
                        sendUdp("COMBO:" + cmd + ":DOWN");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(makeCircleDrawable(false, false));
                        sendUdp("COMBO:" + cmd + ":UP");
                        return true;
                }
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void addStickOverlay(Context ctx, String stickName, int defX, int defY, int baseSize) {
        final float[] normKnob = new float[]{0f, 0f};
        final PadItem item = new PadItem();

        View stickView = new View(ctx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                float radius = Math.min(cx, cy) - 6;
                float maxR = radius * 0.7f;

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(selectedItem == item ? 7 : 4);
                paint.setColor(selectedItem == item ? Color.YELLOW : Color.parseColor("#AAFFFFFF"));
                canvas.drawCircle(cx, cy, radius, paint);
                canvas.drawCircle(cx + normKnob[0] * maxR, cy + normKnob[1] * maxR, radius / 3.0f, paint);
            }
        };
        stickView.setAlpha(globalAlpha);

        item.cmd = stickName;
        item.isStick = true;
        item.view = stickView;
        item.baseW = baseSize;
        item.baseH = baseSize;
        item.itemScale = prefs.getFloat("scale_" + stickName, 1.0f);

        int sz = Math.max(100, Math.round(baseSize * globalScale * item.itemScale));
        int x = prefs.getInt("x_" + stickName, defX);
        int y = prefs.getInt("y_" + stickName, defY);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sz, sz, getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SPLIT_TOUCH
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = x;
        lp.y = y;

        item.params = lp;
        padItems.add(item);
        wm.addView(stickView, lp);

        final float[] touchOffset = new float[2];
        stickView.setOnTouchListener((v, e) -> {
            if (isEditMode) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    selectPadItem(item);
                    touchOffset[0] = lp.x - e.getRawX();
                    touchOffset[1] = lp.y - e.getRawY();
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    lp.x = Math.round(e.getRawX() + touchOffset[0]);
                    lp.y = Math.round(e.getRawY() + touchOffset[1]);
                    wm.updateViewLayout(v, lp);
                    prefs.edit().putInt("x_" + stickName, lp.x).putInt("y_" + stickName, lp.y).apply();
                }
                return true;
            }
            float center = v.getWidth() / 2f;
            float maxR = center * 0.7f;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - center;
                float dy = e.getY() - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                if (dist > maxR && dist > 0) {
                    dx = dx * maxR / dist;
                    dy = dy * maxR / dist;
                }
                normKnob[0] = dx / maxR;
                normKnob[1] = dy / maxR;
                v.invalidate();
                sendUdp("STICK:" + stickName + ":" + Math.round(normKnob[0] * 100) + "," + Math.round(normKnob[1] * 100));
            } else {
                normKnob[0] = 0f;
                normKnob[1] = 0f;
                v.invalidate();
                sendUdp("STICK:" + stickName + ":0,0");
            }
            return true;
        });
    }

    @SuppressLint("SetTextI18n")
    private void selectPadItem(PadItem item) {
        PadItem prev = selectedItem;
        selectedItem = item;
        if (prev != null) {
            if (prev.isStick) prev.view.invalidate();
            else prev.view.setBackground(makeCircleDrawable(false, false));
        }
        if (selectedItem != null) {
            if (selectedItem.isStick) selectedItem.view.invalidate();
            else selectedItem.view.setBackground(makeCircleDrawable(false, true));
            tvSelectedInfo.setText("선택됨: [" + selectedItem.cmd + "] (아래 슬라이더로 크기 조절)");
            seekIndividualSize.setProgress(Math.round(selectedItem.itemScale * 100) - 50);
        } else if (tvSelectedInfo != null) {
            tvSelectedInfo.setText("선택된 버튼: 없음 (크기 조절할 버튼을 터치하세요)");
        }
    }

    private void updateItemSize(PadItem item) {
        item.params.width = Math.max(55, Math.round(item.baseW * globalScale * item.itemScale));
        item.params.height = Math.max(55, Math.round(item.baseH * globalScale * item.itemScale));
        wm.updateViewLayout(item.view, item.params);
        item.view.invalidate();
    }

    private void removePadItem(PadItem item) {
        wm.removeView(item.view);
        padItems.remove(item);
        prefs.edit().putBoolean("deleted_" + item.cmd, true).apply();
        Set<String> set = new HashSet<>(prefs.getStringSet("custom_buttons", new HashSet<>()));
        set.remove(item.cmd);
        prefs.edit().putStringSet("custom_buttons", set).apply();
        selectPadItem(null);
    }

    private void resetAllPositionsAndSizes(Context ctx) {
        String savedIp = serverIp;
        prefs.edit().clear().putString("server_ip", savedIp).apply();
        globalScale = 1.0f;
        globalAlpha = 0.65f;
        for (PadItem item : padItems) {
            wm.removeView(item.view);
        }
        padItems.clear();
        selectPadItem(null);
        loadAllPadItems(ctx);
        Toast.makeText(ctx, "모든 버튼 위치와 크기가 초기화되었습니다.", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("SetTextI18n")
    private void showFloatingAddDialog(Context ctx) {
        closePopupDialog();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(35, 30, 35, 30);
        box.setBackgroundColor(Color.parseColor("#EE1E1E1E"));

        TextView title = new TextView(ctx);
        title.setText("새 조합키 / 키보드 버튼 추가\n(예: RT+A, LB+RT+A, KEY:SPACE, KEY:SHIFT+Q)");
        title.setTextColor(Color.WHITE);
        title.setTextSize(13);
        box.addView(title);

        EditText input = new EditText(ctx);
        input.setHint("RT+LT 또는 KEY:Q 입력");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        box.addView(input);

        LinearLayout btnRow = new LinearLayout(ctx);
        Button btnOk = makeMenuBtn(ctx, "추가");
        Button btnCancel = makeMenuBtn(ctx, "취소");
        btnOk.setOnClickListener(v -> {
            String cmd = input.getText().toString().trim().toUpperCase();
            if (!cmd.isEmpty()) {
                prefs.edit().putBoolean("deleted_" + cmd, false).apply();
                Set<String> set = new HashSet<>(prefs.getStringSet("custom_buttons", new HashSet<>()));
                set.add(cmd);
                prefs.edit().putStringSet("custom_buttons", set).apply();
                addButtonOverlay(ctx, cmd, 500, 300, 140, 100);
            }
            closePopupDialog();
        });
        btnCancel.setOnClickListener(v -> closePopupDialog());
        btnRow.addView(btnOk);
        btnRow.addView(btnCancel);
        box.addView(btnRow);

        showPopupWindow(box);
    }

    @SuppressLint("SetTextI18n")
    private void showFloatingIpDialog(Context ctx, Button btnIp) {
        closePopupDialog();
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(35, 30, 35, 30);
        box.setBackgroundColor(Color.parseColor("#EE1E1E1E"));

        TextView title = new TextView(ctx);
        title.setText("PC 서버 IP 주소 입력");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        box.addView(title);

        EditText input = new EditText(ctx);
        input.setText(serverIp);
        input.setTextColor(Color.WHITE);
        box.addView(input);

        LinearLayout btnRow = new LinearLayout(ctx);
        Button btnOk = makeMenuBtn(ctx, "저장");
        Button btnCancel = makeMenuBtn(ctx, "취소");
        btnOk.setOnClickListener(v -> {
            serverIp = input.getText().toString().trim();
            prefs.edit().putString("server_ip", serverIp).apply();
            btnIp.setText("🌐 IP:" + serverIp);
            closePopupDialog();
        });
        btnCancel.setOnClickListener(v -> closePopupDialog());
        btnRow.addView(btnOk);
        btnRow.addView(btnCancel);
        box.addView(btnRow);

        showPopupWindow(box);
    }

    private void showPopupWindow(View view) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.CENTER;
        popupDialogView = view;
        wm.addView(popupDialogView, lp);
    }

    private void closePopupDialog() {
        if (popupDialogView != null && wm != null) {
            try { wm.removeView(popupDialogView); } catch (Exception ignored) {}
            popupDialogView = null;
        }
    }

    private void stopFloatingPad() {
        closePopupDialog();
        for (PadItem item : padItems) {
            try { wm.removeView(item.view); } catch (Exception ignored) {}
        }
        padItems.clear();
        if (topBarView != null) {
            try { wm.removeView(topBarView); } catch (Exception ignored) {}
            topBarView = null;
        }
        if (udpSocket != null) {
            udpSocket.close();
            udpSocket = null;
        }
        isOverlayRunning = false;
    }

    private void sendUdp(String msg) {
        new Thread(() -> {
            try {
                if (udpSocket == null || udpSocket.isClosed()) return;
                InetAddress addr = InetAddress.getByName(serverIp);
                byte[] data = msg.getBytes();
                udpSocket.send(new DatagramPacket(data, data.length, addr, serverPort));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
