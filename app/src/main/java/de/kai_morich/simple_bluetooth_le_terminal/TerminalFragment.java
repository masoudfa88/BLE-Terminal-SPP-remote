package de.kai_morich.simple_bluetooth_le_terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.IBinder;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {


    private enum Connected {False, Pending, True}

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean pendingNewline = false, startBuffering = false, BufferSending = false;
    private String newline = TextUtil.newline_crlf, time = "";

    private File fileDir;
    private String path;

    private String inputST[];
    private String FileName = "test";
    private File fileEX;
    private FileOutputStream outputStream;
    private PrintWriter printWriter;
    StringBuilder stringBuilder = new StringBuilder();
    BufferedReader bufferedReader;
    private static final String ContinueSend = "cont", ReceiveFile = "receiveFile", PrintLog = "printLog", StartFile = "startFile", Transmit433 = "transmit433", Listen433 = "Listen433", closeFile = "closeFile", EndFile = "endFile", GetFile = "getFile";

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
        fileDir = getActivity().getExternalFilesDir(null);
        path = fileDir.getAbsolutePath();
//        Toast.makeText(getActivity(), path, Toast.LENGTH_SHORT).show();
//        Toast.makeText(getActivity(), String.valueOf(checkStorageState()), Toast.LENGTH_SHORT).show();

        //Toast.makeText(getActivity(), readFile(), Toast.LENGTH_SHORT).show();
    }

    public void openFile() {
        fileEX = new File(fileDir, FileName + ".sig");
        try {
            bufferedReader = new BufferedReader(new FileReader(fileEX));
        } catch (Exception e) {
            Log.e("error", e.toString());
        }
    }

    public void closeReadFile() {
        try {
            bufferedReader.close();
        } catch (Exception e) {
            Log.e("error", e.toString());
        }

    }

    public void readFile() {
        try {
            String line;
//            while ((line = bufferedReader.readLine()) != null) {
//                stringBuilder.append(line);
//                stringBuilder.append("\n");
//            }
            if ((line = bufferedReader.readLine()) != null) {
                send(line, false);
            } else {
                send("-", false);
            }
        } catch (Exception e) {
            Log.e("error", e.toString());
        }
    }

    public void makeFile(String format) {
        fileEX = new File(fileDir, FileName + format);
        try {
            outputStream = new FileOutputStream(fileEX);
        } catch (Exception e) {
            Log.e("error", String.valueOf(e));
        }
        printWriter = new PrintWriter(outputStream);
    }

    public void writeTextFile(String string) {

//        if (file3.exists())file3.delete();
        printWriter.println(string);
    }

    public void closeFile() {
        printWriter.flush();
        printWriter.close();
        try {
            outputStream.close();
        } catch (Exception e) {
            Log.e("error", String.valueOf(e));
        }
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if (service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try {
            getActivity().unbindService(this);
        } catch (Exception ignored) {
        }
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
        if (MySingletonClass.getInstance().gete22Change()) {
            send("@#config>A" + String.valueOf(MySingletonClass.getInstance().getairDataRate()) + "A", false);
            MySingletonClass.getInstance().setE22change(false);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if (initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString(), true));
        View getFile = view.findViewById(R.id.getFile);
        getFile.setOnClickListener(v -> send("getFile", true));
        View Listen433 = view.findViewById(R.id.Listen433);
        Listen433.setOnClickListener(v -> send("Listen433\n", true));
        View transmitBit = view.findViewById(R.id.transmitBit);
        transmitBit.setOnClickListener(v -> sendBitFile());
        View ListenFull = view.findViewById(R.id.ListenFull);
        ListenFull.setOnClickListener(v -> send("ListenFull", true));
        View transmit433 = view.findViewById(R.id.transmit433);
        transmit433.setOnClickListener(v -> send("transmit433", true));
        View sendFile = view.findViewById(R.id.sendFile);
        sendFile.setOnClickListener(v -> send("receiveFile", true));
        View printLog = view.findViewById(R.id.printLog);
        printLog.setOnClickListener(v -> send("printLog", true));
        View setName = view.findViewById(R.id.setName);
        setName.setOnClickListener(v -> sendText.setText("create-"));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {


//        MenuItem configChange = menu.add("Change E22 config");
//        configChange.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
//            @Override
//            public boolean onMenuItemClick(MenuItem menuItem) {
//                Intent intent = new Intent(getActivity().getApplicationContext(), ChangeConfig.class);
//                startActivity(intent);
//
//                return true;
//            }
//        });
        MenuItem setName = menu.add("Set name for File send or receive");
        setName.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                sendText.setText("create-");
                return true;
            }
        });
        MenuItem Listen433 = menu.add("Record 433 data in device");
        Listen433.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                send("Listen433", true);
                return true;
            }
        });
        MenuItem Transmit433 = menu.add("Transmit device in 433");
        Transmit433.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                send("transmit433", true);
                return true;
            }
        });
        MenuItem GetFile = menu.add("get saved data in device to File ");
        GetFile.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                send("getFile", true);
                return true;
            }
        });
        MenuItem ReceiveFile = menu.add("Send file to device ");
        ReceiveFile.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                send("receiveFile", true);
                return true;
            }
        });
        MenuItem PrintLog = menu.add("Reboot hardware");
        PrintLog.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem menuItem) {
                send("RESTART", true);
                return true;
            }
        });

    }

    public boolean checkStorageState() {
        String state = Environment.getExternalStorageState();
        if (state.equals(Environment.MEDIA_MOUNTED)) return true;
        else if (state.equals(Environment.MEDIA_MOUNTED_READ_ONLY))
            Toast.makeText(getActivity(), "readonly", Toast.LENGTH_SHORT).show();
        else
            Toast.makeText(getActivity(), "Exteranl storage is not available!!", Toast.LENGTH_SHORT).show();
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str, boolean show) {
        if (connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (str.contains("create")) {
            FileName = str.substring(str.indexOf("-") + 1, str.length());
        } else if (str.contains(ReceiveFile)) {
            openFile();
            BufferSending = true;
        }
        if (!str.equals("")) {
            try {
                String msg;
                byte[] data;
                if (hexEnabled) {
                    StringBuilder sb = new StringBuilder();
                    TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                    TextUtil.toHexString(sb, newline.getBytes());
                    msg = sb.toString();
                    data = TextUtil.fromHexString(msg);
                } else {
                    msg = str;
                    data = (str + newline).getBytes();
                }
                SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
                spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//                spn.setSpan(new StyleSpan(Typeface.BOLD), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (show) {
                    receiveText.append(spn);
                    time = new SimpleDateFormat("HH:mm").format(new Date());
                    spn.clear();
                    spn.append(time + "\n");
                    spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new AbsoluteSizeSpan(25), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                    sendText.setText("");
                    service.write(data);
                } else service.write(data);
            } catch (Exception e) {
                onSerialIoError(e);
            }
        }
    }

    private void sendBitFile() {
        fileEX = new File(fileDir, FileName + ".bit");
        try {
            bufferedReader = new BufferedReader(new FileReader(fileEX));
        } catch (Exception e) {
            Log.e("error", e.toString());
        }
        try {
            String line;
//            while ((line = bufferedReader.readLine()) != null) {
//                stringBuilder.append(line);
//                stringBuilder.append("\n");
//            }
            if ((line = bufferedReader.readLine()) != null) {
                send(line, true);
            } else {
                send("-", true);
            }
        } catch (Exception e) {
            Log.e("error", e.toString());
        }
        try {
            bufferedReader.close();
        } catch (Exception e) {
            Log.e("error", e.toString());
        }
    }

    private void receive(ArrayDeque<byte[]> datas) {
        String receiveMsg = null;
        SpannableStringBuilder spn = new SpannableStringBuilder();
        for (byte[] data : datas) {
            if (hexEnabled) {
                spn.append(TextUtil.toHexString(data)).append('\n');
            } else {
                String msg = new String(data);
                receiveMsg = msg;
                if (newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                    // don't show CR as ^M if directly before LF
                    msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                    // special handling if CR and LF come in separate fragments
                    if (pendingNewline && msg.charAt(0) == '\n') {
                        if (spn.length() >= 2) {
                            spn.delete(spn.length() - 2, spn.length());
                        } else {
                            Editable edt = receiveText.getEditableText();
                            if (edt != null && edt.length() >= 2)
                                edt.delete(edt.length() - 2, edt.length());
                        }
                    }
                    pendingNewline = msg.charAt(msg.length() - 1) == '\r';
                }
                spn.append(TextUtil.toCaretString(msg, newline.length() != 0));
            }
        }
        if (receiveMsg.contains(GetFile)) {
            spn.clear();
            spn.append("✓\n");
            spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spn.setSpan(new AbsoluteSizeSpan(20), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            receiveText.append("startFile\r\n");
            makeFile(".sig");
            startBuffering = true;
            send("cont", false);
        } else if (receiveMsg.contains(EndFile)) {
            spn.clear();
            spn.append("✓\n");
            spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_RIGHT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spn.setSpan(new AbsoluteSizeSpan(20), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            receiveText.append("endFile\r\n");
            closeFile();
            startBuffering = false;

        } else if (receiveMsg.contains("cont")) {
            readFile();

        } else if (receiveMsg.contains(closeFile)) {
            closeReadFile();
            send(closeFile, true);
        } else if (receiveMsg.contains("biT")) {
            makeFile(".bit");
            writeTextFile(receiveMsg);
            closeFile();
            receiveText.append(spn + "_saved");
        } else {
//                MediaPlayer recieveSound = MediaPlayer.create(getActivity().getApplicationContext(), R.raw.recieve);
//                recieveSound.start();
//            spn.setSpan(new AbsoluteSizeSpan(60), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_LEFT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            receiveText.append(spn);
//            time = new SimpleDateFormat("HH:mm").format(new Date());
//            spn.clear();
//            spn.append(time + "\n");
//            spn.setSpan(new AlignmentSpan.Standard(Layout.Alignment.ALIGN_LEFT), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            spn.setSpan(new AbsoluteSizeSpan(25), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
//            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorTimeText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (startBuffering) {
                writeTextFile(receiveMsg);
                send("cont", false);
            } else {
                receiveText.append(spn+"\n");
            }
        }

    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> datas = new ArrayDeque<>();
        datas.add(data);
        receive(datas);
    }

    public void onSerialRead(ArrayDeque<byte[]> datas) {
        receive(datas);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
        getActivity().onBackPressed();

    }

}