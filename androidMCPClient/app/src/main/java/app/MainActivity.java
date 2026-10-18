package app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;


public class MainActivity extends Activity {

    private static final String TAG = "ShireikanMCP";
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final int REQUEST_NOTIFICATIONS = 1002;
    private static final String NOTIFICATION_CHANNEL = "mcp_status";

    private JSONObject fullConfig;
    private String PROTOCOL = "";
    private String HOST = "";
    private int PORT = 0;
    private String ENDPOINT = "";
    private String AUTH_TOKEN = "";
    private int CONNECT_TIMEOUT_MS = 10_000;
    private int READ_TIMEOUT_MS = 120_000;

    private static final boolean ALLOW_INSECURE_TLS = true;

    private static class ToolDef {
        String id;
        String label;
        List<String> inputs = new ArrayList<>();
        String output = "none";
        JSONObject params = new JSONObject();

        boolean acceptsAudio() { return inputs.contains("audio"); }
        boolean acceptsText() { return inputs.contains("text"); }
        boolean shouldCopy() { return "copy_clipboard".equals(output); }
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, ToolDef> tools = new LinkedHashMap<>();

    private TextView statusView;
    private TextView resultView;
    private EditText textInput;
    private Spinner toolSpinner;
    private Button recordButton;
    private MediaRecorder recorder;
    private File audioFile;
    private boolean recording;

    private void loadConfig() {
        try {
            File internalFile = new File(getFilesDir(), "config.json");
            String jsonContent;

            if (internalFile.exists()) {
                try (FileInputStream fis = new FileInputStream(internalFile)) {
                    byte[] buffer = new byte[fis.available()];
                    fis.read(buffer);
                    jsonContent = new String(buffer, StandardCharsets.UTF_8);
                }
            } else {
                try (InputStream is = getAssets().open("config.json")) {
                    byte[] buffer = new byte[is.available()];
                    is.read(buffer);
                    jsonContent = new String(buffer, StandardCharsets.UTF_8);
                }
                saveJsonToInternalStorage(jsonContent);
            }

            fullConfig = new JSONObject(jsonContent);

            if (fullConfig.has("server")) {
                JSONObject server = fullConfig.getJSONObject("server");
                PROTOCOL = server.optString("protocol", "https");
                HOST = server.optString("host", "");
                PORT = server.optInt("port", 0);
                ENDPOINT = server.optString("endpoint", "/call");
                AUTH_TOKEN = server.optString("token", "");
                CONNECT_TIMEOUT_MS = server.optInt("connect_timeout_ms", 10_000);
                READ_TIMEOUT_MS = server.optInt("timeout_ms", 60_000);
            }
        } catch (Exception ignored) {
        }
    }

    public static class ProcessTextActivity extends Activity {

        private static class ToolAction {
            String id;
            String label;
            String output;
            JSONObject params;
            List<String> inputs = new ArrayList<>();
        }

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            CharSequence text = getIntent().getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
            final String selectedText = (text != null) ? text.toString().trim() : "";
            List<ToolAction> allTools = new ArrayList<>();

