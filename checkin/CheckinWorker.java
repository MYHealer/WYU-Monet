import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class CheckinWorker {
    static String DATA_DIR = "/data/user/0/com.wps.koa/files";
    static String LOG = DATA_DIR + "/wps-miuix.log";
    static String PID_FILE = DATA_DIR + "/wps-checkin-timer.pid";

    public static void main(String[] args) {
        if (args.length > 0 && "schedule".equals(args[0])) {
            scheduleNext();
            return;
        }
        boolean force = args.length > 0 && "force".equals(args[0]);
        try {
            String cookies = readFile(DATA_DIR + "/wps-cookies.txt").trim();
            String csrf = readFile(DATA_DIR + "/wps-csrf.txt").trim();
            if (cookies.isEmpty()) { log("no cookies"); return; }

            String ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 WPS-Miuix/1.0";
            String cid = "Ds1zAQtq";
            String referer = "https://f.kdocs.cn/ksform/cw/w/" + cid;

            String inputName = "";
            String locationName = "";
            String department = "";
            String studentId = "";
            String cfg = readFile(DATA_DIR + "/wps-miuix-checkin.txt");
            String[] cfgLines = cfg.split("\n");
            if (cfgLines.length >= 7 && !cfgLines[6].trim().isEmpty()) {
                locationName = cfgLines[6].trim();
            }
            String fields = "";
            String values = "";
            for (String line : readFile(DATA_DIR + "/wps-checkin-params.txt").split("\n")) {
                if (line.startsWith("inputName=")) inputName = line.substring(10);
                if (line.startsWith("locationName=") && locationName.isEmpty()) locationName = line.substring(13);
                if (line.startsWith("department=")) department = line.substring(11);
                if (line.startsWith("studentId=")) studentId = line.substring(10);
                if (line.startsWith("campaign=")) {
                    String c = line.substring(9).trim();
                    if (!c.isEmpty()) cid = c;
                }
                if (line.startsWith("fields=")) fields = line.substring(7).trim();
                if (line.startsWith("values=")) values = line.substring(7).trim();
            }
            if (cid.isEmpty()) { log("no campaign"); return; }
            referer = "https://f.kdocs.cn/ksform/cw/w/" + cid;
            if (inputName.isEmpty()) { log("no inputName"); return; }

            if (department.isEmpty() || studentId.isEmpty() || fields.isEmpty() || values.isEmpty()) {
                try {
                    String answersResp = post("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid + "/answers/list",
                        "{\"page\":1,\"pageSize\":5}", cookies, csrf, ua, referer);
                    java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\"clockinDepartment\":\\{\"type\":\"input\",\"strValue\":\"([^\"]*)\"")
                        .matcher(answersResp);
                    if (m.find() && department.isEmpty()) department = m.group(1);
                    m = java.util.regex.Pattern
                        .compile("\"clockinStudentId\":\\{\"type\":\"input\",\"strValue\":\"([^\"]*)\"")
                        .matcher(answersResp);
                    if (m.find() && studentId.isEmpty()) studentId = m.group(1);
                    java.util.regex.Matcher fm = java.util.regex.Pattern
                        .compile("\"clockinInfoValue\":\\{((?:\"clockin[A-Za-z0-9]+\":\\{[^}]*\\},?)+)")
                        .matcher(answersResp);
                    if (fm.find()) {
                        if (fields.isEmpty()) {
                            java.util.regex.Matcher km = java.util.regex.Pattern
                                .compile("\"(clockin[A-Za-z0-9]+)\":\\{")
                                .matcher(fm.group(1));
                            StringBuilder sb = new StringBuilder();
                            while (km.find()) {
                                if (sb.length() > 0) sb.append(",");
                                sb.append(km.group(1));
                            }
                            if (sb.length() > 0) fields = sb.toString();
                        }
                        if (values.isEmpty()) {
                            values = "{" + fm.group(1) + "}";
                        }
                    }
                } catch (Throwable t) { log("INFO_ANSWERS=" + t.getMessage()); }
            }
            log("start name=" + inputName + " loc=" + locationName + " dept=" + department + " sid=" + studentId + " fields=" + fields + (force ? " FORCE" : ""));

            // STEP 1: check today
            if (!force) {
                String today = new java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(new java.util.Date());
                String answersResp = post("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid + "/answers/list",
                    "{\"page\":1,\"pageSize\":5}", cookies, csrf, ua, referer);
                try { writeFile(DATA_DIR + "/answers-debug.json", answersResp, false); } catch (Throwable ignored) {}
                if (answersResp.contains("\"aid\":\"" + today)) {
                    log("already done today");
                    return;
                }
            }

            // STEP 2: auth
            long ts = System.currentTimeMillis();
            String authResp = post("https://account.kdocs.cn/p/auth/check",
                "{\"_t\":" + ts + "}", cookies, csrf, ua, referer);
            if (!authResp.contains("nickname")) { log("auth failed: " + authResp.substring(0, Math.min(100, authResp.length()))); return; }
            log("auth OK");

            // STEP 3: get form
            String formResp = get("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid, cookies, csrf, ua, referer);
            try { writeFile(DATA_DIR + "/form-debug.json", formResp, false); } catch (Throwable ignored) {}
            String clockinField = "";
            String commitOptionId = "";
            String commitOptionText = "";
            java.util.regex.Matcher fieldMatcher = java.util.regex.Pattern
                .compile("\"([a-z0-9]+)\":\\{\"type\":\"clockinInfo\"")
                .matcher(formResp);
            if (fieldMatcher.find()) {
                clockinField = fieldMatcher.group(1);
            }
            if (fields.isEmpty()) {
                java.util.regex.Matcher cm = java.util.regex.Pattern
                    .compile("\"(clockin[A-Z][A-Za-z]*)\"")
                    .matcher(formResp);
                StringBuilder sb = new StringBuilder();
                String[] skip = {"clockinInfo", "clockinInfoValue", "clockinStatus"};
                while (cm.find()) {
                    String f = cm.group(1);
                    boolean s = false;
                    for (String x : skip) if (f.equals(x)) { s = true; break; }
                    if (s) continue;
                    if (sb.indexOf(f) >= 0) continue;
                    if (sb.length() > 0) sb.append(",");
                    sb.append(f);
                }
                if (sb.length() > 0) fields = sb.toString();
            }
            int optIdx = formResp.indexOf("\"commitConfig\"");
            if (optIdx > 0) {
                int idIdx = formResp.indexOf("\"id\":\"", optIdx);
                if (idIdx > 0) {
                    idIdx += 6;
                    int idEnd = formResp.indexOf("\"", idIdx);
                    commitOptionId = formResp.substring(idIdx, idEnd);
                }
                int textIdx = formResp.indexOf("\"text\":\"", optIdx);
                if (textIdx > 0) {
                    textIdx += 8;
                    int textEnd = formResp.indexOf("\"", textIdx);
                    commitOptionText = formResp.substring(textIdx, textEnd);
                }
            }
            if (clockinField.isEmpty() || commitOptionId.isEmpty()) {
                log("parse failed field=" + clockinField + " opt=" + commitOptionId);
                return;
            }
            log("field=" + clockinField + " opt=" + commitOptionId);

            // STEP 4: precheck
            String preResp = post("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid + "/precheck",
                "{}", cookies, csrf, ua, referer);
            if (preResp.contains("\u65f6\u95f4") || preResp.contains("\u5468\u671f") || preResp.contains("\u9650\u5236")) {
                log("precheck blocked: " + preResp.substring(0, Math.min(100, preResp.length())));
                return;
            }

            // STEP 5: preset key
            String keyId = "";
            try {
                String keyResp = post("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid + "/preset/key/check",
                    "{\"key\":\"" + inputName + "\"}", cookies, csrf, ua, referer);
                int ki = keyResp.indexOf("\"keyId\":\"");
                if (ki > 0) {
                    ki += 9;
                    int ke = keyResp.indexOf("\"", ki);
                    keyId = keyResp.substring(ki, ke);
                }
                if (keyId.isEmpty()) {
                    log("keyResp: " + keyResp.substring(0, Math.min(100, keyResp.length())));
                }
            } catch (Throwable t) {
                log("preset check skip: " + t.getMessage());
            }
            log("keyId='" + keyId + "'");

            // STEP 6: submit with org.json
            if (fields == null || fields.isEmpty()) {
                fields = "clockinName,clockinLocation,clockinDepartment,clockinStudentId,clockinAcademicGraduates,clockinMajor";
            }

            // parse values JSON (handle \/ escape)
            JSONObject valuesJson = null;
            if (values != null && !values.isEmpty()) {
                try {
                    valuesJson = new JSONObject(values);
                } catch (Throwable t) {
                    log("VALUES_PARSE_FAIL: " + t.getMessage());
                }
            }

            // build clockinInfoValue with ALL 6 sub-fields
            JSONObject clockinInfoValue = new JSONObject();
            for (String f : fields.split(",")) {
                f = f.trim();
                if (f.isEmpty()) continue;
                try {
                    JSONObject sub = new JSONObject();
                    sub.put("type", "input");
                    boolean hasValue = true;
                    if ("clockinName".equals(f)) sub.put("strValue", inputName);
                    else if ("clockinLocation".equals(f)) sub.put("strValue", locationName);
                    else if ("clockinDepartment".equals(f)) sub.put("strValue", department);
                    else if ("clockinStudentId".equals(f)) sub.put("strValue", studentId);
                    else {
                        String hv = "";
                        if (valuesJson != null) {
                            JSONObject v = valuesJson.optJSONObject(f);
                            if (v != null) hv = v.optString("strValue", "");
                        }
                        if (!hv.isEmpty()) {
                            sub.put("strValue", hv);
                        } else {
                            hasValue = false;
                        }
                    }
                    if (hasValue) sub.put("isManualInput", false);
                    clockinInfoValue.put(f, sub);
                } catch (Throwable ignored) {}
            }
            log("clockinInfoValue keys: " + clockinInfoValue.names().toString());

            JSONObject fieldAnswer = new JSONObject();
            fieldAnswer.put("type", "clockinInfo");
            fieldAnswer.put("clockinInfoValue", clockinInfoValue);
            JSONObject answers = new JSONObject();
            answers.put(clockinField, fieldAnswer);
            JSONObject commitInfo = new JSONObject();
            commitInfo.put("optionId", commitOptionId);
            commitInfo.put("optionText", commitOptionText);
            JSONObject clockinInfoProp = new JSONObject();
            clockinInfoProp.put("clockinStatus", "normal");
            clockinInfoProp.put("outOfPeriodDescription", "");
            JSONObject answersProp = new JSONObject();
            if (!keyId.isEmpty()) answersProp.put("presetKeyId", keyId);
            answersProp.put("presetKeyValue", inputName);
            answersProp.put("commitInfo", commitInfo);
            answersProp.put("clockinInfo", clockinInfoProp);
            JSONObject answerJson = new JSONObject();
            answerJson.put("answers", answers);
            answerJson.put("consumeTime", 10);
            answerJson.put("answersProperty", answersProp);
            JSONObject payload = new JSONObject();
            payload.put("answerJson", answerJson);
            payload.put("_t", ts);

            String body = payload.toString();
            log("SUBMIT_BODY: " + body.substring(0, Math.min(400, body.length())));

            String result = post("https://f-api.kdocs.cn/ksform/api/v3/campaign/" + cid,
                body, cookies, csrf, ua, referer);
            if (result.contains("\"code\":0") || result.contains("\"code\": 0")) {
                String time = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date());
                writeFile(DATA_DIR + "/wps-miuix-checkin-log.txt", time + " | ROOT | " + locationName + "\n", true);
                log("SUCCESS");
            } else {
                log("submit failed: " + result.substring(0, Math.min(200, result.length())));
            }
        } catch (Throwable t) {
            log("ERROR: " + t.getMessage());
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            log("STACK: " + sw.toString().substring(0, Math.min(500, sw.toString().length())));
        } finally {
            if (!force) {
                scheduleNext();
            }
        }
    }

    static void scheduleNext() {
        try {
            String cfg = readFile(DATA_DIR + "/wps-miuix-checkin.txt");
            String[] lines = cfg.split("\n");
            if (lines.length < 3) return;
            if (!"true".equals(lines[0].trim())) return;

            if (timerAlive()) {
                log("scheduleNext: timer already alive, skip");
                return;
            }

            String scriptFile = DATA_DIR + "/wps-checkin-loop.sh";
            StringBuilder sb = new StringBuilder();
            sb.append("#!/system/bin/sh\n");
            sb.append("PIDFILE=").append(DATA_DIR).append("/wps-checkin-timer.pid\n");
            sb.append("if [ -f \"$PIDFILE\" ]; then\n");
            sb.append("  OLD=$(cat \"$PIDFILE\")\n");
            sb.append("  if [ -n \"$OLD\" ] && kill -0 \"$OLD\" 2>/dev/null; then\n");
            sb.append("    echo \"already running pid=$OLD\"; exit 0\n");
            sb.append("  fi\n");
            sb.append("fi\n");
            sb.append("echo $$ > \"$PIDFILE\"\n");
            sb.append("while true; do\n");
            sb.append("  CFG=$(cat ").append(DATA_DIR).append("/wps-miuix-checkin.txt)\n");
            sb.append("  ENABLED=$(echo \"$CFG\" | sed -n '1p' | tr -d ' \\r')\n");
            sb.append("  if [ \"$ENABLED\" != \"true\" ]; then sleep 300; continue; fi\n");
            sb.append("  HOUR=$(echo \"$CFG\" | sed -n '2p' | tr -d ' \\r')\n");
            sb.append("  MINUTE=$(echo \"$CFG\" | sed -n '3p' | tr -d ' \\r')\n");
            sb.append("  NOW=$(date +%H%M)\n");
            sb.append("  TARGET=$(printf '%02d%02d' \"$HOUR\" \"$MINUTE\")\n");
            sb.append("  TODAY=$(date +%Y-%m-%d)\n");
            sb.append("  DONE=$(grep -c \"$TODAY\" ").append(DATA_DIR).append("/wps-miuix-checkin-log.txt 2>/dev/null)\n");
            sb.append("  if [ \"$DONE\" != \"0\" ]; then\n");
            sb.append("    sleep 300; continue;\n");
            sb.append("  fi\n");
            sb.append("  if [ \"$NOW\" -ge \"$TARGET\" ]; then\n");
            sb.append("    timeout 120 env CLASSPATH=/data/local/tmp/CheckinWorker.dex app_process / CheckinWorker\n");
            sb.append("    sleep 120\n");
            sb.append("  else\n");
            sb.append("    sleep 30\n");
            sb.append("  fi\n");
            sb.append("done\n");
            writeFile(scriptFile, sb.toString(), false);
            Runtime.getRuntime().exec(new String[]{"chmod", "755", scriptFile}).waitFor();
            Process p = Runtime.getRuntime().exec(new String[]{"sh", "-c",
                "nohup sh " + scriptFile + " > /dev/null 2>&1 &"});
            p.waitFor();
            log("scheduleNext: polling timer dispatched");
        } catch (Throwable t) {
            log("scheduleNext ERROR: " + t.getMessage());
        }
    }

    static boolean timerAlive() {
        try {
            String pid = readFile(PID_FILE).trim();
            if (pid.isEmpty()) return false;
            Process p = Runtime.getRuntime().exec(new String[]{"kill", "-0", pid});
            return p.waitFor() == 0;
        } catch (Throwable t) { return false; }
    }

    static String get(String url, String cookies, String csrf, String ua, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Cookie", cookies);
        conn.setRequestProperty("X-CSRF-Token", csrf);
        conn.setRequestProperty("User-Agent", ua);
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Referer", referer);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        return readResp(conn);
    }

    static String post(String url, String body, String cookies, String csrf, String ua, String referer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Cookie", cookies);
        conn.setRequestProperty("X-CSRF-Token", csrf);
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Origin", "https://f.kdocs.cn");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("User-Agent", ua);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(b.length);
        OutputStream os = conn.getOutputStream();
        os.write(b);
        os.close();
        return readResp(conn);
    }

    static String readResp(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        r.close();
        conn.disconnect();
        return sb.toString();
    }

    static String readFile(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) return "";
        BufferedReader r = new BufferedReader(new FileReader(f));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) { if (sb.length() > 0) sb.append("\n"); sb.append(line); }
        r.close();
        return sb.toString();
    }

    static void writeFile(String path, String content, boolean append) throws Exception {
        FileWriter fw = new FileWriter(path, append);
        fw.write(content);
        fw.close();
    }

    static void log(String msg) {
        try {
            String time = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
            FileWriter fw = new FileWriter(LOG, true);
            fw.write(time + " ROOT_WORKER: " + msg + "\n");
            fw.close();
        } catch (Exception ignored) {}
    }
}