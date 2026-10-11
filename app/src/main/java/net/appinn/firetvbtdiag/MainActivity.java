package net.appinn.firetvbtdiag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Minimal Bluetooth Classic inquiry + createBond diagnostic, designed for Android 11 / Fire OS 8.
 * Uses only public Android APIs. No root, hidden Binder calls or automatic PIN acceptance.
 */
public final class MainActivity extends Activity {
    private static final String TAG = "FireTVBtDiag";
    private static final int REQUEST_LOCATION = 101;
    private static final int BG = 0xFF101C26;
    private static final int PANEL = 0xFF1B2C38;
    private static final int TEXT = Color.WHITE;
    private static final int MUTED = 0xFFB4C9D7;
    private static final int ACCENT = 0xFFFFD181;
    private static final String BOND_REASON_EXTRA = "android.bluetooth.device.extra.REASON";

    private BluetoothAdapter adapter;
    private final LinkedHashMap<String, DeviceRecord> devices = new LinkedHashMap<>();
    private final ArrayList<String> deviceKeys = new ArrayList<>();
    private ArrayAdapter<String> deviceListAdapter;
    private ListView deviceList;
    private TextView statusText;
    private TextView logText;
    private ScrollView logScroll;
    private String selectedAddress;
    private boolean receiverRegistered;
    private boolean scanningRequested;
    private final StringBuilder logBuffer = new StringBuilder();
    private File diagnosticFile;

