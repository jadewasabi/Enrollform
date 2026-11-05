package enrollmentform;


//public class Enrollmentform {

    import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Basic Education Enrollment Form (DepEd, rev. 06/01/2025) as a Java app.
 * Pure JDK, no libraries. Requires Java 17+.
 *
 * Run:   java Enrollmentform.java      (keep index.html in the same folder)
 * Open:  http://localhost:8080            login / create account (index.html)
 *        http://localhost:8080/enroll      enrollment form (any signed-in user)
 *        http://localhost:8080/records     saved enrollments (ADMIN ONLY)
 *
 * Admin account: set ENROLL_ADMIN_EMAIL and ENROLL_ADMIN_PASSWORD before running
 * (defaults below are for testing only - change them).
 * Storage (pick one):
 *   - Google Sheets (survives restarts/redeploys on any host): set GOOGLE_SHEET_ID and
 *     GOOGLE_SERVICE_ACCOUNT_JSON (the key file's contents, or that text base64-encoded)
 *     or GOOGLE_SERVICE_ACCOUNT_FILE (path to the key file). Tabs "Enrollments" and "Users"
 *     are created automatically.
 *   - CSV files (default): enrollments.csv and users.csv inside DATA_DIR (default: current folder).
 * On a host like Render, PORT is read automatically.
 */
public class Enrollmentform {

    static final Path DATA = Paths.get(env("DATA_DIR", "."));
    static final Path DB = DATA.resolve("enrollments.csv");
    static final Path USERS = DATA.resolve("users.csv");
    static final Path INDEX = findIndex();
    static Path findIndex() {
        for (String f : new String[]{"index.html", "src/index.html", "src/enrollmentform/index.html"})
            if (Files.exists(Paths.get(f))) return Paths.get(f);
        return Paths.get("index.html");
    }
    static final String ADMIN_EMAIL = env("ENROLL_ADMIN_EMAIL", "admin@school.com").toLowerCase();
    static final String ADMIN_PASSWORD = env("ENROLL_ADMIN_PASSWORD", "admin123");
    static final Map<String, String[]> SESSIONS = new ConcurrentHashMap<>(); // token -> {email, role}
    static final SecureRandom RNG = new SecureRandom();
    static class BadRequest extends RuntimeException { BadRequest(String m) { super(m); } }
    static String env(String k, String d) { String v = System.getenv(k); return v == null || v.isBlank() ? d : v; }
    static final List<String> KEYS = new ArrayList<>();
    static final Map<String, String> LABELS = new HashMap<>();
    static String FORM;

    // ---------------------------------------------------------------- server

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(env("PORT", "8080"));
        FORM = page("Enrollment Form", form(), JS);
        initStorage();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ex -> {
            try {
                String path = ex.getRequestURI().getPath(), method = ex.getRequestMethod();
                boolean get = method.equals("GET"), post = method.equals("POST");
                String[] user = currentUser(ex);
                boolean admin = user != null && user[1].equals("admin");

                if (path.equals("/") && get) {
                    if (user != null) redirect(ex, admin ? "/records" : "/enroll");
                    else if (!Files.exists(INDEX)) send(ex, 500, page("Missing file",
                            "<section class='card'><h2>index.html not found</h2><p>Put index.html in the folder you run the program from.</p></section>", ""));
                    else send(ex, 200, Files.readString(INDEX, StandardCharsets.UTF_8));
                } else if (path.equals("/signup") && post) json(ex, signup(ex));
                else if (path.equals("/signin") && post) json(ex, signin(ex));
                else if (path.equals("/logout")) { logout(ex); redirect(ex, "/"); }
                else if (path.equals("/enroll") && get) {
                    if (user == null) redirect(ex, "/");
                    else send(ex, 200, FORM.replace("{{NAV}}", nav(admin)));
                } else if (path.equals("/submit") && post) {
                    if (user == null) redirect(ex, "/");
                    else send(ex, 200, submit(ex, admin));
                } else if (path.equals("/records")) {
                    if (user == null) redirect(ex, "/");
                    else if (!admin) send(ex, 403, page("Admins only",
                            "<section class='card'><h2>Admins only</h2><p class='muted'>This page is only available to the administrator account.</p>"
                                    + "<div class='actions'><a class='btn primary' href='/enroll'>Back to the form</a><a class='btn' href='/logout'>Log out</a></div></section>", ""));
                    else send(ex, 200, records());
                } else send(ex, 404, page("Not found",
                        "<section class='card'><h2>Page not found</h2><a class='btn' href='/'>Back to the form</a></section>", ""));
            } catch (BadRequest e) {
                send(ex, 400, page("Cannot submit",
                        "<section class='card'><h2>Cannot submit yet</h2><p>" + esc(e.getMessage())
                                + "</p><a class='btn' href='javascript:history.back()'>Go back</a></section>", ""));
            } catch (Exception e) {
                e.printStackTrace();
                send(ex, 500, page("Error",
                        "<section class='card'><h2>Something went wrong</h2><p>" + esc(String.valueOf(e.getMessage()))
                                + "</p><a class='btn' href='/'>Back to the form</a></section>", ""));
            }
        });
        server.start();
        System.out.println("Enrollment form running at http://localhost:" + port);
        System.out.println("Admin sign-in: " + ADMIN_EMAIL + (ADMIN_PASSWORD.equals("admin123")
                ? "  (default password - set ENROLL_ADMIN_PASSWORD before real use)" : ""));
    }

    static String nav(boolean admin) {
        return "<div class='actions' style='margin:0'>" + (admin ? "<a class='btn' href='/records'>Records</a>" : "")
                + "<a class='btn' href='/logout'>Log out</a></div>";
    }

    // ----------------------------------------------------------- http helpers

    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void json(HttpExchange ex, String j) throws IOException {
        byte[] b = j.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    static void redirect(HttpExchange ex, String to) throws IOException {
        ex.getResponseHeaders().set("Location", to);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    static Map<String, List<String>> parseForm(String body) {
        Map<String, List<String>> in = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            String[] kv = pair.split("=", 2);
            String k = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String v = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            in.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return in;
    }

    static String first(Map<String, List<String>> in, String k) {
        List<String> l = in.get(k);
        return l == null || l.isEmpty() ? "" : l.get(0);
    }

    // ------------------------------------------------------- accounts/sessions

    static String ok(String redirectTo) { return "{\"ok\":true,\"redirect\"😕"" + redirectTo + "\"}"; }
    static String err(String msg) { return "{\"ok\":false,\"error\"😕"" + msg + "\"}"; }

    static synchronized String signup(HttpExchange ex) throws IOException {
        Map<String, List<String>> in = parseForm(body(ex));
        String name = first(in, "name").trim().replaceAll("\\s+", " ");
        String email = first(in, "email").trim().toLowerCase();
        String pw = first(in, "password");
        if (name.isEmpty()) return err("Please enter your name.");
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) return err("Please enter a valid email address.");
        if (pw.length() < 6) return err("Password must be at least 6 characters.");
        if (email.equals(ADMIN_EMAIL) || findUser(email) != null) return err("That email is already registered.");
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        addUser(email, Base64.getEncoder().encodeToString(salt), hash(pw, salt), name,
                LocalDateTime.now().withNano(0).toString());
        startSession(ex, email, "user");
        return ok("/enroll");
    }

    static String signin(HttpExchange ex) throws IOException {
        Map<String, List<String>> in = parseForm(body(ex));
        String email = first(in, "email").trim().toLowerCase(), pw = first(in, "password");
        String bad = err("Incorrect email or password.");
        if (email.equals(ADMIN_EMAIL)) {
            if (!MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8))) return bad;
            startSession(ex, email, "admin");
            return ok("/records");
        }
        String[] u = findUser(email);
        if (u == null) return bad;
        byte[] salt = Base64.getDecoder().decode(u[1]);
        if (!MessageDigest.isEqual(hash(pw, salt).getBytes(StandardCharsets.UTF_8), u[2].getBytes(StandardCharsets.UTF_8))) return bad;
        startSession(ex, email, "user");
        return ok("/enroll");
    }

    static String hash(String pw, byte[] salt) {
        try {
            var spec = new PBEKeySpec(pw.toCharArray(), salt, 120_000, 256);
            return Base64.getEncoder().encodeToString(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (GeneralSecurityException e) { throw new RuntimeException(e); }
    }

    static synchronized String[] findUser(String email) throws IOException {
        for (List<String> c : USE_SHEETS ? readTab(TAB_USERS + "!A:E") : readCsv(USERS))
            if (c.size() >= 3 && c.get(0).equals(email)) return new String[]{c.get(0), c.get(1), c.get(2)};
        return null;
    }

    static synchronized void addUser(String email, String salt, String hash, String name, String created) throws IOException {
        if (USE_SHEETS) { appendSheet(TAB_USERS, List.of(email, salt, hash, name, created)); return; }
        String line = csv(email) + "," + csv(salt) + "," + csv(hash) + "," + csv(name) + "," + csv(created) + "\n";
        Files.writeString(USERS, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String sessionToken(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Cookie");
        if (h == null) return null;
        for (String p : h.split(";")) { p = p.trim(); if (p.startsWith("sid=")) return p.substring(4); }
        return null;
    }

    static String[] currentUser(HttpExchange ex) {
        String t = sessionToken(ex);
        return t == null ? null : SESSIONS.get(t);
    }

    static void startSession(HttpExchange ex, String email, String role) {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String t = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
        SESSIONS.put(t, new String[]{email, role});
        ex.getResponseHeaders().add("Set-Cookie", "sid=" + t + "; Path=/; HttpOnly; SameSite=Lax");
    }

    static void logout(HttpExchange ex) {
        String t = sessionToken(ex);
        if (t != null) SESSIONS.remove(t);
        ex.getResponseHeaders().add("Set-Cookie", "sid=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0");
    }

    /** The certification column changed, so an enrollments.csv with the old layout is set aside instead of mixed with new rows. */
    static void archiveOldCsv() throws IOException {
        if (!Files.exists(DB)) return;
        List<String> lines = Files.readAllLines(DB, StandardCharsets.UTF_8);
        if (!lines.isEmpty() && lines.get(0).equals("timestamp," + String.join(",", KEYS))) return;
        Path old = DATA.resolve("enrollments-old-" + LocalDateTime.now().withNano(0).toString().replace(':', '-') + ".csv");
        Files.move(DB, old);
        System.out.println("enrollments.csv had the old layout; moved to " + old);
    }

    // ---------------------------------------------------------- submit/save

    static String submit(HttpExchange ex, boolean admin) throws IOException {
        Map<String, List<String>> in = parseForm(body(ex));
        if (!in.containsKey("certify"))
            throw new BadRequest("Please tick the certification checkbox before submitting.");
        Map<String, String> row = new LinkedHashMap<>();
        for (String k : KEYS) {
            String v = String.join("; ", in.getOrDefault(k, List.of())).replaceAll("\\s+", " ").trim().toUpperCase();
            row.put(k, v);
        }
        saveRow(row);

        StringBuilder sb = new StringBuilder("<section class='card'><h2>Enrollment received</h2>")
                .append("<p class='muted'>Print this page and hand it to the registrar or class adviser.</p><dl class='sum'>");
        for (var e : row.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sb.append("<dt>").append(esc(LABELS.getOrDefault(e.getKey(), e.getKey()))).append("</dt><dd>")
                    .append(esc(e.getValue())).append("</dd>");
        }
        sb.append("</dl><div class='actions'><button class='btn primary' onclick='window.print()'>Print</button>")
                .append("<a class='btn' href='/enroll'>Enroll another learner</a>")
                .append(admin ? "<a class='btn' href='/records'>View records</a>" : "")
                .append("<a class='btn' href='/logout'>Log out</a></div></section>");
        return page("Enrollment received", sb.toString(), "");
    }

    static synchronized void saveRow(Map<String, String> row) throws IOException {
        String stamp = LocalDateTime.now().withNano(0).toString();
        if (USE_SHEETS) {
            List<String> cells = new ArrayList<>();
            cells.add(stamp);
            cells.addAll(row.values());
            appendSheet(TAB_ENROLL, cells);
            return;
        }
        StringBuilder sb = new StringBuilder();
        if (!Files.exists(DB)) sb.append("timestamp,").append(String.join(",", KEYS)).append('\n');
        sb.append(csv(stamp));
        for (String v : row.values()) sb.append(',').append(csv(v));
        sb.append('\n');
        Files.writeString(DB, sb.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String csv(String v) { return "\"" + v.replace("\"", "\"\"") + "\""; }

    static List<String> parseCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (q) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') q = false;
                else cur.append(c);
            } else if (c == '"') q = true;
            else if (c == ',') { out.add(cur.toString()); cur.setLength(0); }
            else cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    static String records() throws IOException {
        StringBuilder sb = new StringBuilder("<section class='card'><h2>Saved enrollments</h2>");
        List<List<String>> all = USE_SHEETS ? readTab(TAB_ENROLL + "!A:ZZ") : readCsv(DB);
        if (all.size() < 2) {
            sb.append("<p class='muted'>Nothing saved yet. Submit the form and the learner will show up here.</p>");
        } else {
            List<String> head = all.get(0);
            String[] cols = {"timestamp", "lrn", "lastName", "firstName", "grade", "syFrom", "fatherContact", "motherContact"};
            String[] names = {"Saved at", "LRN", "Last name", "First name", "Grade", "School year", "Father's no.", "Mother's no."};
            sb.append("<div class='scroll'><table><tr>");
            for (String n : names) sb.append("<th>").append(n).append("</th>");
            sb.append("</tr>");
            for (int i = all.size() - 1; i >= 1; i--) {
                List<String> r = all.get(i);
                sb.append("<tr>");
                for (String c : cols) {
                    int idx = head.indexOf(c);
                    sb.append("<td>").append(idx >= 0 && idx < r.size() ? esc(r.get(idx)) : "").append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</table></div><p class='muted'>").append(all.size() - 1)
                    .append(USE_SHEETS ? " learner(s). Full details are in your Google Sheet." : " learner(s). Full details are in enrollments.csv.")
                    .append("</p>");
        }
        sb.append("<div class='actions'><a class='btn primary' href='/enroll'>New enrollment</a><a class='btn' href='/logout'>Log out</a></div></section>");
        return page("Records", sb.toString(), "");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------- HTML builders

    static String in(String name, String label, String type, String attrs) {
        KEYS.add(name);
        LABELS.put(name, label);
        return "<label class='f'><span>" + label + "</span><input name='" + name + "' type='" + type + "' " + attrs + "></label>";
    }

    static String sel(String name, String label, String... options) {
        KEYS.add(name);
        LABELS.put(name, label);
        StringBuilder sb = new StringBuilder("<label class='f'><span>" + label + "</span><select name='" + name + "'><option value=''>Select</option>");
        for (String o : options) sb.append("<option>").append(o).append("</option>");
        return sb.append("</select></label>").toString();
    }

    static String opts(String name, String legend, String type, String... labels) {
        if (!KEYS.contains(name)) KEYS.add(name);
        LABELS.put(name, legend);
        StringBuilder sb = new StringBuilder("<fieldset class='fs'><legend>" + legend + "</legend><div class='opts'>");
        for (String l : labels)
            sb.append("<label class='o ").append(type).append("'><input type='").append(type).append("' name='").append(name)
                    .append("' value='").append(l).append("'><i></i><b>").append(l).append("</b></label>");
        return sb.append("</div></fieldset>").toString();
    }

    static String grid(int cols, String... items) {
        return "<div class='grid g" + cols + "'>" + String.join("", items) + "</div>";
    }

    static String cond(String rule, String content) { return "<div class='cond' data-if='" + rule + "'>" + content + "</div>"; }

    static String sec(String title, String content) { return "<section class='card'><h2>" + title + "</h2>" + content + "</section>"; }

    static String sub(String title, String content) { return "<div class='sub'><h3>" + title + "</h3>" + content + "</div>"; }

    static String addr(String p, String who) {
        return grid(3,
                in(p + "House", "House no.", "text", ""),
                in(p + "Street", "Sitio / Street name", "text", ""),
                in(p + "Barangay", "Barangay", "text", ""),
                in(p + "City", "Municipality / City", "text", ""),
                in(p + "Province", "Province", "text", ""),
                in(p + "Country", "Country", "text", "value='Philippines'"),
                in(p + "Zip", "Zip code", "text", "inputmode='numeric' maxlength='4'"));
    }

    static String person(String p, String title) {
        return sub(title, grid(4,
                in(p + "Last", "Last name", "text", ""),
                in(p + "First", "First name", "text", ""),
                in(p + "Middle", "Middle name", "text", ""),
                in(p + "Contact", "Contact number", "tel", "inputmode='tel'")));
    }

    static String form() {
        String[] grades = {"Kindergarten", "Grade 1", "Grade 2", "Grade 3", "Grade 4", "Grade 5", "Grade 6", "Grade 7",
                "Grade 8", "Grade 9", "Grade 10", "Grade 11", "Grade 12"};
        StringBuilder f = new StringBuilder();
        f.append("<header class='hero'><div class='seal' aria-hidden='true'>DepEd</div><div>")
                .append("<h1>Basic Education Enrollment Form</h1>")
                .append("<p class='muted'>Fill in every field that applies. Answers are saved in capital letters. Revised as of 06/01/2025.</p>")
                .append("</div>{{NAV}}</header>");
        f.append("<form method='post' action='/submit'>");

        f.append(sec("1. School year and LRN", grid(3,
                in("syFrom", "School year starts", "number", "min='2020' max='2100' required placeholder='2026'"),
                in("syTo", "School year ends", "number", "min='2020' max='2100' required placeholder='2027'"),
                in("lrn", "Learner Reference No. (LRN), if applicable", "text",
                        "inputmode='numeric' maxlength='12' pattern='\\d{12}' title='12 digits' placeholder='12 digits'"))));

        f.append(sec("2. Grade level to enroll",
                opts("gradeType", "Type of enrollment", "radio", "Graded", "Non-graded (SNEd only)")
                        + cond("gradeType:Graded", sel("grade", "Grade level", grades))
                        + cond("grade:Kindergarten",
                        sub("For kindergarten enrollees",
                                opts("earlyProgram", "Has the learner attended any early learning program?", "radio", "Yes", "No")
                                        + cond("earlyProgram:Yes", in("earlyProgramSpec", "Please specify", "text", ""))))));

        f.append(sec("3. Learner's personal information",
                in("psa", "PSA Birth Certificate No. (if available upon registration)", "text", "")
                        + grid(4,
                        in("lastName", "Last name", "text", "required"),
                        in("firstName", "First name", "text", "required"),
                        in("middleName", "Middle name", "text", ""),
                        in("extName", "Extension name (Jr., III)", "text", ""))
                        + grid(4,
                        in("birthdate", "Birthdate", "date", "required"),
                        in("age", "Age", "text", "readonly placeholder='auto'"),
                        sel("sex", "Sex", "Male", "Female"),
                        in("birthPlace", "Place of birth (Municipality / City)", "text", ""))
                        + grid(2,
                        in("religion", "Religion", "text", ""),
                        in("motherTongue", "Mother tongue", "text", ""))
                        + opts("ip", "Belonging to any Indigenous Peoples (IP) community / Indigenous Cultural Community?", "radio", "Yes", "No")
                        + cond("ip:Yes", in("ipSpec", "Please specify", "text", ""))
                        + opts("fourPs", "Is your family a beneficiary of 4Ps?", "radio", "Yes", "No")
                        + cond("fourPs:Yes", in("fourPsId", "4Ps Household ID number", "text", "inputmode='numeric'"))
                        + sub("Current address", addr("cur", "current"))
                        + opts("permSame", "Is your permanent address the same as your current address?", "radio", "Yes", "No")
                        + cond("permSame:No", sub("Permanent address", addr("perm", "permanent")))));

        f.append(sec("4. Parent's / Guardian's information",
                person("father", "Father's name") + person("mother", "Mother's maiden name") + person("guardian", "Legal guardian's name")));

        String[] a1 = {"Attention Deficit Hyperactivity Disorder", "Autism Spectrum Disorder", "Cerebral Palsy",
                "Emotional-Behavior Disorder", "Hearing Impairment", "Intellectual Disability", "Learning Disability",
                "Multiple Disabilities", "Orthopedic/Physical Handicap", "Speech/Language Disorder",
                "Special Health Problem/Chronic Disease: Cancer", "Special Health Problem/Chronic Disease: Non-Cancer",
                "Visual Impairment: Blind", "Visual Impairment: Low Vision"};
        String[] a2 = {"Difficulty in Applying Knowledge", "Difficulty in Communicating",
                "Difficulty in Displaying Interpersonal Behavior (Emotional and Behavioral)", "Difficulty in Hearing",
                "Difficulty in Mobility (Walking, Climbing and Grasping)", "Difficulty in Performing Adaptive Skills (Self-Care)",
                "Difficulty in Remembering, Concentrating, Paying Attention and Understanding", "Difficulty in Seeing"};
        f.append(sec("5. Special Needs Education Program",
                opts("sned", "Is the learner under the Special Needs Education Program?", "radio", "Yes", "No")
                        + cond("sned:Yes",
                        "<p class='muted'>If yes, check only one: either from a1 or a2.</p>"
                                + opts("diagnosis", "a1. With diagnosis from a licensed medical specialist", "checkbox", a1)
                                + opts("manifest", "a2. With manifestations", "checkbox", a2)
                                + opts("pwdId", "b. Does the learner have a PWD ID?", "radio", "Yes", "No"))));

        f.append(sec("6. Returning learner (Balik-Aral) or transferring / moving in", grid(2,
                in("lastGrade", "Last grade level completed", "text", ""),
                in("lastSY", "Last school year completed", "text", "placeholder='2025-2026'"),
                in("lastSchool", "Last school attended", "text", ""),
                in("lastSchoolId", "School ID", "text", "inputmode='numeric' maxlength='6'"))));

        f.append(cond("grade:Grade 11|Grade 12", sec("7. Senior High School",
                opts("semester", "Semester", "radio", "1st", "2nd")
                        + grid(2, in("track", "Track", "text", ""), in("strand", "Strand", "text", "")))));

        f.append(sec("8. Distance learning preference",
                opts("modality", "If the school will use other distance learning modalities aside from face-to-face instruction, what would you prefer for your child? Check all that apply.",
                        "checkbox", "Blended (Combination)", "Homeschooling", "Modular (Print)", "Radio-Based Television",
                        "Educational Television", "Modular (Digital)", "Online")));

        KEYS.add("certify");
        LABELS.put("certify", "Certification accepted");
        f.append(sec("Certification",
                "<p class='muted'>I hereby certify that the above information given are true and correct to the best of my knowledge and I allow the Department of Education to process the learner's personal information to create and/or update his/her learner profile in the Learner Information System. The personal information herein shall be treated as confidential in compliance with the Data Privacy Act of 2012.</p>"
                        + "<label class='o checkbox cert'><input type='checkbox' id='certify' name='certify' value='Yes' required><i></i>"
                        + "<b>I certify that the information above is true and correct, and I agree to the processing of the learner's personal information as stated.</b></label>"
                        + grid(3, in("signDate", "Date", "date", "required value='" + LocalDate.now() + "'"))
                        + "<div class='actions'><button class='btn primary' id='submitBtn' type='submit' disabled>Submit enrollment</button>"
                        + "<button class='btn' type='reset'>Clear form</button></div>"));

        return f.append("</form>").toString();
    }

    // ------------------------------------------------------------ storage
    // Google Sheets when GOOGLE_SHEET_ID + credentials are set, otherwise CSV files. Pure JDK, no libraries.

    static final String SHEET_ID = env("GOOGLE_SHEET_ID", "");
    static final String SA_JSON = env("GOOGLE_SERVICE_ACCOUNT_JSON", "");
    static final String SA_FILE = env("GOOGLE_SERVICE_ACCOUNT_FILE", "");
    static final boolean USE_SHEETS = !SHEET_ID.isEmpty() && (!SA_JSON.isEmpty() || !SA_FILE.isEmpty());
    static final String SHEETS_BASE = env("GOOGLE_SHEETS_BASE", "https://sheets.googleapis.com");
    static final String TAB_ENROLL = "Enrollments", TAB_USERS = "Users";
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String saEmail, saTokenUri, accessTok;
    static PrivateKey saKey;
    static long accessExp;

    static void initStorage() throws Exception {
        if (!USE_SHEETS) {
            archiveOldCsv();
            System.out.println("Storage: CSV files in " + DATA.toAbsolutePath());
            return;
        }
        loadCredentials();
        ensureTab(TAB_ENROLL);
        ensureTab(TAB_USERS);
        List<String> eh = new ArrayList<>();
        eh.add("timestamp");
        eh.addAll(KEYS);
        ensureHeader(TAB_ENROLL, eh);
        ensureHeader(TAB_USERS, List.of("email", "salt", "hash", "name", "created"));
        System.out.println("Storage: Google Sheet " + SHEET_ID + " (signed in as " + saEmail + ")");
    }

    static List<List<String>> readCsv(Path p) throws IOException {
        List<List<String>> out = new ArrayList<>();
        if (!Files.exists(p)) return out;
        for (String l : Files.readAllLines(p, StandardCharsets.UTF_8)) if (!l.isEmpty()) out.add(parseCsv(l));
        return out;
    }

    @SuppressWarnings("unchecked")
    static void loadCredentials() throws Exception {
        String raw = !SA_JSON.isEmpty() ? SA_JSON.trim() : Files.readString(Paths.get(SA_FILE), StandardCharsets.UTF_8).trim();
        if (!raw.startsWith("{")) raw = new String(Base64.getMimeDecoder().decode(raw), StandardCharsets.UTF_8);
        Map<String, Object> j = (Map<String, Object>) new Json(raw).parse();
        saEmail = (String) j.get("client_email");
        saTokenUri = j.get("token_uri") instanceof String u ? u : "https://oauth2.googleapis.com/token";
        String pem = ((String) j.get("private_key")).replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        saKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    }

    @SuppressWarnings("unchecked")
    static synchronized String accessToken() throws IOException {
        if (accessTok != null && System.currentTimeMillis() < accessExp - 60_000) return accessTok;
        try {
            long now = System.currentTimeMillis() / 1000;
            String h = b64url("{\"alg\"😕"RS256\",\"typ\"😕"JWT\"}");
            String c = b64url("{\"iss\":" + jq(saEmail) + ",\"scope\"😕"https://www.googleapis.com/auth/spreadsheets\",\"aud\":"
                    + jq(saTokenUri) + ",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}");
            Signature sg = Signature.getInstance("SHA256withRSA");
            sg.initSign(saKey);
            sg.update((h + "." + c).getBytes(StandardCharsets.UTF_8));
            String jwt = h + "." + c + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sg.sign());
            HttpRequest rq = HttpRequest.newBuilder(URI.create(saTokenUri))
                    .header("Content-Type", "application/x-www-form-urlencoded").timeout(java.time.Duration.ofSeconds(20))
                    .POST(HttpRequest.BodyPublishers.ofString("grant_type="
                            + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                            + "&assertion=" + jwt)).build();
            HttpResponse<String> rs = HTTP.send(rq, HttpResponse.BodyHandlers.ofString());
            if (rs.statusCode() != 200) throw new IOException("Google sign-in failed (" + rs.statusCode() + "): " + rs.body());
            Map<String, Object> m = (Map<String, Object>) new Json(rs.body()).parse();
            accessTok = (String) m.get("access_token");
            accessExp = System.currentTimeMillis() + ((Number) m.get("expires_in")).longValue() * 1000;
            return accessTok;
        } catch (IOException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Google", e);
        } catch (Exception e) {
            throw new IOException("Google sign-in failed: " + e, e);
        }
    }

    static Object sheets(String method, String path, String body) throws IOException {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SHEETS_BASE + "/v4/spreadsheets/" + SHEET_ID + path))
                    .header("Authorization", "Bearer " + accessToken()).header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(20));
            b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> rs = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (rs.statusCode() / 100 != 2)
                throw new IOException("Google Sheets error " + rs.statusCode() + ": " + rs.body());
            return rs.body().isBlank() ? null : new Json(rs.body()).parse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while contacting Google Sheets", e);
        }
    }

    static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    static String rowsJson(List<String> cells) {
        StringBuilder sb = new StringBuilder("{\"values\":[[");
        for (int i = 0; i < cells.size(); i++) sb.append(i > 0 ? "," : "").append(jq(cells.get(i)));
        return sb.append("]]}").toString();
    }

    static void appendSheet(String tab, List<String> cells) throws IOException {
        sheets("POST", "/values/" + enc(tab + "!A1") + ":append?valueInputOption=RAW&insertDataOption=INSERT_ROWS", rowsJson(cells));
    }

    @SuppressWarnings("unchecked")
    static List<List<String>> readTab(String a1) throws IOException {
        Map<String, Object> m = (Map<String, Object>) sheets("GET", "/values/" + enc(a1), null);
        List<List<String>> out = new ArrayList<>();
        if (m == null || !(m.get("values") instanceof List<?> rows)) return out;
        for (Object r : rows) {
            List<String> row = new ArrayList<>();
            for (Object c : (List<Object>) r) row.add(c == null ? "" : String.valueOf(c));
            out.add(row);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void ensureTab(String title) throws IOException {
        Map<String, Object> meta = (Map<String, Object>) sheets("GET", "?fields=sheets.properties.title", null);
        if (meta != null && meta.get("sheets") instanceof List<?> l)
            for (Object o : l)
                if (title.equals(((Map<String, Object>) ((Map<String, Object>) o).get("properties")).get("title"))) return;
        sheets("POST", ":batchUpdate", "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":" + jq(title) + "}}}]}");
    }

    static void ensureHeader(String tab, List<String> header) throws IOException {
        List<List<String>> r = readTab(tab + "!1:1");
        if (r.isEmpty() || r.get(0).isEmpty()) {
            sheets("PUT", "/values/" + enc(tab + "!A1") + "?valueInputOption=RAW", rowsJson(header));
        } else if (!r.get(0).equals(header)) {
            System.out.println("WARNING: the first row of the '" + tab + "' tab does not match what this program saves. "
                    + "Use an empty tab (or fix the header row) or the columns will be misaligned.");
        }
    }

    static String b64url(String v) { return Base64.getUrlEncoder().withoutPadding().encodeToString(v.getBytes(StandardCharsets.UTF_8)); }

    static String jq(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }

    /** Tiny JSON reader: objects -> Map, arrays -> List, strings, numbers (Double), booleans, null. */
    static final class Json {
        final String s;
        int i;
        Json(String s) { this.s = s; }
        Object parse() { Object v = value(); ws(); return v; }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> m = new LinkedHashMap<>();
                ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) {
                    ws();
                    String k = str();
                    ws();
                    i++; // ':'
                    m.put(k, value());
                    ws();
                    if (s.charAt(i++) == '}') return m;
                }
            }
            if (c == '[') {
                i++;
                List<Object> l = new ArrayList<>();
                ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) {
                    l.add(value());
                    ws();
                    if (s.charAt(i++) == ']') return l;
                }
            }
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.valueOf(s.substring(st, i));
        }
        String str() {
            i++;
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n');
                        case 't' -> b.append('\t');
                        case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b');
                        case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
    }

    // ------------------------------------------------------------- page shell

    static String page(String title, String body, String script) {
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'><title>" + esc(title) + "</title>"
                + "<link rel='preconnect' href='https://fonts.googleapis.com'>"
                + "<link href='https://fonts.googleapis.com/css2?family=Nunito:wght@400;600;800&display=swap' rel='stylesheet'>"
                + "<style>" + CSS + "</style></head><body><main class='wrap'>" + body + "</main><script>" + script + "</script></body></html>";
    }

    static final String CSS = """
        :root{--bg:#e4e9f1;--hi:#ffffff;--lo:#b5bfd0;--tx:#323c55;--mut:#657089;--ac:#3a60d6}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--tx);font:16px/1.55 "Nunito","Segoe UI",system-ui,sans-serif}
        [hidden]{display:none!important}
        .wrap{max-width:980px;margin:0 auto;padding:28px 16px 80px}
        .hero{display:flex;align-items:center;gap:22px;margin-bottom:32px;flex-wrap:wrap}
        .hero>div:nth-child(2){flex:1;min-width:240px}
        .seal{width:84px;height:84px;border-radius:50%;display:grid;place-items:center;font-weight:800;color:var(--ac);letter-spacing:.5px;
          box-shadow:8px 8px 16px var(--lo),-8px -8px 16px var(--hi),inset 3px 3px 6px var(--hi),inset -3px -3px 6px var(--lo)}
        h1{margin:0 0 4px;font-size:clamp(1.6rem,4vw,2.3rem);font-weight:800;line-height:1.15}
        h2{margin:0 0 20px;font-size:1.2rem;font-weight:800}
        h3{margin:0 0 12px;font-size:1rem;font-weight:600;color:var(--mut)}
        .muted{color:var(--mut);font-size:.92rem;margin:0 0 14px}
        .card{background:var(--bg);border-radius:30px;padding:28px;margin:0 0 30px;
          box-shadow:14px 14px 28px var(--lo),-14px -14px 28px var(--hi)}
        .sub{border-radius:22px;padding:20px;margin:18px 0;box-shadow:inset 6px 6px 12px var(--lo),inset -6px -6px 12px var(--hi)}
        .grid{display:grid;gap:18px;margin-bottom:18px}
        .g2{grid-template-columns:repeat(2,1fr)}.g3{grid-template-columns:repeat(3,1fr)}.g4{grid-template-columns:repeat(4,1fr)}
        @media(max-width:820px){.g3,.g4{grid-template-columns:repeat(2,1fr)}}
        @media(max-width:540px){.g2,.g3,.g4{grid-template-columns:1fr}.card{padding:20px;border-radius:24px}}
        .f{display:flex;flex-direction:column;gap:8px;font-size:.88rem;font-weight:600;color:var(--mut)}
        .f input,.f select{width:100%;border:0;outline:0;background:var(--bg);color:var(--tx);font:inherit;font-weight:400;font-size:1rem;
          padding:13px 16px;border-radius:16px;box-shadow:inset 5px 5px 10px var(--lo),inset -5px -5px 10px var(--hi)}
        .f input:not([type=date]):not([type=number]):not([readonly]){text-transform:uppercase}
        .f input::placeholder{color:#9aa5bb;text-transform:none}
        .f input:focus,.f select:focus{box-shadow:inset 5px 5px 10px var(--lo),inset -5px -5px 10px var(--hi),0 0 0 2px var(--ac)}
        .fs{border:0;padding:0;margin:0 0 18px;min-width:0}
        .fs legend{padding:0;margin-bottom:12px;font-size:.92rem;font-weight:600;color:var(--mut)}
        .opts{display:flex;flex-wrap:wrap;gap:14px 26px}
        .o{position:relative;display:flex;align-items:center;gap:12px;cursor:pointer;font-weight:400}
        .o input{position:absolute;opacity:0;width:0;height:0}
        .o i{flex:none;width:28px;height:28px;border-radius:10px;display:grid;place-items:center;
          box-shadow:5px 5px 10px var(--lo),-5px -5px 10px var(--hi);transition:box-shadow .15s}
        .o.radio i{border-radius:50%}
        .o i::after{content:"";width:12px;height:12px;border-radius:50%;background:var(--ac);transform:scale(0);transition:transform .15s}
        .o input:checked+i{box-shadow:inset 4px 4px 8px var(--lo),inset -4px -4px 8px var(--hi)}
        .o input:checked+i::after{transform:scale(1)}
        .o input:focus-visible+i{outline:2px solid var(--ac);outline-offset:3px}
        .o b{font-weight:400;font-size:.95rem}
        .btn{display:inline-block;border:0;cursor:pointer;text-decoration:none;font:inherit;font-weight:800;color:var(--tx);
          padding:13px 26px;border-radius:18px;background:var(--bg);box-shadow:6px 6px 12px var(--lo),-6px -6px 12px var(--hi);transition:box-shadow .12s}
        .btn:active{box-shadow:inset 4px 4px 8px var(--lo),inset -4px -4px 8px var(--hi)}
        .btn:focus-visible{outline:2px solid var(--ac);outline-offset:3px}
        .btn.primary{color:var(--ac)}
        .btn:disabled{opacity:.45;cursor:not-allowed;box-shadow:none}
        .cert{margin:6px 0 20px;align-items:flex-start}.cert b{font-weight:600;line-height:1.45}
        .actions{display:flex;flex-wrap:wrap;gap:18px;margin-top:22px}
        .sum{display:grid;grid-template-columns:minmax(140px,240px) 1fr;gap:10px 20px;margin:0}
        .sum dt{color:var(--mut);font-size:.9rem}.sum dd{margin:0;font-weight:600;word-break:break-word}
        .scroll{overflow-x:auto;border-radius:20px;padding:6px;box-shadow:inset 5px 5px 10px var(--lo),inset -5px -5px 10px var(--hi)}
        table{width:100%;border-collapse:collapse;font-size:.92rem}
        th,td{text-align:left;padding:10px 14px;white-space:nowrap}th{color:var(--mut);font-weight:600}
        @media print{body{background:#fff}.card,.sub{box-shadow:none;border:1px solid #ccc}.actions,.hero .btn{display:none}}
        """;

    static final String JS = """
        const form = document.querySelector('form');
        function val(n){
          const els = document.getElementsByName(n);
          if(!els.length) return '';
          if(els[0].tagName === 'SELECT') return els[0].value;
          const on = [...els].filter(e => e.checked).map(e => e.value);
          return on.join('|');
        }
        function refresh(){
          document.querySelectorAll('[data-if]').forEach(el => {
            const [n, v] = el.dataset.if.split(':');
            const cur = val(n).split('|');
            el.hidden = !v.split('|').some(x => cur.includes(x));
          });
        }
        function calcAge(){
          const b = form.birthdate.value;
          if(!b){ form.age.value = ''; return; }
          const d = new Date(b), t = new Date();
          let a = t.getFullYear() - d.getFullYear();
          if(t.getMonth() < d.getMonth() || (t.getMonth() === d.getMonth() && t.getDate() < d.getDate())) a--;
          form.age.value = a >= 0 ? a : '';
        }
        form.addEventListener('change', () => { refresh(); calcAge(); });
        form.addEventListener('reset', () => setTimeout(() => { refresh(); calcAge(); }));
        refresh();
        const cert = document.getElementById('certify'), sbtn = document.getElementById('submitBtn');
        function syncCert(){ sbtn.disabled = !cert.checked; }
        cert.addEventListener('change', syncCert);
        form.addEventListener('reset', () => setTimeout(syncCert));
        syncCert();
        """;
}
