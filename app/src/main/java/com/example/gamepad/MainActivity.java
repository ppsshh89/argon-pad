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
import android.graphics.RectF;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {
    private static boolean isOverlayRunning = false;
    private static WindowManager wm;
    private static MultiTouchPadView padCanvasView;
    private static WindowManager.LayoutParams padCanvasParams;
    private static View topBarView;
    private static View popupDialogView;
    private static DatagramSocket udpSocket;

    private SharedPreferences prefs;
    private String serverIp = "192.168.0.10";
    private final int serverPort = 5005;

    private boolean isTouchPassthrough = false; // true면 스마트폰/문라이트 화면이 직접 눌림
    private boolean isEditMode = false;
    private boolean isPadHidden = false;
    private float globalAlpha = 0.70f;
    private float globalScale = 1.0f;

    private final List<PadItem> padItems = new ArrayList<>();
    private PadItem selectedItem = null;
    private TextView tvSelectedInfo;
    private SeekBar seekIndividualSize;

    private static class PadItem {
        String cmd;
        boolean isStick;
        float x, y;
        int baseW, baseH;
        float itemScale = 1.0f;
        boolean isPressed = false;
        float knobX = 0f, knobY = 0f; // -1.0 ~ 1.0

        float getW(float gScale) { return Math.max(55f, baseW * gScale * itemScale); }
        float getH(float gScale) { return Math.max(55f, baseH * gScale * itemScale); }
        float getCenterX(float gScale) { return x + getW(gScale) / 2f; }
        float getCenterY(float gScale) { return y + getH(gScale) / 2f; }

        boolean contains(float tx, float ty, float gScale) {
            float w = getW(gScale);
            float h = getH(gScale);
            return tx >= x && tx <= x + w && ty >= y && ty <= y + h;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getApplicationContext().getSharedPreferences("ArgonMultiTouchPrefs", MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "'다른 앱 위에 표시' 권한을 켜주세요!", Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "아르곤 패드가 이미 실행 중입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        isOverlayRunning = true;

        Context appCtx = getApplicationContext();
        wm = (WindowManager) appCtx.getSystemService(WINDOW_SERVICE);
        serverIp = prefs.getString("server_ip", "192.168.0.10");
        globalScale = prefs.getFloat("global_scale", 1.0f);
        globalAlpha = prefs.getFloat("global_alpha", 0.70f);

        new Thread(() -> {
            try {
                if (udpSocket == null || udpSocket.isClosed()) {
                    udpSocket = new DatagramSocket();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();

        loadAllPadItems(appCtx);

        // 10손가락 통합 멀티터치 캔버스 생성
        padCanvasView = new MultiTouchPadView(appCtx);
        padCanvasParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        padCanvasParams.gravity = Gravity.TOP | Gravity.LEFT;
        wm.addView(padCanvasView, padCanvasParams);

        // 상단 컨트롤 바 생성 (항상 최상단 위치)
        createTopControlOverlay(appCtx);
    }

    private void setPassthroughMode(boolean passthrough, Button btnMode) {
        isTouchPassthrough = passthrough;
        if (isTouchPassthrough) {
            releaseAllActiveInputs();
            padCanvasParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            btnMode.setText("📱 화면터치중(패드통과)");
            btnMode.setTextColor(Color.parseColor("#00E5FF"));
        } else {
            padCanvasParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            btnMode.setText("🎮 패드켜짐(멀티터치)");
            btnMode.setTextColor(Color.parseColor("#76FF03"));
        }
        wm.updateViewLayout(padCanvasView, padCanvasParams);
        padCanvasView.invalidate();
    }

    @SuppressLint("SetTextI18n")
    private void createTopControlOverlay(Context ctx) {
        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(Color.parseColor("#BB000000"));
        container.setPadding(12, 6, 12, 6);

        LinearLayout mainRow = new LinearLayout(ctx);
        mainRow.setOrientation(LinearLayout.HORIZONTAL);
        mainRow.setGravity(Gravity.CENTER_VERTICAL);

        // 핵심: 문라이트 앱을 켤 때와 패드를 쓸 때를 1초 만에 전환하는 버튼
        Button btnMode = makeMenuBtn(ctx, "🎮 패드켜짐(멀티터치)");
        btnMode.setTextColor(Color.parseColor("#76FF03"));

        Button btnHide = makeMenuBtn(ctx, "👁️ 숨김");
        Button btnEdit = makeMenuBtn(ctx, "⚙️ 편집");
        Button btnAdd = makeMenuBtn(ctx, "➕ 키추가");
        Button btnIp = makeMenuBtn(ctx, "🌐 IP:" + serverIp);
        Button btnExit = makeMenuBtn(ctx, "❌ 종료");
        btnExit.setTextColor(Color.parseColor("#FF6E6E"));

        mainRow.addView(btnMode);
        mainRow.addView(btnHide);
        mainRow.addView(btnEdit);
        mainRow.addView(btnAdd);
        mainRow.addView(btnIp);
        mainRow.addView(btnExit);
        container.addView(mainRow);

        // 편집 모드 패널
        LinearLayout editPanel = new LinearLayout(ctx);
        editPanel.setOrientation(LinearLayout.VERTICAL);
        editPanel.setPadding(8, 8, 8, 4);
        editPanel.setVisibility(View.GONE);

        LinearLayout infoRow = new LinearLayout(ctx);
        infoRow.setOrientation(LinearLayout.HORIZONTAL);
        infoRow.setGravity(Gravity.CENTER_VERTICAL);

        tvSelectedInfo = new TextView(ctx);
        tvSelectedInfo.setText("버튼을 터치해 드래그 이동 / 크기 조절");
        tvSelectedInfo.setTextColor(Color.YELLOW);
        tvSelectedInfo.setTextSize(11);
        infoRow.addView(tvSelectedInfo);

        Button btnDel = makeMenuBtn(ctx, "🗑️ 선택삭제");
        btnDel.setOnClickListener(v -> {
            if (selectedItem != null) {
                padItems.remove(selectedItem);
                prefs.edit().putBoolean("deleted_" + selectedItem.cmd, true).apply();
                Set<String> set = new HashSet<>(prefs.getStringSet("custom_buttons", new HashSet<>()));
                set.remove(selectedItem.cmd);
                prefs.edit().putStringSet("custom_buttons", set).apply();
                selectedItem = null;
                tvSelectedInfo.setText("삭제됨");
                padCanvasView.invalidate();
            }
        });
        infoRow.addView(btnDel);

        Button btnReset = makeMenuBtn(ctx, "🔄 초기화");
        btnReset.setOnClickListener(v -> {
            String savedIp = serverIp;
            prefs.edit().clear().putString("server_ip", savedIp).apply();
            globalScale = 1.0f;
            globalAlpha = 0.70f;
            selectedItem = null;
            loadAllPadItems(ctx);
            padCanvasView.invalidate();
            Toast.makeText(ctx, "초기화 완료!", Toast.LENGTH_SHORT).show();
        });
        infoRow.addView(btnReset);
        editPanel.addView(infoRow);

        LinearLayout sliderRow = new LinearLayout(ctx);
        sliderRow.setOrientation(LinearLayout.HORIZONTAL);
        sliderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvInd = new TextView(ctx);
        tvInd.setText("개별크기:");
        tvInd.setTextColor(Color.WHITE);
        tvInd.setTextSize(11);
        sliderRow.addView(tvInd);

        seekIndividualSize = new SeekBar(ctx);
        seekIndividualSize.setMax(170);
        seekIndividualSize.setProgress(50);
        seekIndividualSize.setLayoutParams(new LinearLayout.LayoutParams(240, -2));
        seekIndividualSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser && selectedItem != null) {
                    selectedItem.itemScale = (50 + p) / 100f;
                    prefs.edit().putFloat("scale_" + selectedItem.cmd, selectedItem.itemScale).apply();
                    padCanvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sliderRow.addView(seekIndividualSize);

        TextView tvGlobal = new TextView(ctx);
        tvGlobal.setText(" 전체크기:");
        tvGlobal.setTextColor(Color.WHITE);
        tvGlobal.setTextSize(11);
        sliderRow.addView(tvGlobal);

        SeekBar seekGlobal = new SeekBar(ctx);
        seekGlobal.setMax(130);
        seekGlobal.setProgress(Math.round(globalScale * 100) - 50);
        seekGlobal.setLayoutParams(new LinearLayout.LayoutParams(220, -2));
        seekGlobal.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) {
                    globalScale = (50 + p) / 100f;
                    prefs.edit().putFloat("global_scale", globalScale).apply();
                    padCanvasView.invalidate();
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sliderRow.addView(seekGlobal);

        TextView tvAlpha = new TextView(ctx);
        tvAlpha.setText(" 투명도:");
        tvAlpha.setTextColor(Color.WHITE);
        tvAlpha.setTextSize(11);
        sliderRow.addView(tvAlpha);

        SeekBar seekAlpha = new SeekBar(ctx);
        seekAlpha.setMax(85);
        seekAlpha.setProgress(Math.round(globalAlpha * 100) - 15);
        seekAlpha.setLayoutParams(new LinearLayout.LayoutParams(180, -2));
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                globalAlpha = (15 + p) / 100f;
                prefs.edit().putFloat("global_alpha", globalAlpha).apply();
                padCanvasView.invalidate();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        sliderRow.addView(seekAlpha);

        editPanel.addView(sliderRow);
        container.addView(editPanel);

        btnMode.setOnClickListener(v -> setPassthroughMode(!isTouchPassthrough, btnMode));

        btnHide.setOnClickListener(v -> {
            isPadHidden = !isPadHidden;
            btnHide.setText(isPadHidden ? "👁️ 표시" : "👁️ 숨김");
            padCanvasView.setVisibility(isPadHidden ? View.GONE : View.VISIBLE);
        });

        btnEdit.setOnClickListener(v -> {
            isEditMode = !isEditMode;
            if (isEditMode && isTouchPassthrough) {
                setPassthroughMode(false, btnMode);
            }
            btnEdit.setText(isEditMode ? "🔒 완료" : "⚙️ 편집");
            btnEdit.setTextColor(isEditMode ? Color.YELLOW : Color.WHITE);
            editPanel.setVisibility(isEditMode ? View.VISIBLE : View.GONE);
            if (!isEditMode) selectedItem = null;
            padCanvasView.invalidate();
        });

        btnAdd.setOnClickListener(v -> showFloatingAddDialog(ctx));
        btnIp.setOnClickListener(v -> showFloatingIpDialog(ctx, btnIp));
        btnExit.setOnClickListener(v -> stopFloatingPad());

        WindowManager.LayoutParams topParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                getOverlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        topParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        topParams.y = 8;

        topBarView = container;
        wm.addView(topBarView, topParams);
    }

    private Button makeMenuBtn(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(10);
        b.setAllCaps(false);
        b.setPadding(14, 2, 14, 2);
        b.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, 68);
        lp.setMargins(3, 0, 3, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private void loadAllPadItems(Context ctx) {
        padItems.clear();
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels);
        int sh = Math.min(dm.widthPixels, dm.heightPixels);

        addPadItem("LT", false, (int)(sw * 0.04), (int)(sh * 0.14), 150, 115);
        addPadItem("LB", false, (int)(sw * 0.14), (int)(sh * 0.14), 150, 115);
        addPadItem("RB", false, (int)(sw * 0.76), (int)(sh * 0.14), 150, 115);
        addPadItem("RT", false, (int)(sw * 0.86), (int)(sh * 0.14), 150, 115);

        addPadItem("Y", false, (int)(sw * 0.82), (int)(sh * 0.38), 125, 125);
        addPadItem("X", false, (int)(sw * 0.75), (int)(sh * 0.52), 125, 125);
        addPadItem("B", false, (int)(sw * 0.89), (int)(sh * 0.52), 125, 125);
        addPadItem("A", false, (int)(sw * 0.82), (int)(sh * 0.66), 125, 125);

        addPadItem("UP", false, (int)(sw * 0.31), (int)(sh * 0.44), 105, 105);
        addPadItem("DOWN", false, (int)(sw * 0.31), (int)(sh * 0.66), 105, 105);
        addPadItem("LEFT", false, (int)(sw * 0.25), (int)(sh * 0.55), 105, 105);
        addPadItem("RIGHT", false, (int)(sw * 0.37), (int)(sh * 0.55), 105, 105);

        addPadItem("BACK", false, (int)(sw * 0.38), (int)(sh * 0.82), 145, 85);
        addPadItem("START", false, (int)(sw * 0.52), (int)(sh * 0.82), 145, 85);

        addPadItem("RT+A", false, (int)(sw * 0.71), (int)(sh * 0.30), 140, 95);
        addPadItem("LB+RT+A", false, (int)(sw * 0.61), (int)(sh * 0.30), 150, 95);
        addPadItem("KEY:Q", false, (int)(sw * 0.45), (int)(sh * 0.65), 105, 105);
        addPadItem("KEY:E", false, (int)(sw * 0.53), (int)(sh * 0.65), 105, 105);

        addPadItem("LS", true, (int)(sw * 0.05), (int)(sh * 0.44), 310, 310);
        addPadItem("RS", true, (int)(sw * 0.56), (int)(sh * 0.46), 280, 280);

        Set<String> customSet = prefs.getStringSet("custom_buttons", new HashSet<>());
        for (String cmd : customSet) {
            addPadItem(cmd, false, (int)(sw * 0.45), (int)(sh * 0.35), 140, 100);
        }
    }

    private void addPadItem(String cmd, boolean isStick, int defX, int defY, int w, int h) {
        if (prefs.getBoolean("deleted_" + cmd, false)) return;
        for (PadItem existing : padItems) {
            if (existing.cmd.equals(cmd)) return;
        }
        PadItem item = new PadItem();
        item.cmd = cmd;
        item.isStick = isStick;
        item.baseW = w;
        item.baseH = h;
        item.x = prefs.getInt("x_" + cmd, defX);
        item.y = prefs.getInt("y_" + cmd, defY);
        item.itemScale = prefs.getFloat("scale_" + cmd, 1.0f);
        padItems.add(item);
    }

    private void releaseAllActiveInputs() {
        for (PadItem item : padItems) {
            if (item.isStick && (item.knobX != 0 || item.knobY != 0)) {
                item.knobX = 0;
                item.knobY = 0;
                sendUdp("STICK:" + item.cmd + ":0,0");
            } else if (!item.isStick && item.isPressed) {
                item.isPressed = false;
                sendUdp("COMBO:" + item.cmd + ":UP");
            }
        }
        sendUdp("COMBO:ALL:RESET");
    }

    /**
     * 문라이트와 동일한 10손가락 동시 멀티터치 처리 뷰
     */
    private class MultiTouchPadView extends View {
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rectF = new RectF();

        // 각 손가락(pointerId)이 조작 중인 스틱 고정 추적
        private final Map<Integer, PadItem> pointerStickMap = new HashMap<>();
        private float editDragOffsetX, editDragOffsetY;

        public MultiTouchPadView(Context context) {
            super(context);
            strokePaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStyle(Paint.Style.FILL);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float alphaFactor = isTouchPassthrough ? 0.25f : globalAlpha;
            int strokeAlpha = Math.min(255, Math.round(220 * alphaFactor));
            int textAlpha = Math.min(255, Math.round(255 * alphaFactor));

            for (PadItem item : padItems) {
                float w = item.getW(globalScale);
                float h = item.getH(globalScale);
                rectF.set(item.x, item.y, item.x + w, item.y + h);

                boolean isSel = (isEditMode && selectedItem == item);
                strokePaint.setStrokeWidth(isSel ? 7f : 4f);
                strokePaint.setColor(isSel ? Color.YELLOW : Color.argb(strokeAlpha, 255, 255, 255));

                if (item.isStick) {
                    float cx = item.x + w / 2f;
                    float cy = item.y + h / 2f;
                    float r = Math.min(w, h) / 2f - 4f;
                    float maxR = r * 0.7f;

                    fillPaint.setColor(Color.argb(Math.round(35 * alphaFactor), 255, 255, 255));
                    canvas.drawCircle(cx, cy, r, fillPaint);
                    canvas.drawCircle(cx, cy, r, strokePaint);

                    float kx = cx + item.knobX * maxR;
                    float ky = cy + item.knobY * maxR;
                    fillPaint.setColor(item.isPressed
                            ? Color.argb(Math.round(140 * alphaFactor), 0, 229, 255)
                            : Color.argb(Math.round(70 * alphaFactor), 255, 255, 255));
                    canvas.drawCircle(kx, ky, r / 2.8f, fillPaint);
                    canvas.drawCircle(kx, ky, r / 2.8f, strokePaint);
                } else {
                    fillPaint.setColor(item.isPressed
                            ? Color.argb(Math.round(150 * alphaFactor), 0, 229, 255)
                            : Color.argb(Math.round(45 * alphaFactor), 255, 255, 255));
                    canvas.drawOval(rectF, fillPaint);
                    canvas.drawOval(rectF, strokePaint);

                    textPaint.setTextSize(Math.max(24f, Math.min(w, h) * 0.24f));
                    textPaint.setAlpha(textAlpha);
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    float textY = item.y + h / 2f - (fm.ascent + fm.descent) / 2f;
                    canvas.drawText(item.cmd, item.x + w / 2f, textY, textPaint);
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (isTouchPassthrough) return false;

            int action = event.getActionMasked();
            int actionIdx = event.getActionIndex();
            int pointerId = event.getPointerId(actionIdx);

            // 1. 편집 모드: 드래그 이동 및 개별 크기 선택
            if (isEditMode) {
                float tx = event.getX();
                float ty = event.getY();
                if (action == MotionEvent.ACTION_DOWN) {
                    selectedItem = findTopItemAt(tx, ty);
                    if (selectedItem != null) {
                        editDragOffsetX = selectedItem.x - tx;
                        editDragOffsetY = selectedItem.y - ty;
                        tvSelectedInfo.setText("선택됨: [" + selectedItem.cmd + "]");
                        seekIndividualSize.setProgress(Math.round(selectedItem.itemScale * 100) - 50);
                    } else {
                        tvSelectedInfo.setText("선택된 버튼: 없음");
                    }
                    invalidate();
                } else if (action == MotionEvent.ACTION_MOVE && selectedItem != null) {
                    selectedItem.x = tx + editDragOffsetX;
                    selectedItem.y = ty + editDragOffsetY;
                    prefs.edit()
                            .putInt("x_" + selectedItem.cmd, Math.round(selectedItem.x))
                            .putInt("y_" + selectedItem.cmd, Math.round(selectedItem.y))
                            .apply();
                    invalidate();
                }
                return true;
            }

            // 2. 게임 모드: 10손가락 독립 멀티터치 처리
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                float px = event.getX(actionIdx);
                float py = event.getY(actionIdx);
                for (int i = padItems.size() - 1; i >= 0; i--) {
                    PadItem item = padItems.get(i);
                    if (item. isStick && item.contains(px, py, globalScale)) {
                        pointerStickMap.put(pointerId, item);
                        item.isPressed = true;
                        updateStickKnob(item, px, py);
                        break;
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
                if (action == MotionEvent.ACTION_CANCEL) {
                    pointerStickMap.clear();
                    releaseAllActiveInputs();
                    invalidate();
                    return true;
                }
                PadItem stick = pointerStickMap.remove(pointerId);
                if (stick != null) {
                    stick.isPressed = false;
                    stick.knobX = 0f;
                    stick.knobY = 0f;
                    sendUdp("STICK:" + stick.cmd + ":0,0");
                }
            }

            // 현재 화면에 닿아있는 모든 손가락 위치 스캔 (동시 입력 및 슬라이드 완벽 인식)
            int pointerCount = event.getPointerCount();
            Set<PadItem> currentlyTouchedButtons = new HashSet<>();

            for (int i = 0; i < pointerCount; i++) {
                // 방금 화면에서 떨어진 손가락은 제외
                if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) && i == actionIdx) {
                    continue;
                }
                int pid = event.getPointerId(i);
                float px = event.getX(i);
                float py = event.getY(i);

                // 스틱에 할당된 손가락이면 스틱 좌표 업데이트
                PadItem boundStick = pointerStickMap.get(pid);
                if (boundStick != null) {
                    updateStickKnob(boundStick, px, py);
                    continue;
                }

                // 일반 버튼 / 십자키 / 조합키 동시 체크
                for (PadItem item : padItems) {
                    if (!item.isStick && item.contains(px, py, globalScale)) {
                        currentlyTouchedButtons.add(item);
                    }
                }
            }

            // 이전 프레임과 비교해 새로 눌린 버튼은 DOWN, 떨어진 버튼은 UP 전송
            for (PadItem item : padItems) {
                if (item.isStick) continue;
                boolean nowPressed = currentlyTouchedButtons.contains(item);
                if (nowPressed && !item.isPressed) {
                    item.isPressed = true;
                    sendUdp("COMBO:" + item.cmd + ":DOWN");
                } else if (!nowPressed && item.isPressed) {
                    item.isPressed = false;
                    sendUdp("COMBO:" + item.cmd + ":UP");
                }
            }

            invalidate();
            return true;
        }

        private void updateStickKnob(PadItem stick, float px, float py) {
            float cx = stick.getCenterX(globalScale);
            float cy = stick.getCenterY(globalScale);
            float maxR = (Math.min(stick.getW(globalScale), stick.getH(globalScale)) / 2f) * 0.7f;
            float dx = px - cx;
            float dy = py - cy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy);
            if (dist > maxR && dist > 0) {
                dx = dx * maxR / dist;
                dy = dy * maxR / dist;
            }
            stick.knobX = dx / maxR;
            stick.knobY = dy / maxR;
            int nx = Math.round(stick.knobX * 100);
            int ny = Math.round(stick.knobY * 100);
            sendUdp("STICK:" + stick.cmd + ":" + nx + "," + ny);
        }

        private PadItem findTopItemAt(float x, float y) {
            for (int i = padItems.size() - 1; i >= 0; i--) {
                PadItem item = padItems.get(i);
                if (item.contains(x, y, globalScale)) return item;
            }
            return null;
        }
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
                addPadItem(cmd, false, 500, 300, 140, 100);
                padCanvasView.invalidate();
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
        releaseAllActiveInputs();
        closePopupDialog();
        if (padCanvasView != null) {
            try { wm.removeView(padCanvasView); } catch (Exception ignored) {}
            padCanvasView = null;
        }
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