            try (InputStream is = getAssets().open("actions.json")) {
                byte[] buf = new byte[is.available()];
                is.read(buf);
                JSONObject root = new JSONObject(new String(buf, StandardCharsets.UTF_8));
                JSONArray arr = root.getJSONArray("tools");

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    ToolAction a = new ToolAction();
                    a.id = obj.getString("tool_id");
                    a.label = obj.getString("label");
                    a.output = obj.optString("output", "toast");
                    a.params = obj.optJSONObject("params");

                    JSONArray inputsArr = obj.optJSONArray("inputs");
                    if (inputsArr != null) {
                        for (int j = 0; j < inputsArr.length(); j++) {
                            a.inputs.add(inputsArr.getString(j));
                        }
                    }
                    allTools.add(a);
                }
            } catch (Exception e) {
                Toast.makeText(this, "Erreur actions.json : " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            if (allTools.isEmpty()) {
                Toast.makeText(this, "Aucun outil défini dans actions.json", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            String[] labels = new String[allTools.size()];
            for (int i = 0; i < allTools.size(); i++) {
                labels[i] = allTools.get(i).label;
            }

            new android.app.AlertDialog.Builder(this)
                .setTitle("Assistant MCP")
                .setItems(labels, (dialog, which) -> {
                    ToolAction selectedTool = allTools.get(which);
                    executeToolCall(selectedTool, selectedText);
                })
                .setOnCancelListener(dialog -> finish())
                .show();
        }

        private void executeToolCall(ToolAction tool, String selectedText) {
            if (tool.inputs != null && tool.inputs.contains("audio")) {
                android.content.Intent intent = new android.content.Intent(this, MainActivity.class);
                intent.putExtra("tool_id", tool.id);
                intent.putExtra("auto_record", true);
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(intent);
                finish();
                return;
            }

            new Thread(() -> {
                try {
                    JSONObject serverConfig;
                    File internalConfig = new File(getFilesDir(), "config.json");
                    String configStr;
                    if (internalConfig.exists()) {
                        try (FileInputStream fis = new FileInputStream(internalConfig)) {
                            byte[] buf = new byte[fis.available()];
                            fis.read(buf);
                            configStr = new String(buf, StandardCharsets.UTF_8);
                        }
                    } else {
                        try (InputStream is = getAssets().open("config.json")) {
                            byte[] buf = new byte[is.available()];
                            is.read(buf);
                            configStr = new String(buf, StandardCharsets.UTF_8);
                        }
                    }
                    JSONObject root = new JSONObject(configStr);
                    serverConfig = root.getJSONObject("server");

                    String protocol = serverConfig.optString("protocol", "https");
                    String host = serverConfig.getString("host");
                    int port = serverConfig.getInt("port");
                    String endpoint = serverConfig.optString("endpoint", "/call");
                    String token = serverConfig.optString("token", "");
                    int timeout = serverConfig.optInt("timeout_ms", 60000);

                    URL url = new URL(protocol + "://" + host + ":" + port + endpoint);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();

                    if (conn instanceof HttpsURLConnection) {
                        javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                            new javax.net.ssl.X509TrustManager() {
                                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                                public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                                public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                            }
                        };
                        javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                        sc.init(null, trustAll, new java.security.SecureRandom());
                        ((HttpsURLConnection) conn).setSSLSocketFactory(sc.getSocketFactory());
                        ((HttpsURLConnection) conn).setHostnameVerifier((h, s) -> true);
                    }

                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(timeout);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                    conn.setRequestProperty("Accept", "text/plain, application/json");
                    if (!token.isEmpty()) {
                        conn.setRequestProperty("X-Auth-Token", token);
                    }

                    JSONObject body = new JSONObject();
                    body.put("tool", tool.id);

                    JSONObject args = new JSONObject();
                    args.put("text", selectedText);

                    if (tool.params != null) {
                        Iterator<String> keys = tool.params.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            args.put(k, tool.params.get(k));
                        }
                    }
                    body.put("args", args);

                    byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                    conn.setFixedLengthStreamingMode(b.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(b);
                    }

                    int code = conn.getResponseCode();
                    InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
                    String response = readAll(stream);
                    conn.disconnect();

                    if (code < 200 || code >= 300) {
                        throw new Exception("HTTP " + code + " : " + response);
                    }

                    runOnUiThread(() -> {
                        if ("copy_clipboard".equals(tool.output)) {
                            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText(tool.id, response));
                            }
                            Toast.makeText(getApplicationContext(), "Copié : " + response, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getApplicationContext(), response, Toast.LENGTH_LONG).show();
                        }
                        finish();
                    });

                } catch (Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(getApplicationContext(), "Erreur : " + e.getMessage(), Toast.LENGTH_LONG).show();
                        finish();
                    });
                }
            }).start();
        }
    }

    public static class AppWidget extends android.appwidget.AppWidgetProvider {
        @Override
        public void onUpdate(Context context, android.appwidget.AppWidgetManager appWidgetManager, int[] appWidgetIds) {
            super.onUpdate(context, appWidgetManager, appWidgetIds);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadConfig();
        createNotificationChannel();
        buildInterface();
        requestNotificationPermissionIfNeeded();
        handleIncomingIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private void handleIncomingIntent(android.content.Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("auto_record", false)) {
            String targetToolId = intent.getStringExtra("tool_id");
            if (targetToolId != null && toolSpinner != null) {
                int idx = 0;
                for (String id : tools.keySet()) {
                    if (id.equals(targetToolId)) {
                        toolSpinner.setSelection(idx);
                        break;
                    }
                    idx++;
                }
            }
            mainHandler.post(() -> onRecordClicked());
            return;
        }

        String action = intent.getAction();
        CharSequence sharedText = null;

        if (android.content.Intent.ACTION_PROCESS_TEXT.equals(action)) {
            sharedText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT);
        } else if (android.content.Intent.ACTION_SEND.equals(action)) {
            if ("text/plain".equals(intent.getType())) {
                sharedText = intent.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT);
            }
        }

        if (sharedText != null && sharedText.length() > 0) {
            String text = sharedText.toString().trim();
            if (textInput != null) {
                textInput.setText(text);
                setStatus("Texte reçu depuis la sélection.");
            }
        }
    }

    private void buildInterface() {
        tools.clear();
        try (InputStream is = getAssets().open("actions.json")) {
            byte[] buffer = new byte[is.available()];
            is.read(buffer);
            JSONObject root = new JSONObject(new String(buffer, StandardCharsets.UTF_8));
            JSONArray array = root.getJSONArray("tools");
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                ToolDef def = new ToolDef();
                def.id = obj.getString("tool_id");
                def.label = obj.getString("label");
                if (obj.has("output")) def.output = obj.getString("output");
                if (obj.has("params")) def.params = obj.getJSONObject("params");

                JSONArray inputs = obj.optJSONArray("inputs");
                if (inputs != null) {
                    for (int j = 0; j < inputs.length(); j++) {
                        def.inputs.add(inputs.getString(j));
                    }
                }
                tools.put(def.id, def);
            }
        } catch (Exception e) {
            ToolDef fallback = new ToolDef();
            fallback.id = "tool_ask_mcp";
            fallback.label = "Ask MCP";
            fallback.inputs.add("audio");
            fallback.inputs.add("text");
            tools.put(fallback.id, fallback);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText("Shireikan · Client MCP");
        title.setTextSize(22f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        statusView = new TextView(this);
        statusView.setText("Prêt · serveur " + PROTOCOL + "://" + HOST + ":" + PORT);
        statusView.setPadding(0, dp(10), 0, dp(10));
        root.addView(statusView, matchWrap());

        toolSpinner = new Spinner(this);
        List<String> labels = new ArrayList<>();
        for (ToolDef t : tools.values()) labels.add(t.label);
        labels.add("Update Server IP");

        toolSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
        toolSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if ("Update Server IP".equals(labels.get(position))) {
                    showUpdateIpDialog();
                    toolSpinner.setSelection(0);
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
        root.addView(toolSpinner, matchWrap());

        textInput = new EditText(this);
        textInput.setHint("Texte pour la commande…");
        textInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        textInput.setMinLines(2);
        root.addView(textInput, matchWrap());

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER);

        recordButton = new Button(this);
        recordButton.setText("Démarrer le micro");
        recordButton.setOnClickListener(v -> onRecordClicked());
        actions.addView(recordButton, weightWrap(1));

        Button sendButton = new Button(this);
        sendButton.setText("Envoyer le texte");
        sendButton.setOnClickListener(v -> sendSelectedText());
        actions.addView(sendButton, weightWrap(1));
        root.addView(actions, matchWrap());

        Button clipboardButton = new Button(this);
        clipboardButton.setText("Utiliser le presse-papier");
        clipboardButton.setOnClickListener(v -> sendClipboard());
        root.addView(clipboardButton, matchWrap());

        resultView = new TextView(this);
        resultView.setText("Résultat : —");
        resultView.setTextIsSelectable(true);
        resultView.setPadding(0, dp(16), 0, dp(16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(resultView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
    }

    private ToolDef getSelectedToolDef() {
        int index = toolSpinner.getSelectedItemPosition();
        if (index < 0 || index >= tools.size()) return null;
        return (ToolDef) tools.values().toArray()[index];
    }

    private void onRecordClicked() {
        if (recording) {
            stopRecordingAndSend();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_RECORD_AUDIO);
            return;
        }
        startRecording();
    }

    private void startRecording() {
        if (getSelectedToolDef() == null) {
            showToast("Veuillez choisir un outil valide.");
            return;
        }
        try {
            audioFile = File.createTempFile("shireikan_", ".m4a", getCacheDir());
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(128_000);
            recorder.setAudioSamplingRate(44_100);
            recorder.setOutputFile(audioFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            recording = true;
            recordButton.setText("Arrêter et envoyer");
            setStatus("Enregistrement local en cours…");
        } catch (Exception e) {
            releaseRecorder();
            deleteAudioFile();
            setStatus("Échec du micro : " + safeMessage(e));
        }
    }

    private void stopRecordingAndSend() {
        if (!recording || recorder == null) return;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            setStatus("Enregistrement trop court ou invalide.");
        } finally {
            releaseRecorder();
            recording = false;
            recordButton.setText("Démarrer le micro");
        }
        if (audioFile == null || !audioFile.exists() || audioFile.length() == 0) {
            deleteAudioFile();
            setStatus("Aucun audio à envoyer.");
            return;
        }

        ToolDef tool = getSelectedToolDef();
        if (tool == null || !tool.acceptsAudio()) {
            setStatus("Cet outil n'accepte pas l'audio. Audio annulé.");
            deleteAudioFile();
            return;
        }

        final File fileToSend = audioFile;
        setStatus("Encodage M4A puis envoi…");
        new Thread(() -> {
            try {
                String audioBase64 = encodeFile(fileToSend);
                JSONObject args = new JSONObject();
                args.put("audio_base64", audioBase64);
                args.put("format_ext", "m4a");

                Iterator<String> keys = tool.params.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    args.put(key, tool.params.get(key));
                }

                String result = callTool(tool.id, args);
                handleResult(result, tool.shouldCopy());
            } catch (Exception e) {
                handleError(e);
            } finally {
                deleteFile(fileToSend);
            }
        }).start();
    }

    private void sendSelectedText() {
        String text = textInput.getText().toString().trim();
        if (getSelectedToolDef() == null) {
            showToast("Veuillez choisir un outil valide.");
            return;
        }
        if (text.isEmpty()) {
            showToast("Saisissez un texte ou utilisez le presse-papier.");
            return;
        }
        sendTextToTool(text);
    }

    private void sendClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager == null || !manager.hasPrimaryClip()) {
            showToast("Presse-papier vide.");
            return;
        }
        CharSequence value = manager.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (value == null || value.toString().trim().isEmpty()) {
            showToast("Presse-papier vide.");
            return;
        }
        textInput.setText(value.toString());
        sendTextToTool(value.toString().trim());
    }

    private void sendTextToTool(String text) {
        ToolDef tool = getSelectedToolDef();
        if (tool == null) return;

        if (!tool.acceptsText()) {
            setStatus("L'outil " + tool.label + " n'accepte pas de texte.");
            return;
        }

        JSONObject args = new JSONObject();
        try {
            args.put("text", text);
            Iterator<String> keys = tool.params.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                args.put(key, tool.params.get(key));
            }
        } catch (Exception e) {
            handleError(e);
            return;
        }

        setStatus("Envoi à " + tool.label + "…");
        new Thread(() -> {
            try {
                handleResult(callTool(tool.id, args), tool.shouldCopy());
            } catch (Exception e) {
                handleError(e);
            }
        }).start();
    }

    private String callTool(String tool, JSONObject args) throws Exception {
        if (HOST.isEmpty() || PORT == 0) {
            throw new Exception("Incomplete configuration: check config.json (missing host or port).");
        }
        URL url = new URL(PROTOCOL + "://" + HOST + ":" + PORT + ENDPOINT);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (connection instanceof HttpsURLConnection && ALLOW_INSECURE_TLS) {
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection httpsConn = (HttpsURLConnection) connection;
            httpsConn.setSSLSocketFactory(sc.getSocketFactory());
            httpsConn.setHostnameVerifier((hostname, session) -> true);
        }
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        connection.setRequestProperty("Accept", "text/plain, application/json");
        connection.setRequestProperty("X-Auth-Token", AUTH_TOKEN);

        JSONObject body = new JSONObject();
        body.put("tool", tool);
        body.put("args", args);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readAll(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) throw new Exception("HTTP " + code + " : " + response);
        return response.trim();
    }

    private void handleResult(String result, boolean copy) {
        mainHandler.post(() -> {
            resultView.setText("Résultat :\n" + result);
            setStatus("Terminé");
            if (copy) copyToClipboard(result);
            showToast(copy ? "Résultat copié dans le presse-papier." : "Réponse reçue.");
        });
    }

    private void handleError(Exception error) {
        mainHandler.post(() -> {
            setStatus("Erreur : " + safeMessage(error));
            showToast("Erreur MCP : " + safeMessage(error));
        });
    }

    private static String encodeFile(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = input.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line).append('\n');
        }
        return builder.toString().trim();
    }

    private void copyToClipboard(String text) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (manager != null) manager.setPrimaryClip(ClipData.newPlainText("MCP", text));
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
    }

    private void deleteAudioFile() { deleteFile(audioFile); audioFile = null; }
    private static void deleteFile(File file) { if (file != null && file.exists()) file.delete(); }

    private void setStatus(String text) { mainHandler.post(() -> statusView.setText(text)); }
    private void showToast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private static String safeMessage(Exception e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams weightWrap(float weight) { return new LinearLayout.LayoutParams(0, -2, weight); }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, "État MCP", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_RECORD_AUDIO && (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED)) {
            showToast("Permission microphone refusée.");
        }
    }

    @Override
    protected void onDestroy() {
        if (recording) {
            releaseRecorder();
            recording = false;
        }
        deleteAudioFile();
        super.onDestroy();
    }

    private void saveJsonToInternalStorage(String content) {
        try {
            File internalFile = new File(getFilesDir(), "config.json");
            try (FileOutputStream fos = new FileOutputStream(internalFile, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void updateServerHost(String newHost) {
        try {
            HOST = newHost;
            if (fullConfig != null && fullConfig.has("server")) {
                fullConfig.getJSONObject("server").put("host", newHost);
                saveJsonToInternalStorage(fullConfig.toString(2));
                setStatus("Prêt · serveur " + PROTOCOL + "://" + HOST + ":" + PORT);
                showToast("Host updated: " + HOST);
            }
        } catch (Exception ignored) {
        }
    }

    private void showUpdateIpDialog() {
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("Update Server IP");

        final EditText input = new EditText(this);
        input.setHint("Host / IP");
        input.setText(HOST);
        builder.setView(input);

        builder.setPositiveButton("Save", (dialog, which) -> {
            String newIp = input.getText().toString().trim();
            if (!newIp.isEmpty()) {
                updateServerHost(newIp);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
    }
}