    private static final class DeviceRecord {
        BluetoothDevice device;
        String name;
        String address;
        int type;
        int rssi = Integer.MIN_VALUE;
        int bondState = BluetoothDevice.BOND_NONE;
        boolean seen;
        boolean bonded;
    }

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            final String action = intent.getAction();
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                scanningRequested = true;
                append("系统广播：ACTION_DISCOVERY_STARTED，经典蓝牙扫描已开始");
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanningRequested = false;
                append("系统广播：ACTION_DISCOVERY_FINISHED，扫描结束，发现设备=" + countSeen());
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    DeviceRecord record = addOrUpdate(device);
                    record.seen = true;
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    if (rssi != Short.MIN_VALUE) record.rssi = rssi;
                    append("发现设备：" + displayName(record) + " / " + record.address
                            + " / " + typeName(record.type) + " / RSSI=" + rssiText(record));
                    redrawDevices();
                }
            } else if (BluetoothDevice.ACTION_NAME_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    DeviceRecord record = addOrUpdate(device);
                    append("设备名称更新：" + displayName(record) + " / " + record.address);
                    redrawDevices();
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1);
                append("收到系统配对请求：" + safeAddress(device) + " / variant=" + variant
                        + "；请观察 Fire TV 和 iPhone 的系统确认弹窗");
                // Deliberately do not intercept or auto-accept pairing.
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                int now = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
                int old = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR);
                int reason = intent.getIntExtra(BOND_REASON_EXTRA, -1);
                append("配对状态：" + safeAddress(device) + " " + bondName(old) + " -> "
                        + bondName(now) + "; 系统原因码=" + (reason < 0 ? "未提供" : reason));
                if (old == BluetoothDevice.BOND_BONDING && now == BluetoothDevice.BOND_NONE) {
                    append("配对失败：系统结束了配对流程，详情请结合 FireTVBtDiag 和蓝牙服务日志判断");
                } else if (now == BluetoothDevice.BOND_BONDED) {
                    append("配对成功：现在可回到 DiPlay -> 选择 iPhone 检查设备列表");
                }
                refreshBonded();
            } else if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                append("底层 ACL 已连接：" + safeAddress(device));
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                append("底层 ACL 已断开：" + safeAddress(device));
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                append("蓝牙适配器状态广播：" + state);
            }
            refreshStatus();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        diagnosticFile = new File(getFilesDir(), "bluetooth-diagnostic.txt");
        buildUi();
        try {
            BluetoothManager manager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            adapter = manager == null ? null : manager.getAdapter();
        } catch (RuntimeException e) {
            append("获取蓝牙适配器失败：" + e);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothDevice.ACTION_NAME_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            registerReceiver(btReceiver, filter);
            receiverRegistered = true;
        } catch (RuntimeException e) {
            append("注册系统广播失败：" + e);
        }
        append("启动：model=" + android.os.Build.MODEL + " sdk=" + android.os.Build.VERSION.SDK_INT
                + " / Android " + android.os.Build.VERSION.RELEASE);
        append("模式：Bluetooth Classic inquiry + createBond；不使用 root / 不自动同意配对");
        append("日志：adb shell run-as net.appinn.firetvbtdiag cat files/bluetooth-diagnostic.txt");
        refreshBonded();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        if (scanningRequested && adapter != null) {
            try { adapter.cancelDiscovery(); } catch (RuntimeException ignored) { }
        }
        if (receiverRegistered) {
            try { unregisterReceiver(btReceiver); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_LOCATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            append("精确位置权限：" + (granted ? "已授予" : "被拒绝"));
            if (granted) doScan();
            else append("提示：可在 Mac 执行 adb shell pm grant net.appinn.firetvbtdiag android.permission.ACCESS_FINE_LOCATION");
            refreshStatus();
        }
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button button(String text, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setFocusable(true);
        b.setOnClickListener(click);
        return b;
    }

    private void buildUi() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setBackgroundColor(BG);
        outer.setPadding(dp(18), dp(12), dp(18), dp(10));
        setContentView(outer);

        TextView title = label("Fire TV 蓝牙配对诊断  ·  Classic / createBond()", 24, TEXT, true);
        outer.addView(title, new LinearLayout.LayoutParams(-1, dp(38)));
        statusText = label("正在检查蓝牙状态…", 14, ACCENT, false);
        statusText.setMaxLines(2);
        outer.addView(statusText, new LinearLayout.LayoutParams(-1, dp(42)));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button scanBtn = button("开始扫描", v -> doScan());
        bar.addView(scanBtn, new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        bar.addView(button("停止扫描", v -> stopScan()), new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        bar.addView(button("配对所选", v -> pairSelected()), new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        bar.addView(button("刷新配对", v -> refreshBonded()), new LinearLayout.LayoutParams(0, dp(52), 1.1f));
        bar.addView(button("输入 MAC", v -> manualMac()), new LinearLayout.LayoutParams(0, dp(52), 1.0f));
        outer.addView(bar);

        LinearLayout center = new LinearLayout(this);
        center.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams centerP = new LinearLayout.LayoutParams(-1, 0, 1);
        centerP.topMargin = dp(6);
        outer.addView(center, centerP);

        LinearLayout left = new LinearLayout(this);
        left.setBackgroundColor(PANEL);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(10), dp(8), dp(10), dp(8));
        center.addView(left, new LinearLayout.LayoutParams(0, -1, 1.08f));
        left.addView(label("设备列表（包含已配对；仅经典蓝牙扫描）", 17, ACCENT, true));
        deviceList = new ListView(this);
        deviceList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        deviceList.setSelector(new ColorDrawable(0xFF31556C));
        deviceList.setDivider(new ColorDrawable(0xFF39505D));
        deviceList.setDividerHeight(dp(2));
        deviceListAdapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_activated_1,
                new ArrayList<>()) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView item = (TextView) super.getView(position, convertView, parent);
                item.setTextColor(TEXT);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                item.setPadding(dp(12), dp(8), dp(10), dp(8));
                item.setMinHeight(dp(66));
                return item;
            }
        };
        deviceList.setAdapter(deviceListAdapter);
        deviceList.setOnItemClickListener((parent, view, position, id) -> {
            selectedAddress = deviceKeys.get(position);
            deviceList.setItemChecked(position, true);
            append("选中设备：" + selectedAddress);
        });
        left.addView(deviceList, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams rightP = new LinearLayout.LayoutParams(0, -1, 1f);
        rightP.leftMargin = dp(8);
        center.addView(right, rightP);
        right.addView(label("实时事件（也写入 logcat）", 17, ACCENT, true));
        logScroll = new ScrollView(this);
        logScroll.setFillViewport(false);
        logScroll.setFocusable(false);
        logText = label("", 13, MUTED, false);
        logText.setTextIsSelectable(false); // Remote-friendly: avoid text-selection modal.
        logText.setPadding(dp(4), dp(6), dp(4), dp(6));
        logScroll.addView(logText);
        right.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));

        TextView footer = label("iPhone：保持「设置 → 蓝牙」页面打开。遥控器方向键移动，确认键操作。日志用 ADB 导出。", 13, MUTED, false);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        outer.addView(footer, new LinearLayout.LayoutParams(-1, dp(36)));
        scanBtn.requestFocus();
    }

    private boolean locationGranted() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean locationOn() {
        try {
            LocationManager location = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return location != null && location.isLocationEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    private void refreshStatus() {
        boolean enabled = false;
        try { enabled = adapter != null && adapter.isEnabled(); }
        catch (RuntimeException e) { append("读取蓝牙状态失败：" + e); }
        String msg = "蓝牙=" + (adapter == null ? "无适配器" : (enabled ? "已开启" : "未开启"))
                + "   位置权限=" + (locationGranted() ? "允许" : "未授予")
                + "   系统定位=" + (locationOn() ? "开启" : "关闭/未知")
                + "   经典蓝牙扫描=" + (scanningRequested ? "已请求/进行中" : "未进行")
                + "   已发现=" + countSeen() + "   已配对=" + countBonded();
        statusText.setText(msg);
    }

    private void doScan() {
        if (adapter == null) {
            append("扫描失败：Android 未返回 BluetoothAdapter"); return;
        }
        if (!adapter.isEnabled()) {
            append("扫描失败：蓝牙未开启；请在 Fire TV 系统设置中开启"); return;
        }
        if (!locationGranted()) {
            append("扫描前请求 ACCESS_FINE_LOCATION（Android 11 要求）");
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
            return;
        }
        if (!locationOn()) append("警告：系统定位关闭/不可读，Android 11 上可能导致扫描结果为空");
        for (DeviceRecord record : devices.values()) record.seen = false;
        scanningRequested = false;
        try {
            if (adapter.isDiscovering()) adapter.cancelDiscovery();
            boolean accepted = adapter.startDiscovery();
            scanningRequested = accepted;
            append("startDiscovery() 返回：" + accepted
                    + "（true 仅表示扫描请求被接受，不代表已找到 iPhone）");
            if (!accepted) append("注意：可能是 Fire OS 拒绝扫描、蓝牙服务繁忙或硬件状态异常");
        } catch (Exception e) {
            append("startDiscovery() 异常：" + e);
        }
        redrawDevices();
        refreshStatus();
    }

    private void stopScan() {
        if (adapter == null) return;
        try {
            append("cancelDiscovery() 返回：" + adapter.cancelDiscovery());
        } catch (Exception e) {
            append("cancelDiscovery() 异常：" + e);
        }
        scanningRequested = false;
        refreshStatus();
    }

    private void refreshBonded() {
        for (DeviceRecord r : devices.values()) r.bonded = false;
        if (adapter == null) {
            append("无法读取已配对设备：无蓝牙适配器");
            refreshStatus();
            return;
        }
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            for (BluetoothDevice d : bonded) {
                DeviceRecord record = addOrUpdate(d);
                record.bonded = true;
                record.bondState = BluetoothDevice.BOND_BONDED;
            }
            append("系统已配对设备数量=" + bonded.size());
        } catch (Exception e) {
            append("getBondedDevices() 失败：" + e);
        }
        redrawDevices();
        refreshStatus();
    }

    private void pairSelected() {
        if (selectedAddress == null || !devices.containsKey(selectedAddress)) {
            append("请先用遥控器在左侧列表中选中一台设备"); return;
        }
        DeviceRecord selected = devices.get(selectedAddress);
        if (selected == null || selected.device == null) return;
        if (selected.bondState == BluetoothDevice.BOND_BONDED) {
            append("所选设备已经 BOND_BONDED；直接进入 DiPlay -> 选择 iPhone");
            return;
        }
        stopScan(); // Android recommends stopping inquiry before connecting/bonding.
        try {
            append("createBond() 发起：" + displayName(selected) + " / " + selectedAddress
                    + " / type=" + typeName(selected.type));
            boolean accepted = selected.device.createBond();
            append("createBond() 返回 " + accepted
                    + (accepted ? "（仅表示异步配对请求已接受）" : "（系统立即拒绝配对请求）"));
            if (accepted) append("注意 Fire TV 及 iPhone：是否出现系统配对码/授权弹窗");
        } catch (Exception e) {
            append("createBond() 异常：" + e);
        }
        refreshStatus();
    }

    private void manualMac() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(19);
        input.setHint("AA:BB:CC:DD:EE:FF");
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        new AlertDialog.Builder(this)
                .setTitle("按经典蓝牙 MAC 添加目标（实验性）")
                .setMessage("仅用于已知真实经典蓝牙地址。iPhone 的随机 BLE 地址不能替代经典蓝牙地址；系统仍可拒绝配对。")
                .setView(input)
                .setPositiveButton("加入列表", (dialog, which) -> {
                    String mac = input.getText().toString().trim().toUpperCase(Locale.ROOT);
                    if (!BluetoothAdapter.checkBluetoothAddress(mac)) {
                        append("手动 MAC 格式错误：" + mac); return;
                    }
                    if (adapter == null) { append("无法添加：无蓝牙适配器"); return; }
                    try {
                        DeviceRecord item = addOrUpdate(adapter.getRemoteDevice(mac));
                        selectedAddress = item.address;
                        redrawDevices();
                        append("手动指定 MAC：" + mac + "；请点「配对所选」继续");
                    } catch (Exception e) {
                        append("手动添加设备异常：" + e);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private DeviceRecord addOrUpdate(BluetoothDevice device) {
        String address = safeAddress(device);
        DeviceRecord record = devices.get(address);
        if (record == null) {
            record = new DeviceRecord();
            record.address = address;
            devices.put(address, record);
        }
        record.device = device;
        try {
            String name = device.getName();
            if (name != null && !name.trim().isEmpty()) record.name = name;
            record.type = device.getType();
            record.bondState = device.getBondState();
            if (record.bondState == BluetoothDevice.BOND_BONDED) record.bonded = true;
        } catch (Exception e) {
            append("读取设备属性失败：" + address + " / " + e);
        }
        return record;
    }

    private String safeAddress(BluetoothDevice device) {
        if (device == null) return "未知设备";
        try { return device.getAddress(); }
        catch (Exception e) { return "地址受限"; }
    }

    private String displayName(DeviceRecord record) {
        return record.name == null ? "(未公开名称)" : record.name;
    }

    private String typeName(int type) {
        switch (type) {
            case BluetoothDevice.DEVICE_TYPE_CLASSIC: return "Classic";
            case BluetoothDevice.DEVICE_TYPE_DUAL: return "Dual";
            case BluetoothDevice.DEVICE_TYPE_LE: return "BLE (不适合直接验证 DiPlay RFCOMM)";
            default: return "Unknown(" + type + ")";
        }
    }

    private String bondName(int state) {
        switch (state) {
            case BluetoothDevice.BOND_NONE: return "BOND_NONE";
            case BluetoothDevice.BOND_BONDING: return "BOND_BONDING";
            case BluetoothDevice.BOND_BONDED: return "BOND_BONDED";
            default: return "UNKNOWN(" + state + ")";
        }
    }

    private String rssiText(DeviceRecord record) {
        return record.rssi == Integer.MIN_VALUE ? "未知" : record.rssi + " dBm";
    }

    private int countSeen() {
        int n = 0;
        for (DeviceRecord r : devices.values()) if (r.seen) n++;
        return n;
    }

    private int countBonded() {
        int n = 0;
        for (DeviceRecord r : devices.values()) if (r.bonded) n++;
        return n;
    }

    private void redrawDevices() {
        deviceListAdapter.clear();
        deviceKeys.clear();
        for (Map.Entry<String, DeviceRecord> entry : devices.entrySet()) {
            DeviceRecord r = entry.getValue();
            deviceKeys.add(entry.getKey());
            deviceListAdapter.add(displayName(r) + "  [" + typeName(r.type) + "]\n" + r.address
                    + "  ·  " + bondName(r.bondState)
                    + (r.seen ? "  ·  最近扫描可见" : "")
                    + (r.rssi != Integer.MIN_VALUE ? "  ·  " + rssiText(r) : ""));
        }
        deviceListAdapter.notifyDataSetChanged();
        if (selectedAddress != null) {
            int selectedIndex = deviceKeys.indexOf(selectedAddress);
            if (selectedIndex >= 0) deviceList.setItemChecked(selectedIndex, true);
        }
        refreshStatus();
    }

    private void append(String message) {
        String stamp = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String line = stamp + "  " + message + "\n";
        Log.i(TAG, message);
        logBuffer.append(line);
        if (logBuffer.length() > 24000) logBuffer.delete(0, logBuffer.length() - 16000);
        if (logText != null) {
            logText.setText(logBuffer.toString());
            if (logScroll != null) logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
        if (diagnosticFile != null) {
            try (FileOutputStream output = new FileOutputStream(diagnosticFile, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                Log.e(TAG, "Could not save diagnostic file", e);
            }
        }
    }
}
