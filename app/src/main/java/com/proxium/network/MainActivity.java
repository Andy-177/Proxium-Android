package com.proxium.network;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.widget.NestedScrollView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private EditText etLocalIp, etLocalPort, etRemoteIp, etRemotePort;
    private Button btnStart, btnStop, btnClear;
    private TextView tvLog, tvPacketDetail, tvPacketCount;
    // 修改这里：将 ScrollView 改为 NestedScrollView
    private NestedScrollView svLog, svPacketDetail;
    private TableLayout tablePackets;
    private HorizontalScrollView horizontalScrollView;
    private ExecutorService executorService;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private Handler mainHandler;
    private ServerSocket tcpServer;
    private DatagramSocket udpSocket;
    private ArrayList<Map<String, Object>> packets = new ArrayList<>();
    private int maxPackets = 500;
    private int selectedPacketIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mainHandler = new Handler(Looper.getMainLooper());
        executorService = Executors.newCachedThreadPool();

        // 初始化控件
        etLocalIp = findViewById(R.id.et_local_ip);
        etLocalPort = findViewById(R.id.et_local_port);
        etRemoteIp = findViewById(R.id.et_remote_ip);
        etRemotePort = findViewById(R.id.et_remote_port);
        btnStart = findViewById(R.id.btn_start);
        btnStop = findViewById(R.id.btn_stop);
        btnClear = findViewById(R.id.btn_clear);
        tvLog = findViewById(R.id.tv_log);
        tvPacketDetail = findViewById(R.id.tv_packet_detail);
        tvPacketCount = findViewById(R.id.tv_packet_count);
        // 这里不需要强制转换，直接赋值即可
        svLog = findViewById(R.id.sv_log);
        svPacketDetail = findViewById(R.id.sv_packet_detail);
        tablePackets = findViewById(R.id.table_packets);
        horizontalScrollView = findViewById(R.id.horizontal_scroll_view);

        // 设置文本可滚动
        tvLog.setMovementMethod(new ScrollingMovementMethod());
        tvPacketDetail.setMovementMethod(new ScrollingMovementMethod());

        // 默认值
        etLocalIp.setText("127.0.0.1");
        etLocalPort.setText("11434");
        etRemoteIp.setText("192.168.6.99");
        etRemotePort.setText("11434");

        btnStart.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startForwarding();
                }
            });

        btnStop.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    stopForwarding();
                }
            });

        btnClear.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    clearPackets();
                }
            });

        btnStop.setEnabled(false);
    }

    private void clearPackets() {
        packets.clear();
        mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    // 移除所有表格行（保留表头）
                    int childCount = tablePackets.getChildCount();
                    for (int i = childCount - 1; i >= 1; i--) {
                        tablePackets.removeViewAt(i);
                    }
                    tvPacketCount.setText("数据包: 0");
                    tvPacketDetail.setText("");
                    selectedPacketIndex = -1;
                    log("已清空数据包列表");
                }
            });
    }

    private void log(final String msg) {
        mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    tvLog.append(msg + "\n");
                    // NestedScrollView 也支持 fullScroll 方法
                    svLog.fullScroll(View.FOCUS_DOWN);
                }
            });
    }

    private String formatHexDump(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%04x: ", i));
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                sb.append(String.format("%02x ", data[i + j]));
            }
            sb.append("  ");
            for (int j = 0; j < 16 && i + j < data.length; j++) {
                char c = (char) data[i + j];
                sb.append((c >= 32 && c < 127) ? c : '.');
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void addPacketToTable(final int index, final String timestamp, final String direction, 
                                  final int length, final String srcAddr, final String dstAddr) {
        TableRow row = new TableRow(this);
        row.setLayoutParams(new TableRow.LayoutParams(TableRow.LayoutParams.MATCH_PARENT, TableRow.LayoutParams.WRAP_CONTENT));

        // 交替行颜色
        if (index % 2 == 1) {
            row.setBackgroundColor(0xfff5f5f5);
        } else {
            row.setBackgroundColor(0xffffffff);
        }

        // 序号
        TextView tvIndex = createTableCell(String.valueOf(index), 0.5f);
        tvIndex.setPadding(8, 6, 8, 6);
        tvIndex.setTextSize(10);
        row.addView(tvIndex);

        // 时间
        TextView tvTime = createTableCell(timestamp, 2.0f);
        tvTime.setPadding(8, 6, 8, 6);
        tvTime.setTextSize(10);
        row.addView(tvTime);

        // 方向
        TextView tvDirection = createTableCell(direction, 1.2f);
        tvDirection.setPadding(8, 6, 8, 6);
        tvDirection.setTextSize(10);
        // 根据方向设置颜色
        if ("客户端->远端".equals(direction)) {
            tvDirection.setTextColor(0xff4caf50);
        } else {
            tvDirection.setTextColor(0xff2196f3);
        }
        row.addView(tvDirection);

        // 长度
        TextView tvLength = createTableCell(String.valueOf(length), 0.8f);
        tvLength.setPadding(8, 6, 8, 6);
        tvLength.setTextSize(10);
        row.addView(tvLength);

        // 源地址
        TextView tvSrc = createTableCell(srcAddr, 2.5f);
        tvSrc.setPadding(8, 6, 8, 6);
        tvSrc.setTextSize(10);
        row.addView(tvSrc);

        // 目标地址
        TextView tvDst = createTableCell(dstAddr, 2.5f);
        tvDst.setPadding(8, 6, 8, 6);
        tvDst.setTextSize(10);
        row.addView(tvDst);

        // 设置点击事件
        final int packetIndex = index - 1;
        row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showPacketDetail(packetIndex);
                    highlightSelectedRow(index);
                }
            });

        tablePackets.addView(row);

        // 水平滚动到底部
        if (horizontalScrollView != null) {
            horizontalScrollView.post(new Runnable() {
                    @Override
                    public void run() {
                        horizontalScrollView.fullScroll(HorizontalScrollView.FOCUS_RIGHT);
                    }
                });
        }
    }

    private TextView createTableCell(String text, float weight) {
        TextView tv = new TextView(this);
        TableRow.LayoutParams params = new TableRow.LayoutParams(0, TableRow.LayoutParams.WRAP_CONTENT, weight);
        tv.setLayoutParams(params);
        tv.setText(text);
        tv.setSingleLine(true);
        return tv;
    }

    private void highlightSelectedRow(int selectedTableIndex) {
        // 重置所有行背景（跳过表头，表头是第0个）
        int childCount = tablePackets.getChildCount();
        for (int i = 1; i < childCount; i++) {
            View row = tablePackets.getChildAt(i);
            if (i == selectedTableIndex) {
                row.setBackgroundColor(0xffcce5ff);
            } else {
                if ((i - 1) % 2 == 0) {
                    row.setBackgroundColor(0xffffffff);
                } else {
                    row.setBackgroundColor(0xfff5f5f5);
                }
            }
        }
    }

    private void showPacketDetail(int index) {
        if (index >= 0 && index < packets.size()) {
            selectedPacketIndex = index;
            Map<String, Object> packet = packets.get(index);
            byte[] data = (byte[]) packet.get("data");
            StringBuilder detail = new StringBuilder();
            detail.append("时间: ").append(packet.get("timestamp")).append("\n");
            detail.append("方向: ").append(packet.get("direction")).append("\n");
            detail.append("长度: ").append(packet.get("length")).append(" bytes\n");
            detail.append("源地址: ").append(packet.get("src_addr")).append("\n");
            detail.append("目标地址: ").append(packet.get("dst_addr")).append("\n\n");
            detail.append("--- Hex Dump ---\n");
            detail.append(formatHexDump(data));
            tvPacketDetail.setText(detail.toString());

            // 滚动到顶部
            if (svPacketDetail != null) {
                svPacketDetail.post(new Runnable() {
                        @Override
                        public void run() {
                            svPacketDetail.fullScroll(View.FOCUS_UP);
                        }
                    });
            }
        }
    }

    private void addPacket(final String timestamp, final String direction, final int length, 
                           final String srcAddr, final String dstAddr, final byte[] data) {
        Map<String, Object> packet = new HashMap<String, Object>();
        packet.put("timestamp", timestamp);
        packet.put("direction", direction);
        packet.put("length", length);
        packet.put("src_addr", srcAddr);
        packet.put("dst_addr", dstAddr);
        packet.put("data", data);

        packets.add(packet);
        if (packets.size() > maxPackets) {
            packets.remove(0);
            // 移除表格中的第一行数据（跳过表头）
            mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tablePackets.getChildCount() > 1) {
                            tablePackets.removeViewAt(1);
                        }
                    }
                });
        }

        final int index = packets.size();

        mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    addPacketToTable(index, timestamp, direction, length, srcAddr, dstAddr);
                    tvPacketCount.setText("数据包: " + packets.size());
                }
            });
    }

    private void startForwarding() {
        try {
            final String localIp = etLocalIp.getText().toString();
            final int localPort = Integer.parseInt(etLocalPort.getText().toString());
            final String remoteIp = etRemoteIp.getText().toString();
            final int remotePort = Integer.parseInt(etRemotePort.getText().toString());

            if (localPort <= 0 || localPort > 65535 || remotePort <= 0 || remotePort > 65535) {
                Toast.makeText(this, "端口范围 1-65535", Toast.LENGTH_SHORT).show();
                return;
            }

            isRunning.set(true);

            executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        startTcpForwarding(localIp, localPort, remoteIp, remotePort);
                    }
                });

            executorService.execute(new Runnable() {
                    @Override
                    public void run() {
                        startUdpForwarding(localIp, localPort, remoteIp, remotePort);
                    }
                });

            runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnStart.setEnabled(false);
                        btnStop.setEnabled(true);
                    }
                });
            log("转发服务已启动");

        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效的端口号", Toast.LENGTH_SHORT).show();
        }
    }

    private void startTcpForwarding(String localIp, int localPort, final String remoteIp, final int remotePort) {
        try {
            tcpServer = new ServerSocket();
            tcpServer.setReuseAddress(true);
            tcpServer.bind(new InetSocketAddress(localIp, localPort));
            tcpServer.setSoTimeout(1000);
            log("[TCP] 监听 " + localIp + ":" + localPort + " -> " + remoteIp + ":" + remotePort);

            while (isRunning.get()) {
                try {
                    final Socket clientSocket = tcpServer.accept();
                    final InetSocketAddress clientAddr = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
                    log("[TCP] 连接来自 " + clientAddr.getAddress().getHostAddress() + ":" + clientAddr.getPort());

                    executorService.execute(new Runnable() {
                            @Override
                            public void run() {
                                handleTcpClient(clientSocket, remoteIp, remotePort);
                            }
                        });
                } catch (SocketTimeoutException e) {
                    // 继续循环
                } catch (IOException e) {
                    if (isRunning.get()) {
                        log("[TCP] 错误: " + e.getMessage());
                    }
                    break;
                }
            }
        } catch (IOException e) {
            log("[TCP] 启动失败: " + e.getMessage());
        }
    }

    private void handleTcpClient(Socket clientSocket, String remoteIp, int remotePort) {
        Socket remoteSocket = null;
        try {
            remoteSocket = new Socket(remoteIp, remotePort);
            remoteSocket.setSoTimeout(1000);
            clientSocket.setSoTimeout(1000);

            final InetSocketAddress clientAddr = (InetSocketAddress) clientSocket.getRemoteSocketAddress();
            final InetSocketAddress remoteAddr = (InetSocketAddress) remoteSocket.getRemoteSocketAddress();

            final String clientAddrStr = clientAddr.getAddress().getHostAddress() + ":" + clientAddr.getPort();
            final String remoteAddrStr = remoteAddr.getAddress().getHostAddress() + ":" + remoteAddr.getPort();

            final Socket finalRemoteSocket = remoteSocket;
            final Socket finalClientSocket = clientSocket;

            Thread t1 = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        forwardData(finalClientSocket, finalRemoteSocket, "客户端->远端", clientAddrStr, remoteAddrStr);
                    }
                });

            Thread t2 = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        forwardData(finalRemoteSocket, finalClientSocket, "远端->客户端", remoteAddrStr, clientAddrStr);
                    }
                });

            t1.start();
            t2.start();

            try {
                t1.join();
                t2.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            log("[TCP] 转发错误: " + e.getMessage());
        } finally {
            try {
                if (clientSocket != null) clientSocket.close();
            } catch (Exception e) {}
            try {
                if (remoteSocket != null) remoteSocket.close();
            } catch (Exception e) {}
        }
        log("[TCP] 连接关闭");
    }

    private void forwardData(Socket src, Socket dst, String direction, String srcAddr, String dstAddr) {
        byte[] buffer = new byte[4096];
        try {
            InputStream srcIn = src.getInputStream();
            OutputStream dstOut = dst.getOutputStream();

            while (isRunning.get()) {
                int len = srcIn.read(buffer);
                if (len <= 0) break;

                final byte[] data = new byte[len];
                System.arraycopy(buffer, 0, data, 0, len);

                final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                final String finalDirection = direction;
                final String finalSrcAddr = srcAddr;
                final String finalDstAddr = dstAddr;
                final int finalLen = len;

                runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            addPacket(timestamp, finalDirection, finalLen, finalSrcAddr, finalDstAddr, data);
                        }
                    });

                dstOut.write(data);
                dstOut.flush();
            }
        } catch (Exception e) {
            // 连接关闭，正常情况
        }
    }

    private void startUdpForwarding(String localIp, int localPort, final String remoteIp, final int remotePort) {
        try {
            udpSocket = new DatagramSocket(new InetSocketAddress(localIp, localPort));
            udpSocket.setSoTimeout(1000);
            log("[UDP] 监听 " + localIp + ":" + localPort + " -> " + remoteIp + ":" + remotePort);

            byte[] buffer = new byte[65535];
            InetSocketAddress clientAddr = null;

            while (isRunning.get()) {
                try {
                    final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpSocket.receive(packet);

                    final byte[] data = new byte[packet.getLength()];
                    System.arraycopy(packet.getData(), 0, data, 0, packet.getLength());

                    final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
                    final InetSocketAddress srcAddr = new InetSocketAddress(packet.getAddress(), packet.getPort());

                    if (srcAddr.getPort() == remotePort && srcAddr.getAddress().getHostAddress().equals(remoteIp)) {
                        // 来自远端
                        if (clientAddr != null) {
                            final InetSocketAddress finalClientAddr = clientAddr;
                            DatagramPacket outPacket = new DatagramPacket(data, data.length, 
                                                                          finalClientAddr.getAddress(), finalClientAddr.getPort());
                            udpSocket.send(outPacket);

                            runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        addPacket(timestamp, "远端->客户端", data.length,
                                                  srcAddr.getAddress().getHostAddress() + ":" + srcAddr.getPort(),
                                                  finalClientAddr.getAddress().getHostAddress() + ":" + finalClientAddr.getPort(), data);
                                    }
                                });
                        }
                    } else {
                        // 来自客户端
                        clientAddr = srcAddr;
                        DatagramPacket outPacket = new DatagramPacket(data, data.length, 
                                                                      InetAddress.getByName(remoteIp), remotePort);
                        udpSocket.send(outPacket);

                        final String finalRemoteIp = remoteIp;
                        final int finalRemotePort = remotePort;
                        runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    addPacket(timestamp, "客户端->远端", data.length,
                                              srcAddr.getAddress().getHostAddress() + ":" + srcAddr.getPort(),
                                              finalRemoteIp + ":" + finalRemotePort, data);
                                }
                            });
                        log("[UDP] 转发客户端数据到远端");
                    }
                } catch (SocketTimeoutException e) {
                    // 继续循环
                }
            }
        } catch (IOException e) {
            log("[UDP] 启动失败: " + e.getMessage());
        } finally {
            if (udpSocket != null && !udpSocket.isClosed()) {
                udpSocket.close();
            }
        }
    }

    private void stopForwarding() {
        isRunning.set(false);

        try {
            if (tcpServer != null) {
                tcpServer.close();
                tcpServer = null;
            }
        } catch (Exception e) {
            log("关闭TCP服务出错: " + e.getMessage());
        }

        try {
            if (udpSocket != null) {
                udpSocket.close();
                udpSocket = null;
            }
        } catch (Exception e) {
            log("关闭UDP服务出错: " + e.getMessage());
        }

        runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    btnStart.setEnabled(true);
                    btnStop.setEnabled(false);
                }
            });
        log("转发服务已停止");
    }

    @Override
    protected void onDestroy() {
        stopForwarding();
        if (executorService != null) {
            executorService.shutdown();
        }
        super.onDestroy();
    }
}
