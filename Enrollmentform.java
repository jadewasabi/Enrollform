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

    // =====================================================================================
    //  EDIT HERE - settings you will want to change
    // =====================================================================================

    /** School year shown (and saved) on every form. Change this number each year. */
    static final int SCHOOL_YEAR_START = 2026;
    static final int SCHOOL_YEAR_END = SCHOOL_YEAR_START + 1;

    
    static final String[] RELIGIONS = {
        "Roman Catholic", "Islam", "Iglesia ni Cristo", "Born Again Christian", "Evangelical",
        "Seventh-day Adventist", "Aglipayan", "United Church of Christ in the Philippines", "Baptist",
        "Jehovah's Witnesses", "Latter-day Saints", "Buddhism", "Hinduism", "None"
    };

    /** Province dropdown. Provinces you add to PLACES below are added to this list automatically. */
    static final String[] PROVINCES = {
        "Abra", "Agusan del Norte", "Agusan del Sur", "Aklan", "Albay", "Antique", "Apayao", "Aurora", "Basilan",
        "Bataan", "Batanes", "Batangas", "Benguet", "Biliran", "Bohol", "Bukidnon", "Bulacan", "Bocaue", "Cagayan",
        "Camarines Norte", "Camarines Sur", "Camiguin", "Capiz", "Catanduanes", "Cavite", "Cebu", "Cotabato",
        "Davao de Oro", "Davao del Norte", "Davao del Sur", "Davao Occidental", "Davao Oriental", "Dinagat Islands",
        "Eastern Samar", "Guimaras", "Ifugao", "Ilocos Norte", "Ilocos Sur", "Iloilo", "Isabela", "Kalinga",
        "La Union", "Laguna", "Lanao del Norte", "Lanao del Sur", "Leyte", "Maguindanao del Norte",
        "Maguindanao del Sur", "Marinduque", "Masbate", "Metro Manila", "Misamis Occidental", "Misamis Oriental",
        "Mountain Province", "Negros Occidental", "Negros Oriental", "Northern Samar", "Nueva Ecija",
        "Nueva Vizcaya", "Occidental Mindoro", "Oriental Mindoro", "Palawan", "Pampanga", "Pangasinan", "Quezon",
        "Quirino", "Rizal", "Romblon", "Samar", "Sarangani", "Siquijor", "Sorsogon", "South Cotabato",
        "Southern Leyte", "Sultan Kudarat", "Sulu", "Surigao del Norte", "Surigao del Sur", "Tarlac", "Tawi-Tawi",
        "Zambales", "Zamboanga del Norte", "Zamboanga del Sur", "Zamboanga Sibugay"
    };

    /**
     * City / municipality and barangay lists. One line per barangay:   Province | City or Municipality | Barangay
     * - A line with only two parts (Province | City) adds the city with no barangay list (people type the barangay).
     * - Lines starting with # are notes and are ignored. Names are shown in the order you write them.
     * - Anything not listed here can still be typed using "Others (type it)".
     * Example (remove the # to use):
     * # Sample Province | Sample City | Sample Barangay
     */
    static final String PLACES = """
        Metro Manila | Caloocan
        Metro Manila | Las Pi\u00f1as
        Metro Manila | Makati
        Metro Manila | Malabon
        Metro Manila | Mandaluyong
        Metro Manila | Manila
        Metro Manila | Marikina
        Metro Manila | Muntinlupa
        Metro Manila | Navotas
        Metro Manila | Para\u00f1aque
        Metro Manila | Pasay
        Metro Manila | Pasig
        Metro Manila | Pateros
        Metro Manila | Quezon City
        Metro Manila | San Juan
        Metro Manila | Taguig
        Metro Manila | Valenzuela
        """;

    // =====================================================================================

    static final Map<String, Map<String, List<String>>> PLACE_MAP = parsePlaces();
    static final List<String> PROVINCE_LIST = provinceList();
    static final List<String> CITY_LIST = cityList();

    static Map<String, Map<String, List<String>>> parsePlaces() {
        Map<String, Map<String, List<String>>> m = new LinkedHashMap<>();
        for (String line : PLACES.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;
            String prov = parts[0].trim(), city = parts[1].trim();
            if (prov.isEmpty() || city.isEmpty()) continue;
            List<String> brgys = m.computeIfAbsent(prov, k -> new LinkedHashMap<>()).computeIfAbsent(city, k -> new ArrayList<>());
            if (parts.length > 2 && !parts[2].trim().isEmpty() && !brgys.contains(parts[2].trim())) brgys.add(parts[2].trim());
        }
        return m;
    }

    static List<String> provinceList() {
        TreeSet<String> t = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        t.addAll(Arrays.asList(PROVINCES));
        t.addAll(PLACE_MAP.keySet());
        return new ArrayList<>(t);
    }

    static List<String> cityList() {
        TreeSet<String> t = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (var p : PLACE_MAP.values()) t.addAll(p.keySet());
        return new ArrayList<>(t);
    }

    static String placesJson() {
        StringBuilder sb = new StringBuilder("{");
        boolean f1 = true;
        for (var p : PLACE_MAP.entrySet()) {
            sb.append(f1 ? "" : ",").append(jq(p.getKey())).append(":{");
            f1 = false;
            boolean f2 = true;
            for (var c : p.getValue().entrySet()) {
                sb.append(f2 ? "" : ",").append(jq(c.getKey())).append(":[");
                f2 = false;
                for (int i = 0; i < c.getValue().size(); i++) sb.append(i > 0 ? "," : "").append(jq(c.getValue().get(i)));
                sb.append("]");
            }
            sb.append("}");
        }
        return sb.append("}").toString().replace("<", "\\u003c");
    }
    static final List<String> KEYS = new ArrayList<>();
    static final Map<String, String> LABELS = new HashMap<>();
    static String FORM;

    // ---------------------------------------------------------------- server

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(env("PORT", "8080"));
        FORM = page("Enrollment Form", form(), "const PLACES = " + placesJson() + ";\n" + JS);
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

    static String ok(String redirectTo) { return "{\"ok\":true,\"redirect\":\"" + redirectTo + "\"}"; }
    static String err(String msg) { return "{\"ok\":false,\"error\":\"" + msg + "\"}"; }

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
        row.put("syFrom", String.valueOf(SCHOOL_YEAR_START)); // cannot be changed from the browser
        row.put("syTo", String.valueOf(SCHOOL_YEAR_END));
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
        // Saved rows are always: timestamp, then every form field in KEYS order - so read by position, not by header text.
        String[] cols = {"timestamp", "lrn", "lastName", "firstName", "grade", "syFrom", "fatherContact", "motherContact"};
        String[] names = {"Saved at", "LRN", "Last name", "First name", "Grade", "School year", "Father's no.", "Mother's no."};
        List<List<String>> rows = new ArrayList<>();
        for (int i = all.size() - 1; i >= 1; i--) {
            boolean empty = true;
            for (String c : all.get(i)) if (!c.isBlank()) { empty = false; break; }
            if (!empty) rows.add(all.get(i));
        }
        if (rows.isEmpty()) {
            sb.append("<p class='muted'>Nothing saved yet. Submit the form and the learner will show up here.</p>");
        } else {
            sb.append("<div class='scroll'><table><tr>");
            for (String n : names) sb.append("<th>").append(n).append("</th>");
            sb.append("</tr>");
            for (List<String> r : rows) {
                sb.append("<tr>");
                for (String c : cols) {
                    int idx = c.equals("timestamp") ? 0 : KEYS.indexOf(c) + 1;
                    sb.append("<td>").append(idx >= 1 || c.equals("timestamp") ? (idx < r.size() ? esc(r.get(idx)) : "") : "").append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</table></div><p class='muted'>").append(rows.size())
                    .append(USE_SHEETS ? " learner(s). Full details are in your Google Sheet." : " learner(s). Full details are in enrollments.csv.")
                    .append("</p>");
        }
        sb.append("<div class='actions'>");
        if (USE_SHEETS)
            sb.append("<a class='btn primary' target='_blank' rel='noopener' href='https://docs.google.com/spreadsheets/d/")
                    .append(esc(SHEET_ID)).append("/edit#gid=").append(esc(enrollGid)).append("'>Open Google Sheet</a>");
        sb.append("<a class='btn' href='/enroll'>New enrollment</a><a class='btn' href='/logout'>Log out</a></div></section>");
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

    /** Dropdown with an "Others (type it)" choice that reveals a text box. Typed text is what gets saved. */
    static String selOther(String name, String label, String role, Collection<String> options) {
        KEYS.add(name);
        LABELS.put(name, label);
        StringBuilder sb = new StringBuilder("<label class='f'><span>" + esc(label) + "</span><select name='" + name + "' data-other"
                + (role == null ? "" : " data-place='" + role + "'") + "><option value=''>Select</option>");
        for (String o : options) sb.append("<option value='").append(esc(o)).append("'>").append(esc(o)).append("</option>");
        return sb.append("<option value='__other__'>Others (type it)</option></select>")
                .append("<input class='other-input' type='text' placeholder='Type it here' hidden></label>").toString();
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
        // Built in the original column order (so saved columns never move), shown in a friendlier order.
        String house = in(p + "House", "House no.", "text", "");
        String street = in(p + "Street", "Sitio / Street name", "text", "");
        String brgy = selOther(p + "Barangay", "Barangay", "barangay", List.of());
        String city = selOther(p + "City", "Municipality / City", "city", List.of());
        String prov = selOther(p + "Province", "Province", "province", PROVINCE_LIST);
        String country = in(p + "Country", "Country", "text", "value='Philippines'");
        String zip = in(p + "Zip", "Zip code", "text", "inputmode='numeric' maxlength='4'");
        return grid(3, prov, city, brgy, house, street, zip, country);
    }

    static String person(String p, String title) {
        return sub(title, grid(4,
                in(p + "Last", "Last name", "text", ""),
                in(p + "First", "First name", "text", ""),
                in(p + "Middle", "Middle name", "text", ""),
                in(p + "Contact", "Contact number", "tel", "inputmode='tel'")));
    }

    static String form() {
        String[] grades = {"Grade 7",
                "Grade 8", "Grade 9", "Grade 10", "Grade 11", "Grade 12"};
        StringBuilder f = new StringBuilder();
        f.append("<header class='hero'><div class='seal' aria-hidden='true'>DepEd</div><div>")
                .append("<h1>Enrollment Form</h1>")
                .append("<p class='muted'>Fill in every field that applies. Answers are saved in capital letters. </p>")
                .append("</div>{{NAV}}</header>");
        f.append("<form method='post' action='/submit'>");

        f.append(sec("1. School year and LRN", grid(3,
                in("syFrom", "School year starts", "number", "required readonly value='" + SCHOOL_YEAR_START + "'"),
                in("syTo", "School year ends", "number", "required readonly value='" + SCHOOL_YEAR_END + "'"),
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
                        CITY_LIST.isEmpty() ? in("birthPlace", "Place of birth (Municipality / City)", "text", "")
                                : selOther("birthPlace", "Place of birth (Municipality / City)", null, CITY_LIST))
                        + grid(2,
                        selOther("religion", "Religion", null, Arrays.asList(RELIGIONS)),
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
    static String enrollGid = "0";

    static void initStorage() throws Exception {
        if (!USE_SHEETS) {
            archiveOldCsv();
            System.out.println("Storage: CSV files in " + DATA.toAbsolutePath());
            return;
        }
        loadCredentials();
        enrollGid = ensureTab(TAB_ENROLL);
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
            String h = b64url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
            String c = b64url("{\"iss\":" + jq(saEmail) + ",\"scope\":\"https://www.googleapis.com/auth/spreadsheets\",\"aud\":"
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

    /** Writes the row straight under the last used row of column A (callers are synchronized). */
    static void appendSheet(String tab, List<String> cells) throws IOException {
        int next = readTab(tab + "!A:A").size() + 1;
        sheets("PUT", "/values/" + enc(tab + "!A" + next) + "?valueInputOption=RAW", rowsJson(cells));
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

    /** Makes sure the tab exists and returns its numeric id (used for the "Open Google Sheet" link). */
    @SuppressWarnings("unchecked")
    static String ensureTab(String title) throws IOException {
        Map<String, Object> meta = (Map<String, Object>) sheets("GET", "?fields=sheets.properties(sheetId,title)", null);
        if (meta != null && meta.get("sheets") instanceof List<?> l)
            for (Object o : l) {
                Map<String, Object> pr = (Map<String, Object>) ((Map<String, Object>) o).get("properties");
                if (title.equals(pr.get("title"))) return gidOf(pr);
            }
        Map<String, Object> made = (Map<String, Object>) sheets("POST", ":batchUpdate",
                "{\"requests\":[{\"addSheet\":{\"properties\":{\"title\":" + jq(title) + "}}}]}");
        try {
            Map<String, Object> reply = (Map<String, Object>) ((List<Object>) made.get("replies")).get(0);
            return gidOf((Map<String, Object>) ((Map<String, Object>) reply.get("addSheet")).get("properties"));
        } catch (RuntimeException e) { return "0"; }
    }

    static String gidOf(Map<String, Object> props) {
        return props.get("sheetId") instanceof Number n ? String.valueOf(n.longValue()) : "0";
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
        .other-input{margin-top:8px}
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

        /* dropdowns with an "Others (type it)" box, and Province -> City -> Barangay */
        function syncOther(sel){
          const box = sel.parentElement.querySelector('.other-input');
          if(!box) return;
          const on = sel.value === '__other__';
          box.hidden = !on;
          if(!on) box.value = '';
        }
        function fillSelect(sel, items){
          sel.innerHTML = '';
          const first = document.createElement('option');
          first.value = ''; first.textContent = 'Select';
          sel.appendChild(first);
          items.forEach(t => { const o = document.createElement('option'); o.value = t; o.textContent = t; sel.appendChild(o); });
          const other = document.createElement('option');
          other.value = '__other__'; other.textContent = 'Others (type it)';
          sel.appendChild(other);
        }
        const provinceSelects = [...form.querySelectorAll('select[data-place=province]')];
        function wireAddress(ps){
          const prefix = ps.name.slice(0, -'Province'.length);
          const cs = form.elements[prefix + 'City'], bs = form.elements[prefix + 'Barangay'];
          function loadBarangays(){
            const list = ((PLACES[ps.value] || {})[cs.value]) || [];
            fillSelect(bs, list);
            if(!list.length && cs.value) bs.value = '__other__';
            syncOther(bs);
          }
          function loadCities(){
            const list = Object.keys(PLACES[ps.value] || {});
            fillSelect(cs, list);
            if(!list.length && ps.value) cs.value = '__other__';
            syncOther(cs);
            loadBarangays();
          }
          ps.addEventListener('change', () => { syncOther(ps); loadCities(); });
          cs.addEventListener('change', () => { syncOther(cs); loadBarangays(); });
          bs.addEventListener('change', () => syncOther(bs));
          loadCities();
          return loadCities;
        }
        const reloaders = provinceSelects.map(wireAddress);
        form.querySelectorAll('select[data-other]').forEach(s => s.addEventListener('change', () => syncOther(s)));
        form.addEventListener('reset', () => setTimeout(() => {
          form.querySelectorAll('select[data-other]').forEach(syncOther);
          reloaders.forEach(r => r());
        }));
        form.addEventListener('submit', () => {
          form.querySelectorAll('select[data-other]').forEach(s => {
            if(s.value !== '__other__') return;
            const t = s.parentElement.querySelector('.other-input').value.trim();
            if(t){ const o = s.options[s.selectedIndex]; o.value = t; o.textContent = t; }
            else { s.value = ''; }
          });
        });
        """;
}
