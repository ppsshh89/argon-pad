package com.example.gamepad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {
    private DatagramSocket udpSocket;
    private String serverIp = "192.168.0.10";
    private final int serverPort = 5005;

    private FrameLayout padLayer;
    private SharedPreferences prefs;
    private boolean isEditMode = false;
    private float globalAlpha = 0.65f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ArgonPadPrefs", MODE_PRIVATE);
        serverIp = prefs.getString("server_ip", "192.168.0.10");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#22000000"));

        padLayer = new FrameLayout(this);
        root.addView(padLayer, new FrameLayout.LayoutParams(-1, -1));

        setupTopControlBar(root);
        loadDefaultAndSavedButtons();

        setContentView(root);

        new Thread(() -> {
            try {
                udpSocket = new DatagramSocket();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void setupTopControlBar(FrameLayout root) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(16, 8, 16, 8);
        bar.setBackgroundColor(Color.parseColor("#66000000"));

        Button btnEdit = createMenuButton("편집: OFF");
        btnEdit.setOnClickListener(v -> {
            isEditMode = !isEditMode;
            btnEdit.setText(isEditMode ? "편집: ON(드래그/길게눌러삭제)" : "편집: OFF");
            btnEdit.setTextColor(isEditMode ? Color.YELLOW : Color.WHITE);
        });
        bar.addView(btnEdit);

        Button btnAdd = createMenuButton("+ 키/매크로 추가");
        btnAdd.setOnClickListener(v -> showAddKeyDialog());
        bar.addView(btnAdd);

        Button btnIp = createMenuButton("IP: " + serverIp);
        btnIp.setOnClickListener(v -> showIpDialog(btnIp));
        bar.addView(btnIp);

        SeekBar seekAlpha = new SeekBar(this);
        seekAlpha.setMax(100);
        seekAlpha.setProgress(65);
        seekAlpha.setLayoutParams(new LinearLayout.LayoutParams(220, -2));
        seekAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean b) {
                globalAlpha = Math.max(0.15f, p / 100f);
                for (int i = 0; i < padLayer.getChildCount(); i++) {
                    padLayer.getChildAt(i).setAlpha(globalAlpha);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        bar.addView(seekAlpha);

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(-2, -2);
        barParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        root.addView(bar, barParams);
    }

    private Button createMenuButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setBackgroundColor(Color.parseColor("#44FFFFFF"));
        return b;
    }

    private void loadDefaultAndSavedButtons() {
        // 좌측 상단 트리거/범퍼
        createRoundButton("LT", 60, 140, 170, 130);
        createRoundButton("LB", 260, 140, 170, 130);

        // 우측 상단 트리거/범퍼
        createRoundButton("RB", 1480, 140, 170, 130);
        createRoundButton("RT", 1680, 140, 170, 130);

        // 우측 ABXY 패드 버튼
        createRoundButton("Y", 1600, 420, 140, 140);
        createRoundButton("X", 1450, 560, 140, 140);
        createRoundButton("B", 1750, 560, 140, 140);
        createRoundButton("A", 1600, 700, 140, 140);

        // 십자키 (D-Pad)
        createRoundButton("UP", 640, 460, 110, 110);
        createRoundButton("DOWN", 640, 680, 110, 110);
        createRoundButton("LEFT", 530, 570, 110, 110);
        createRoundButton("RIGHT", 750, 570, 110, 110);

        // 중앙 하단 BACK / START
        createRoundButton("BACK", 760, 840, 160, 95);
        createRoundButton("START", 1020, 840, 160, 95);

        // 기본 조합키 및 키보드 예시 버튼
        createRoundButton("RT+A", 1380, 320, 150, 100);
        createRoundButton("LB+RT+A", 1200, 320, 160, 100);
        createRoundButton("KEY:Q", 960, 680, 110, 110);
        createRoundButton("KEY:E", 1240, 680, 110, 110);
        createRoundButton("KEY:SHIFT", 1100, 680, 120, 110);

        // 좌우 아날로그 스틱
        createStick("LS", 120, 460, 360);
        createStick("RS", 1040, 440, 320);

        // 사용자가 추가했던 커스텀 버튼 불러오기
        Set<String> customSet = prefs.getStringSet("custom_buttons", new HashSet<>());
        for (String cmd : customSet) {
            createRoundButton(cmd, 850, 300, 150, 110);
        }
    }

    private GradientDrawable makeCircleStyle(boolean pressed) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.OVAL);
        gd.setColor(pressed ? Color.parseColor("#6600E5FF") : Color.parseColor("#18FFFFFF"));
        gd.setStroke(4, Color.parseColor("#BBFFFFFF"));
        return gd;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createRoundButton(String cmd, int defX, int defY, int w, int h) {
        Button btn = new Button(this);
        btn.setText(cmd);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setBackground(makeCircleStyle(false));
        btn.setAlpha(globalAlpha);

        int savedX = prefs.getInt("x_" + cmd, defX);
        int savedY = prefs.getInt("y_" + cmd, defY);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = savedX;
        lp.topMargin = savedY;
        padLayer.addView(btn, lp);

        final float[] dXY = new float[2];
        final long[] downTime = new long[1];

        btn.setOnTouchListener((v, event) -> {
            if (isEditMode) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dXY[0] = v.getX() - event.getRawX();
                        dXY[1] = v.getY() - event.getRawY();
                        downTime[0] = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = (int) (event.getRawX() + dXY[0]);
                        int ny = (int) (event.getRawY() + dXY[1]);
                        v.setX(nx);
                        v.setY(ny);
                        prefs.edit().putInt("x_" + cmd, nx).putInt("y_" + cmd, ny).apply();
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (System.currentTimeMillis() - downTime[0] > 900 &&
                            Math.abs(v.getX() - (event.getRawX() + dXY[0])) < 20) {
                            confirmRemoveButton(cmd, v);
                        }
                        return true;
                }
            } else {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_POINTER_DOWN:
                        v.setBackground(makeCircleStyle(true));
                        sendUdp("COMBO:" + cmd + ":DOWN");
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_POINTER_UP:
                    case MotionEvent.ACTION_CANCEL:
                        v.setBackground(makeCircleStyle(false));
                        sendUdp("COMBO:" + cmd + ":UP");
                        return true;
                }
            }
            return false;
        });
    }

    @SuppressLint("ClickableViewAccessibility")
    private void createStick(String stickName, int defX, int defY, int size) {
        final float[] knobXY = new float[]{size / 2f, size / 2f};
        View stickView = new View(this) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                float cx = getWidth() / 2f;
                float cy = getHeight() / 2f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(4);
                paint.setColor(Color.parseColor("#99FFFFFF"));
                canvas.drawCircle(cx, cy, cx - 6, paint);
                canvas.drawCircle(knobXY[0], knobXY[1], cx / 3.2f, paint);
            }
        };
        stickView.setAlpha(globalAlpha);

        int sx = prefs.getInt("x_" + stickName, defX);
        int sy = prefs.getInt("y_" + stickName, defY);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = sx;
        lp.topMargin = sy;
        padLayer.addView(stickView, lp);

        final float[] dXY = new float[2];
        stickView.setOnTouchListener((v, e) -> {
            if (isEditMode) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    dXY[0] = v.getX() - e.getRawX();
                    dXY[1] = v.getY() - e.getRawY();
                } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                    int nx = (int) (e.getRawX() + dXY[0]);
                    int ny = (int) (e.getRawY() + dXY[1]);
                    v.setX(nx);
                    v.setY(ny);
                    prefs.edit().putInt("x_" + stickName, nx).putInt("y_" + stickName, ny).apply();
                }
                return true;
            }
            float center = size / 2f;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN || e.getActionMasked() == MotionEvent.ACTION_MOVE) {
                float dx = e.getX() - center;
                float dy = e.getY() - center;
                float dist = (float) Math.sqrt(dx * dx + dy * dy);
                float maxR = center * 0.7f;
                if (dist > maxR) {
                    dx = dx * maxR / dist;
                    dy = dy * maxR / dist;
                }
                knobXY[0] = center + dx;
                knobXY[1] = center + dy;
                v.invalidate();
                int normX = Math.round((dx / maxR) * 100);
                int normY = Math.round((dy / maxR) * 100);
                sendUdp("STICK:" + stickName + ":" + normX + "," + normY);
            } else {
                knobXY[0] = center;
                knobXY[1] = center;
                v.invalidate();
                sendUdp("STICK:" + stickName + ":0,0");
            }
            return true;
        });
    }

    private void showAddKeyDialog() {
        EditText input = new EditText(this);
        input.setHint("예: RT+A, LB+RT+A, KEY:SPACE, KEY:SHIFT+Q");
        new AlertDialog.Builder(this)
            .setTitle("새 조합키 / 키보드 버튼 추가")
            .setMessage("패드 조합은 + 로 연결하고, 키보드 자판은 앞에 KEY: 를 붙이세요.\n(예: RT+LT, LB+RT+A, KEY:Q, KEY:SPACE)")
            .setView(input)
            .setPositiveButton("추가", (d, w) -> {
                String cmd = input.getText().toString().trim().toUpperCase();
                if (!cmd.isEmpty()) {
                    Set<String> set = new HashSet<>(prefs.getStringSet("custom_buttons", new HashSet<>()));
                    set.add(cmd);
                    prefs.edit().putStringSet("custom_buttons", set).apply();
                    createRoundButton(cmd, 850, 350, 150, 110);
                }
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void confirmRemoveButton(String cmd, View v) {
        new AlertDialog.Builder(this)
            .setTitle("버튼 삭제")
            .setMessage("[" + cmd + "] 버튼을 화면에서 지울까요?")
            .setPositiveButton("삭제", (d, w) -> {
                padLayer.removeView(v);
                Set<String> set = new HashSet<>(prefs.getStringSet("custom_buttons", new HashSet<>()));
                set.remove(cmd);
                prefs.edit().putStringSet("custom_buttons", set).apply();
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void showIpDialog(Button btnIp) {
        EditText input = new EditText(this);
        input.setText(serverIp);
        new AlertDialog.Builder(this)
            .setTitle("PC 서버 IP 주소 입력")
            .setView(input)
            .setPositiveButton("저장", (d, w) -> {
                serverIp = input.getText().toString().trim();
                prefs.edit().putString("server_ip", serverIp).apply();
                btnIp.setText("IP: " + serverIp);
            })
            .setNegativeButton("취소", null)
            .show();
    }

    private void sendUdp(String msg) {
        new Thread(() -> {
            try {
                if (udpSocket == null) return;
                InetAddress addr = InetAddress.getByName(serverIp);
                byte[] data = msg.getBytes();
                udpSocket.send(new DatagramPacket(data, data.length, addr, serverPort));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }
}
