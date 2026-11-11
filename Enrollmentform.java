package enrollmentform;




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
    

    //auto year to
    static final int SCHOOL_YEAR_START = 2026;
    static final int SCHOOL_YEAR_END = SCHOOL_YEAR_START + 1;

   
    static final String[] RELIGIONS = {
        "Roman Catholic", "Islam", "Iglesia ni Cristo", "Born Again Christian", "Evangelical",
        "Seventh-day Adventist", "Aglipayan", "United Church of Christ in the Philippines", "Baptist",
        "Jehovah's Witnesses", "Latter-day Saints", "Buddhism", "Hinduism", "None"
    };

   
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
        Bulacan | Bocaue | Taal 
        Bulacan | Bocaue | Caingin
        Bulacan | Bocaue | Batia 
        Bulacan | Bocaue | Tambubong
        Bulacan | Bocaue | Wakas
        Bulacan | Bocaue | Bambang 
        Bulacan | Bocaue | Binang 1st
        Bulacan | Bocaue | Binang 2nd
        Bulacan | Bocaue | Bagumbayan
        Bulacan | Bocaue | Sulucan
        Bulacan | Bocaue | Lolomboy
        Bulacan | Bocaue | igulot
        Bulacan | Bocaue | Duhat
        Bulacan | Bocaue | Turo
        Bulacan | Bocaue | Poblacion
        Bulacan | Bocaue | Bunlo
        Bulacan | Bocaue | Bolacan
        Bulacan | Bocaue | Bundukan
        Bulacan | Bocaue | Antipona 
        """;

  

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

   
   //dito nagana render
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
                    if (user != null) redirect(ex, "/home");
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
                    else send(ex, 200, submit(ex, user));
                } else if (path.equals("/home") && get) {
                    if (user == null) redirect(ex, "/");
                    else send(ex, 200, home(user, admin));
                } else if (path.equals("/my") && get) {
                    if (user == null) redirect(ex, "/");
                    else send(ex, 200, myPage(user));
                } else if (path.equals("/view") && get) {
                    if (user == null) redirect(ex, "/");
                    else {
                        String h = viewRecord(ex.getRequestURI().getQuery(), user, admin);
                        if (h == null) send(ex, 404, page("Not found",
                                "<section class='card'><h2>Submission not found</h2><div class='actions'><a class='btn primary' href='/home'>Home</a></div></section>", ""));
                        else send(ex, 200, h);
                    }
                } else if (path.equals("/records") || path.equals("/users")) {
                    if (user == null) redirect(ex, "/");
                    else if (!admin) send(ex, 403, page("Admins only",
                            "<section class='card'><h2>Admins only</h2><p class='muted'>This page is only available to the administrator account.</p>"
                                    + "<div class='actions'><a class='btn primary' href='/home'>Home</a><a class='btn' href='/logout'>Log out</a></div></section>", ""));
                    else send(ex, 200, path.equals("/users") ? usersPage() : records());
                } else if (get && path.matches("/[A-Za-z0-9_-]+\\.(png|jpe?g|gif|svg|webp|ico)")) {
                    sendImage(ex, path.substring(1));
                } else send(ex, 404, page("Not found",
                        "<section class='card'><h2>Page not found</h2><a class='btn' href='/home'>Back to home</a></section>", ""));
            } catch (BadRequest e) {
                send(ex, 400, page("Cannot submit",
                        "<section class='card'><h2>Cannot submit yet</h2><p>" + esc(e.getMessage())
                                + "</p><a class='btn' href='javascript:history.back()'>Go back</a></section>", ""));
            } catch (Exception e) {
                e.printStackTrace();
                send(ex, 500, page("Error",
                        "<section class='card'><h2>Something went wrong</h2><p>" + esc(String.valueOf(e.getMessage()))
                                + "</p><a class='btn' href='/home'>Back to home</a></section>", ""));
            }
        });
        server.start();
        System.out.println("Enrollment form running at http://localhost:" + port);
        System.out.println("Admin sign-in: " + ADMIN_EMAIL + (ADMIN_PASSWORD.equals("admin123")
                ? "  (default password - set ENROLL_ADMIN_PASSWORD before real use)" : ""));
    }

    static final Map<String, String> ICONS = Map.ofEntries(
        Map.entry("cap", "<path d='M22 10v6M2 10l10-5 10 5-10 5z'/><path d='M6 12v5c3 3 9 3 12 0v-5'/>"),
        Map.entry("school", "<path d='M14 22v-4a2 2 0 1 0-4 0v4'/><path d='m18 10 3.447 1.724a1 1 0 0 1 .553.894V20a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2v-7.382a1 1 0 0 1 .553-.894L6 10'/><path d='M18 5v17'/><path d='m4 6 7.106-3.553a2 2 0 0 1 1.788 0L20 6'/><path d='M6 5v17'/><circle cx='12' cy='9' r='2'/>"),
        Map.entry("user", "<path d='M19 21v-2a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4v2'/><circle cx='12' cy='7' r='4'/>"),
        Map.entry("users", "<path d='M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2'/><circle cx='9' cy='7' r='4'/><path d='M22 21v-2a4 4 0 0 0-3-3.87'/><path d='M16 3.13a4 4 0 0 1 0 7.75'/>"),
        Map.entry("house", "<path d='M15 21v-8a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1v8'/><path d='M3 10a2 2 0 0 1 .709-1.528l7-5.999a2 2 0 0 1 2.582 0l7 5.999A2 2 0 0 1 21 10v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'/>"),
        Map.entry("pulse", "<path d='M19 14c1.49-1.46 3-3.21 3-5.5A5.5 5.5 0 0 0 16.5 3c-1.76 0-3 .5-4.5 2-1.5-1.5-2.74-2-4.5-2A5.5 5.5 0 0 0 2 8.5c0 2.3 1.5 4.05 3 5.5l7 7Z'/><path d='M3.22 12H9.5l.5-1 2 4.5 2-7 1.5 3.5h5.27'/>"),
        Map.entry("file", "<path d='M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z'/><path d='M14 2v4a2 2 0 0 0 2 2h4'/><path d='M16 13H8'/><path d='M16 17H8'/><path d='M10 9H8'/>"),
        Map.entry("check", "<path d='M20 6 9 17l-5-5'/>"),
        Map.entry("left", "<path d='m12 19-7-7 7-7'/><path d='M19 12H5'/>"),
        Map.entry("right", "<path d='M5 12h14'/><path d='m12 5 7 7-7 7'/>"),
        Map.entry("shield", "<path d='M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z'/>"),
        Map.entry("send", "<path d='M14.536 21.686a.5.5 0 0 0 .937-.024l6.5-19a.496.496 0 0 0-.635-.635l-19 6.5a.5.5 0 0 0-.024.937l7.93 3.18a2 2 0 0 1 1.112 1.11z'/><path d='m21.854 2.147-10.94 10.939'/>"),
        Map.entry("pin", "<path d='M20 10c0 4.993-5.539 10.193-7.399 11.799a1 1 0 0 1-1.202 0C9.539 20.193 4 14.993 4 10a8 8 0 0 1 16 0'/><circle cx='12' cy='10' r='3'/>"),
        Map.entry("out", "<path d='M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4'/><path d='m16 17 5-5-5-5'/><path d='M21 12H9'/>"),
        Map.entry("list", "<rect width='8' height='4' x='8' y='2' rx='1'/><path d='M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2'/><path d='M12 11h4'/><path d='M12 16h4'/><path d='M8 11h.01'/><path d='M8 16h.01'/>"),
        Map.entry("plus", "<path d='M5 12h14'/><path d='M12 5v14'/>"),
        Map.entry("print", "<path d='M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2'/><path d='M6 9V3a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v6'/><rect x='6' y='14' width='12' height='8' rx='1'/>"));

    static String ic(String n) {
        return "<svg class='ic' viewBox='0 0 24 24' aria-hidden='true'>" + ICONS.get(n) + "</svg>";
    }

    static final String CHIPS = "<div class='chips'><span class='chip dark'>Official enrollment form</span><span class='chip gold'>Not for sale</span></div>";

    static String hero(String chips, String title, String text, String actions) {
        return "<header class='hero'><div class='hero-in'>" + chips + "<h1>" + title + "</h1><p>" + text + "</p>"
                + (actions.isEmpty() ? "" : "<div class='hero-actions'>" + actions + "</div>")
                + "</div><span class='hero-ic'>" + ic("school") + "</span></header>";
    }

    static String nav(boolean admin) {
        return "<a class='gbtn' href='/home'>" + ic("house") + "Home</a><a class='gbtn' href='/logout'>" + ic("out") + "Log out</a>";
    }

  

    static void send(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    //pics dito
    static void sendImage(HttpExchange ex, String name) throws IOException {
        Path dir = INDEX.toAbsolutePath().getParent();
        Path f = dir == null ? Paths.get(name) : dir.resolve(name);
        if (!Files.isRegularFile(f)) {
            send(ex, 404, page("Not found", "<section class='card'><h2>Not found</h2></section>", ""));
            return;
        }
        String n = name.toLowerCase();
        String type = n.endsWith(".png") ? "image/png" : n.endsWith(".svg") ? "image/svg+xml" : n.endsWith(".gif") ? "image/gif"
                : n.endsWith(".webp") ? "image/webp" : n.endsWith(".ico") ? "image/x-icon" : "image/jpeg";
        byte[] b = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        ex.sendResponseHeaders(200, b.length);
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
        return ok("/home");
    }

    static String signin(HttpExchange ex) throws IOException {
        Map<String, List<String>> in = parseForm(body(ex));
        String email = first(in, "email").trim().toLowerCase(), pw = first(in, "password");
        String bad = err("Incorrect email or password.");
        if (email.equals(ADMIN_EMAIL)) {
            if (!MessageDigest.isEqual(pw.getBytes(StandardCharsets.UTF_8), ADMIN_PASSWORD.getBytes(StandardCharsets.UTF_8))) return bad;
            startSession(ex, email, "admin");
            return ok("/home");
        }
        String[] u = findUser(email);
        if (u == null) return bad;
        byte[] salt = Base64.getDecoder().decode(u[1]);
        if (!MessageDigest.isEqual(hash(pw, salt).getBytes(StandardCharsets.UTF_8), u[2].getBytes(StandardCharsets.UTF_8))) return bad;
        startSession(ex, email, "user");
        return ok("/home");
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

    
    static void archiveOldCsv() throws IOException {
        if (!Files.exists(DB)) return;
        List<String> lines = Files.readAllLines(DB, StandardCharsets.UTF_8);
        String full = "timestamp," + String.join(",", KEYS);
        String legacy = full.substring(0, full.length() - ",submittedBy".length()); 
        if (!lines.isEmpty() && (lines.get(0).equals(full) || lines.get(0).equals(legacy))) return;
        Path old = DATA.resolve("enrollments-old-" + LocalDateTime.now().withNano(0).toString().replace(':', '-') + ".csv");
        Files.move(DB, old);
        System.out.println("enrollments.csv had the old layout; moved to " + old);
    }

  
   //submit tas save
    static String submit(HttpExchange ex, String[] user) throws IOException {
        boolean admin = user[1].equals("admin");
        Map<String, List<String>> in = parseForm(body(ex));
        if (!in.containsKey("certify"))
            throw new BadRequest("Please tick the certification checkbox before submitting.");
        Map<String, String> row = new LinkedHashMap<>();
        for (String k : KEYS) {
            String v = String.join("; ", in.getOrDefault(k, List.of())).replaceAll("\\s+", " ").trim().toUpperCase();
            row.put(k, v);
        }
        row.put("submittedBy", user[0]);
        row.put("syFrom", String.valueOf(SCHOOL_YEAR_START)); // cannot be changed from the browser
        row.put("syTo", String.valueOf(SCHOOL_YEAR_END));
        saveRow(row);

        String ref = "-";
        try { ref = "#DEPED-" + SCHOOL_YEAR_START + "-" + String.format("%04d", enrollRows().size() - 1); } catch (Exception ignored) { }
        StringBuilder sb = new StringBuilder("<section class='card done'><span class='okic'>" + ic("check") + "</span><h2>Enrollment Submitted!</h2>")
                .append("<p class='muted'>The learner's information has been saved. Print this page and hand it to the registrar or class adviser.</p>")
                .append("<div class='ref'><div><b>Reference Code:</b> <span>").append(esc(ref)).append("</span></div>")
                .append("<div><b>Status:</b> <span>Pending Registrar Verification</span></div></div>")
                .append("<div class='actions mid'><a class='btn primary wide' href='/enroll'>Register Another Learner</a>")
                .append("<button class='btn' onclick='window.print()'>").append(ic("print")).append("Print</button>")
                .append(admin ? "<a class='btn' href='/records'>View records</a>" : "<a class='btn' href='/my'>My submissions</a>")
                .append("<a class='btn' href='/home'>Home</a></div></section>")
                .append("<section class='card'><h2>Submitted details</h2><dl class='sum'>");
        for (var e : row.entrySet()) {
            if (e.getValue().isEmpty() || e.getKey().equals("submittedBy")) continue;
            sb.append("<dt>").append(esc(LABELS.getOrDefault(e.getKey(), e.getKey()))).append("</dt><dd>")
                    .append(esc(e.getValue())).append("</dd>");
        }
        sb.append("</dl></section>");
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

   
   //hp tas lists
    static List<List<String>> enrollRows() throws IOException {
        return USE_SHEETS ? readTab(TAB_ENROLL + "!A:ZZ") : readCsv(DB);
    }

    static boolean blankRow(List<String> r) {
        for (String c : r) if (!c.isBlank()) return false;
        return true;
    }

    //time 
    static String cell(List<String> r, String key) {
        int k = KEYS.indexOf(key);
        int i = key.equals("timestamp") ? 0 : k < 0 ? -1 : k + 1;
        return i >= 0 && i < r.size() ? r.get(i) : "";
    }

   //stud acc
    static synchronized List<String[]> userList() throws IOException {
        List<String[]> out = new ArrayList<>();
        for (List<String> c : USE_SHEETS ? readTab(TAB_USERS + "!A:E") : readCsv(USERS)) {
            if (c.size() < 4 || c.get(0).equals("email")) continue;
            out.add(new String[]{c.get(0), c.get(3), c.size() > 4 ? c.get(4) : ""});
        }
        return out;
    }

    static String userName(String email) throws IOException {
        for (String[] u : userList()) if (u[0].equals(email)) return u[1];
        return email;
    }

    static String tile(String href, String icon, String title, String text, String stat) {
        return "<a class='tile' href='" + href + "'><span class='tile-ic'>" + ic(icon) + "</span><span class='tile-b'><b>" + title
                + "</b><span>" + text + "</span></span><span class='tile-n'>" + stat + "</span></a>";
    }

    static String home(String[] user, boolean admin) throws IOException {
        int total = 0, mine = 0, today = 0;
        String td = LocalDate.now().toString();
        List<List<String>> all = enrollRows();
        for (int i = 1; i < all.size(); i++) {
            if (blankRow(all.get(i))) continue;
            total++;
            if (cell(all.get(i), "submittedBy").equalsIgnoreCase(user[0])) mine++;
            if (cell(all.get(i), "timestamp").startsWith(td)) today++;
        }
        String who = admin ? "Administrator" : userName(user[0]);
        String chips = "<div class='chips'><span class='chip dark'>" + (admin ? "Administrator account" : "Student account")
                + "</span><span class='chip gold'>SY " + SCHOOL_YEAR_START + " - " + SCHOOL_YEAR_END + "</span></div>";
        String text = admin ? "Review submitted enrollments and manage student accounts."
                : "Fill in the enrollment form or look at the forms you already submitted.";
        StringBuilder sb = new StringBuilder(hero(chips, "Welcome, " + esc(who), text,
                admin ? "<a class='gbtn' href='/logout'>" + ic("out") + "Log out</a>"
                        : "<a class='gbtn solid' href='/enroll'>" + ic("plus") + "Start enrollment</a><a class='gbtn' href='/logout'>" + ic("out") + "Log out</a>"));
        sb.append("<div class='tiles'>");
        if (admin) {
            sb.append(tile("/records", "list", "Enrollment records", "Every learner enrolled so far, with full details. " + today + " submitted today.", String.valueOf(total)))
                    .append(tile("/users", "users", "User records", "Student accounts that have been created.", String.valueOf(userList().size())));
        } else {
            sb.append(tile("/enroll", "file", "Enrollment form", "Fill in a new Basic Education enrollment form.", "+"))
                    .append(tile("/my", "list", "My submissions", "The forms you submitted. Open one to view or print it.", String.valueOf(mine)));
            sb.append("<section class='card need'><h2>Before you start</h2><ul>"
                    + "<li>The learner's birth certificate, so names and birthdate match.</li>"
                    + "<li>The 12-digit LRN, if the learner already has one.</li>"
                    + "<li>Mother's maiden name and a parent or guardian contact number.</li>"
                    + "<li>Previous school details for returning learners and transferees.</li></ul></section>");
        }
        sb.append("</div>");
        return page("Home", sb.toString(), "");
    }

    static String recordTable(List<List<String>> all, String onlyEmail, boolean showWho) {
        String[] cols = {"timestamp", "lrn", "lastName", "firstName", "grade"};
        String[] names = {"Saved at", "LRN", "Last name", "First name", "Grade"};
        StringBuilder sb = new StringBuilder();
        int n = 0;
        StringBuilder rows = new StringBuilder();
        for (int i = all.size() - 1; i >= 1; i--) {
            List<String> r = all.get(i);
            if (blankRow(r)) continue;
            if (onlyEmail != null && !cell(r, "submittedBy").equalsIgnoreCase(onlyEmail)) continue;
            n++;
            rows.append("<tr>");
            for (String c : cols) rows.append("<td>").append(esc(c.equals("timestamp") ? cell(r, c).replace('T', ' ') : cell(r, c))).append("</td>");
            if (showWho) rows.append("<td>").append(esc(cell(r, "submittedBy"))).append("</td>");
            rows.append("<td><a class='lnk' href='/view?id=").append(i).append("'>View</a></td></tr>");
        }
        if (n == 0) return "";
        sb.append("<div class='scroll'><table><tr>");
        for (String h : names) sb.append("<th>").append(h).append("</th>");
        if (showWho) sb.append("<th>Submitted by</th>");
        sb.append("<th></th></tr>").append(rows).append("</table></div><p class='muted'>").append(n).append(" learner(s).</p>");
        return sb.toString();
    }

    static String records() throws IOException {
        StringBuilder sb = new StringBuilder("<section class='card'><h2>Enrollment records</h2>");
        String t = recordTable(enrollRows(), null, true);
        sb.append(t.isEmpty() ? "<p class='muted'>Nothing saved yet. Submitted forms will show up here.</p>" : t);
        sb.append("<div class='actions'>");
        if (USE_SHEETS)
            sb.append("<a class='btn primary' target='_blank' rel='noopener' href='https://docs.google.com/spreadsheets/d/")
                    .append(esc(SHEET_ID)).append("/edit#gid=").append(esc(enrollGid)).append("'>Open Google Sheet</a>");
        sb.append("<a class='btn' href='/home'>Home</a><a class='btn' href='/logout'>Log out</a></div></section>");
        return page("Enrollment records", sb.toString(), "");
    }

    static String usersPage() throws IOException {
        List<List<String>> all = enrollRows();
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 1; i < all.size(); i++) {
            if (blankRow(all.get(i))) continue;
            counts.merge(cell(all.get(i), "submittedBy").toLowerCase(), 1, Integer::sum);
        }
        List<String[]> us = userList();
        StringBuilder sb = new StringBuilder("<section class='card'><h2>User records</h2>");
        if (us.isEmpty()) sb.append("<p class='muted'>No student accounts yet.</p>");
        else {
            sb.append("<div class='scroll'><table><tr><th>Name</th><th>Email</th><th>Created</th><th>Forms submitted</th></tr>");
            for (int i = us.size() - 1; i >= 0; i--) {
                String[] u = us.get(i);
                sb.append("<tr><td>").append(esc(u[1])).append("</td><td>").append(esc(u[0])).append("</td><td>")
                        .append(esc(u[2].replace('T', ' '))).append("</td><td>")
                        .append(counts.getOrDefault(u[0].toLowerCase(), 0)).append("</td></tr>");
            }
            sb.append("</table></div><p class='muted'>").append(us.size()).append(" account(s).</p>");
        }
        sb.append("<div class='actions'><a class='btn' href='/home'>Home</a><a class='btn' href='/logout'>Log out</a></div></section>");
        return page("User records", sb.toString(), "");
    }

    static String myPage(String[] user) throws IOException {
        StringBuilder sb = new StringBuilder("<section class='card'><h2>My submissions</h2>");
        String t = recordTable(enrollRows(), user[0], false);
        sb.append(t.isEmpty() ? "<p class='muted'>You have not submitted a form yet.</p>" : t);
        sb.append("<div class='actions'><a class='btn primary' href='/enroll'>New enrollment</a><a class='btn' href='/home'>Home</a>"
                + "<a class='btn' href='/logout'>Log out</a></div></section>");
        return page("My submissions", sb.toString(), "");
    }

   //mga na ssave na form ng students
    static String viewRecord(String query, String[] user, boolean admin) throws IOException {
        int id;
        try { id = Integer.parseInt(first(parseForm(query == null ? "" : query), "id").trim()); }
        catch (NumberFormatException e) { return null; }
        List<List<String>> all = enrollRows();
        if (id < 1 || id >= all.size() || blankRow(all.get(id))) return null;
        List<String> r = all.get(id);
        if (!admin && !cell(r, "submittedBy").equalsIgnoreCase(user[0])) return null;
        StringBuilder sb = new StringBuilder("<section class='card'><h2>Submitted form</h2>")
                .append("<p class='muted'>Saved ").append(esc(cell(r, "timestamp").replace('T', ' ')))
                .append(admin ? " by " + esc(cell(r, "submittedBy")) : "").append("</p><dl class='sum'>");
        for (String k : KEYS) {
            String v = cell(r, k);
            if (v.isEmpty() || k.equals("submittedBy")) continue;
            sb.append("<dt>").append(esc(LABELS.getOrDefault(k, k))).append("</dt><dd>").append(esc(v)).append("</dd>");
        }
        sb.append("</dl><div class='actions'><button class='btn primary' onclick='window.print()'>Print</button>")
                .append("<a class='btn' href='").append(admin ? "/records" : "/my").append("'>Back to the list</a>")
                .append("<a class='btn' href='/home'>Home</a></div></section>");
        return page("Submitted form", sb.toString(), "");
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }

  
    //html hahahah
    static final Set<String> DEFAULT_NO = Set.of("ip", "fourPs", "sned", "pwdId");

    static String req(String attrs) { return attrs.contains("required") ? " <i class='req'>*</i>" : ""; }

    static String in(String name, String label, String type, String attrs) {
        KEYS.add(name);
        LABELS.put(name, label);
        return "<label class='f'><span>" + label + req(attrs) + "</span><input name='" + name + "' type='" + type + "' " + attrs + "></label>";
    }

    // input without a visible label (placeholder only), used for the parent / guardian rows
    static String pin(String name, String label, String ph, String type, String attrs) {
        KEYS.add(name);
        LABELS.put(name, label);
        return "<input class='pin' name='" + name + "' type='" + type + "' placeholder='" + ph + (attrs.contains("required") ? " *" : "")
                + "' aria-label='" + esc(label) + "' " + attrs + ">";
    }

    static String hid(String name, String label, String value) {
        KEYS.add(name);
        LABELS.put(name, label);
        return "<input type='hidden' name='" + name + "' value='" + value + "'>";
    }

    static String sel(String name, String label, String... options) { return selA(name, label, "", options); }

    static String selA(String name, String label, String attrs, String... options) {
        KEYS.add(name);
        LABELS.put(name, label);
        StringBuilder sb = new StringBuilder("<label class='f'><span>" + label + req(attrs) + "</span><select name='" + name + "' " + attrs + "><option value=''>Select " + label + "</option>");
        for (String o : options) sb.append("<option>").append(o).append("</option>");
        return sb.append("</select></label>").toString();
    }

    static String selOther(String name, String label, String role, Collection<String> options) {
        return selOther(name, label, role, options, "");
    }

    //drop down st
    static String selOther(String name, String label, String role, Collection<String> options, String attrs) {
        KEYS.add(name);
        LABELS.put(name, label);
        StringBuilder sb = new StringBuilder("<label class='f'><span>" + esc(label) + req(attrs) + "</span><select name='" + name + "' data-other " + attrs
                + (role == null ? "" : " data-place='" + role + "'") + "><option value=''>Select</option>");
        for (String o : options) sb.append("<option value='").append(esc(o)).append("'>").append(esc(o)).append("</option>");
        return sb.append("<option value='__other__'>Others (type it)</option></select>")
                .append("<input class='other-input' type='text' placeholder='Type it here' hidden></label>").toString();
    }

    static String opts(String name, String legend, String type, String... labels) {
        return optsX(name, legend, type, type.equals("checkbox"), labels);
    }

    static String optsX(String name, String legend, String type, boolean stack, String... labels) {
        if (!KEYS.contains(name)) KEYS.add(name);
        LABELS.put(name, legend);
        StringBuilder sb = new StringBuilder("<div class='q" + (stack ? " stack" : "") + "' role='group' aria-label='" + esc(legend) + "'><span class='ql'>" + esc(legend)
                + "</span><div class='opts " + type + "s'>");
        for (String l : labels)
            sb.append("<label class='o ").append(type).append(type.equals("checkbox") ? " box" : "").append("'><input type='").append(type).append("' name='").append(name)
                    .append("' value='").append(esc(l)).append("'").append(l.equals("No") && DEFAULT_NO.contains(name) ? " checked" : "")
                    .append("><span>").append(esc(l)).append("</span></label>");
        return sb.append("</div></div>").toString();
    }

    static String grid(int cols, String... items) {
        return "<div class='grid g" + cols + "'>" + String.join("", items) + "</div>";
    }

    static String cond(String rule, String content) { return "<div class='cond' data-if='" + rule + "'>" + content + "</div>"; }

    static String sub(String title, String content) { return "<div class='group'>" + (title.isEmpty() ? "" : "<h3>" + title + "</h3>") + content + "</div>"; }

    static String step(int i, String icon, String title, String text, String content) {
        return "<section class='panel' data-step='" + i + "'" + (i > 0 ? " hidden" : "") + "><div class='ph'><span class='pic'>" + ic(icon)
                + "</span><div><h2>" + title + "</h2><p class='muted'>" + text + "</p></div></div><div class='pbody'>" + content + "</div></section>";
    }

    static String stepper() {
        String[][] s = {{"school", "Grade & LRN"}, {"user", "Learner Info"}, {"house", "Address & Parents"}, {"pulse", "SNED & Modalities"}, {"file", "Review & Submit"}};
        StringBuilder sb = new StringBuilder("<nav class='stepper' aria-label='Enrollment steps'><ol>");
        for (int i = 0; i < s.length; i++)
            sb.append("<li class='st").append(i == 0 ? " active" : "").append("'><button type='button' class='dot' data-go='").append(i)
                    .append("' aria-label='").append(esc(s[i][1])).append("'><span class='mi'>").append(ic(s[i][0])).append("</span><span class='ck'>")
                    .append(ic("check")).append("</span></button><span class='lbl'>").append(esc(s[i][1])).append("</span></li>");
        return sb.append("</ol></nav>").toString();
    }

    static String addr(String p, String who) {
        String r = p.equals("cur") ? "required" : "";
        String house = in(p + "House", "House No.", "text", "placeholder='e.g. Block 5 Lot 2'");
        String street = in(p + "Street", "Sitio / Street", "text", "placeholder='e.g. Sitio Maligaya'");
        String brgy = selOther(p + "Barangay", "Barangay", "barangay", List.of(), r);
        String city = selOther(p + "City", "Municipality / City", "city", List.of(), r);
        String prov = selOther(p + "Province", "Province", "province", PROVINCE_LIST, r);
        String country = in(p + "Country", "Country", "text", "value='Philippines'");
        String zip = in(p + "Zip", "Zip Code", "text", "inputmode='numeric' maxlength='4' placeholder='e.g. 2009'");
        return grid(3, prov, city, brgy, house, street, zip, country);
    }

    static String person(String p, String title, String heading, boolean nameReq, boolean contactReq) {
        String n = nameReq ? "required" : "";
        return "<div class='group'><h4 class='gt'>" + heading + "</h4>" + grid(4,
                pin(p + "Last", title + ": last name", "Last Name", "text", n),
                pin(p + "First", title + ": first name", "First Name", "text", n),
                pin(p + "Middle", title + ": middle name", "Middle Name", "text", ""),
                pin(p + "Contact", title + ": contact number", "Contact Number", "tel", "inputmode='tel' " + (contactReq ? "data-guardian" : ""))) + "</div>";
    }

    static String form() {
        String[] grades = {"Grade 7", "Grade 8", "Grade 9", "Grade 10", "Grade 11", "Grade 12"};
        StringBuilder f = new StringBuilder();
        f.append(hero(CHIPS, "Basic Education Enrollment Form",
                "Please provide accurate information. Required fields are marked with an asterisk (*). Answers are saved in capital letters.", "{{NAV}}"));
        f.append("<form method='post' action='/submit' id='enrollForm' autocomplete='off'>").append(stepper()).append("<div class='card wiz'>");

        // ---- step 1: school year, LRN, grade
        String sy = "<label class='f'><span>School Year <i class='req'>*</i></span><input type='text' value='" + SCHOOL_YEAR_START + " - " + SCHOOL_YEAR_END + "' readonly></label>";
        String syHid = hid("syFrom", "School year starts", "" + SCHOOL_YEAR_START) + hid("syTo", "School year ends", "" + SCHOOL_YEAR_END);
        String lrn = in("lrn", "Learner Reference No. (LRN)", "text", "inputmode='numeric' maxlength='12' pattern='\\d{12}' title='12 digits' placeholder='12-digit LRN (if applicable)'")
                .replace("</label>", "<small class='hint'>Leave blank if no LRN yet (e.g., Kindergarten or First Time Enrollee)</small></label>");
        String gt = hid("gradeType", "Type of enrollment", "Graded");
        KEYS.add("grade");
        LABELS.put("grade", "Grade level");
        StringBuilder gs = new StringBuilder("<select name='grade' id='grade' required><option value=''>Select Grade Level</option>");
        for (String g : grades) gs.append("<option>").append(g).append("</option>");
        gs.append("</select>");
        String gradeBox = "<div class='group'><label class='lab' for='grade'>Grade Level to Enroll <i class='req'>*</i></label><div class='row2'>" + gs
                + "<label class='o checkbox box'><input type='checkbox' id='nonGraded'><span>Non-Graded (For Special Needs Education / SNED Only)</span></label></div>" + gt + "</div>";
        String kinder = cond("grade:Kindergarten", sub("For kindergarten enrollees",
                opts("earlyProgram", "Has the learner attended any early learning program?", "radio", "Yes", "No")
                        + cond("earlyProgram:Yes", in("earlyProgramSpec", "Please specify", "text", ""))));
        f.append(step(0, "school", "1. School Year &amp; Grade Level Information", "Specify target academic year, grade level, and Learner Reference Number (LRN).",
                "<div class='grid g2'>" + sy + lrn + "</div>" + syHid + gradeBox + kinder));

        // ---- step 2: learner's personal information
        String psa = in("psa", "PSA Birth Certificate No. (If available upon registration)", "text", "placeholder='Enter PSA Birth Certificate Number'");
        String last = in("lastName", "Last Name", "text", "required placeholder='Dela Cruz'");
        String first = in("firstName", "First Name", "text", "required placeholder='Juan'");
        String mid = in("middleName", "Middle Name", "text", "placeholder='Santos'");
        String ext = in("extName", "Ext. Name (e.g. Jr., III)", "text", "placeholder='Jr. / III'");
        String dob = in("birthdate", "Date of Birth", "date", "required max='" + LocalDate.now() + "'");
        String age = in("age", "Age", "text", "readonly placeholder='Auto-calculated'");
        String sex = selA("sex", "Sex", "required", "Male", "Female");
        String bp = CITY_LIST.isEmpty() ? in("birthPlace", "Place of Birth (Municipality/City)", "text", "placeholder='e.g. Baybay City'")
                : selOther("birthPlace", "Place of Birth (Municipality/City)", null, CITY_LIST);
        String rel = selOther("religion", "Religion", null, Arrays.asList(RELIGIONS));
        String tongue = in("motherTongue", "Mother Tongue", "text", "placeholder='e.g. Tagalog, Cebuano, Waray'");
        String ip = sub("", optsX("ip", "Belonging to Indigenous Peoples (IP) Community?", "radio", true, "Yes", "No")
                + cond("ip:Yes", in("ipSpec", "Specify IP Community", "text", "placeholder='Specify IP Community'")));
        String fp = sub("", optsX("fourPs", "Is your family a beneficiary of 4Ps?", "radio", true, "Yes", "No")
                + cond("fourPs:Yes", in("fourPsId", "4Ps Household ID Number", "text", "inputmode='numeric' placeholder='Write 4Ps Household ID Number'")));
        f.append(step(1, "user", "2. Learner's Personal Information", "Ensure details match the learner's Birth Certificate.",
                psa + grid(4, last, first, mid, ext) + grid(3, dob, age, sex) + grid(3, bp, tongue, rel) + "<div class='grid g2 top'>" + ip + fp + "</div>"));

        // ---- step 3: addresses and parents
        String cur = addr("cur", "current");
        String permSame = hid("permSame", "Is your permanent address the same as your current address?", "No");
        String perm = addr("perm", "permanent");
        String parents = person("father", "Father", "Father's Name", false, false)
                + person("mother", "Mother", "Mother's Maiden Name (Required)", true, false)
                + person("guardian", "Legal guardian", "Legal Guardian's Name (If learner is not living with parents)", false, true);
        f.append(step(2, "house", "3. Addresses &amp; Parent / Guardian Details", "Provide current residence and legal parent/guardian information.",
                "<h3 class='sh'>" + ic("pin") + "Current Address</h3>" + cur
                        + "<div class='sh-row'><h3 class='sh'>" + ic("pin") + "Permanent Address</h3><label class='o checkbox pill'><input type='checkbox' id='permChk'><span>Same as Current Address</span></label></div>"
                        + permSame + "<div id='permBox'>" + perm + "</div><p class='muted' id='permNote' hidden>The permanent address will be saved as the same as the current address.</p>"
                        + "<h3 class='sh top'>" + ic("users") + "4. Parent's / Guardian's Information</h3>" + parents));

     
        String[] a1 = {"Attention Deficit Hyperactivity Disorder", "Autism Spectrum Disorder", "Cerebral Palsy",
                "Emotional-Behavior Disorder", "Hearing Impairment", "Intellectual Disability", "Learning Disability",
                "Multiple Disabilities", "Orthopedic/Physical Handicap", "Speech/Language Disorder",
                "Special Health Problem/Chronic Disease: Cancer", "Special Health Problem/Chronic Disease: Non-Cancer",
                "Visual Impairment: Blind", "Visual Impairment: Low Vision"};
        String[] a2 = {"Difficulty in Applying Knowledge", "Difficulty in Communicating",
                "Difficulty in Displaying Interpersonal Behavior (Emotional and Behavioral)", "Difficulty in Hearing",
                "Difficulty in Mobility (Walking, Climbing and Grasping)", "Difficulty in Performing Adaptive Skills (Self-Care)",
                "Difficulty in Remembering, Concentrating, Paying Attention and Understanding", "Difficulty in Seeing"};
        String sned = sub("5. Special Needs Education Program",
                optsX("sned", "Is the Learner under the Special Needs Education Program?", "radio", false, "Yes", "No")
                        + cond("sned:Yes", "<p class='muted'>If yes, check only one: either from a1 or a2.</p>"
                        + opts("diagnosis", "a1. With diagnosis from a licensed medical specialist", "checkbox", a1)
                        + opts("manifest", "a2. With manifestations", "checkbox", a2)
                        + optsX("pwdId", "b. Does the Learner have a PWD ID?", "radio", false, "Yes", "No")));
        String ret = sub("6. For Returning Learner (Balik-Aral) &amp; Transferees", grid(2,
                in("lastGrade", "Last Grade Level Completed", "text", "placeholder='e.g. Grade 6'"),
                in("lastSY", "Last School Year Completed", "text", "placeholder='e.g. 2025-2026'"),
                in("lastSchool", "Last School Attended", "text", "placeholder='Name of previous school'"),
                in("lastSchoolId", "School ID (6 digits)", "text", "inputmode='numeric' maxlength='6' placeholder='e.g. 104829'")));
        String shs = cond("grade:Grade 11|Grade 12", sub("7. Senior High School",
                opts("semester", "Semester", "radio", "1st", "2nd")
                        + grid(2, in("track", "Track", "text", ""), in("strand", "Strand", "text", ""))));
        String mod = sub("8. Preferred Distance Learning Modality (Check all that apply)",
                opts("modality", "Preferred distance learning modality", "checkbox", "Blended (Combination)", "Modular (Print)", "Modular (Digital)",
                        "Online", "Educational Television", "Radio-Based Instruction", "Homeschooling"));
        f.append(step(3, "pulse", "4. Special Needs, Transfer &amp; Learning Preferences", "Specify special education, transfer history, SHS strands, and distance learning options.",
                sned + ret + shs + mod));

       
        KEYS.add("certify");
        LABELS.put("certify", "Certification accepted");
        KEYS.add("submittedBy");
        LABELS.put("submittedBy", "Submitted by (account email)");
        String review = "<dl class='review'>"
                + "<div><dt>Learner full name</dt><dd id='rvName'></dd></div><div><dt>School year &amp; grade</dt><dd id='rvGrade' class='hl'></dd></div>"
                + "<div><dt>LRN &amp; PSA cert no.</dt><dd id='rvIds'></dd></div><div><dt>DOB &amp; sex</dt><dd id='rvDob'></dd></div>"
                + "<div><dt>Current address</dt><dd id='rvAddr'></dd></div><div><dt>Mother / guardian contact</dt><dd id='rvContact'></dd></div></dl>";
        String certBox = "<div class='cert'><p><em>\"I hereby certify that the above information given are true and correct to the best of my knowledge and I allow the Department of Education to process the learner's personal information to create and/or update his/her learner profile in the Learner Information System.\"</em></p>"
                + "<p><b>The personal information herein shall be treated as confidential in compliance with the Data Privacy Act of 2012.</b></p></div>"
                + "<label class='o checkbox certrow'><input type='checkbox' id='certify' name='certify' value='Yes'><span>I agree to the legal certification statement above and confirm the accuracy of all submitted details. <i class='req'>*</i></span></label>"
                + "<p class='err' id='certErr' hidden>You must certify accuracy before submitting.</p>"
                + "<div class='grid g3'>" + in("signDate", "Date Signed", "date", "readonly value='" + LocalDate.now() + "'") + "</div>";
        f.append(step(4, "file", "5. Review &amp; Legal Certification", "Confirm details and sign the compliance certification.", review + certBox));

        f.append("<div class='foot'><button type='button' class='btn' id='prev' disabled>" + ic("left") + "Previous</button>"
                + "<button type='button' class='btn primary' id='next'>Next Step" + ic("right") + "</button>"
                + "<button type='submit' class='btn ok' id='submitBtn' hidden>Submit Registration" + ic("send") + "</button></div>");
        return f.append("</div></form>").toString();
    }


    //google sheets
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

    //pag ioopen
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
                    i++; 
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

   

    static String page(String title, String body, String script) {
        return "<!doctype html><html lang='en'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'><title>" + esc(title) + "</title>"
                + "<link rel='preconnect' href='https://fonts.googleapis.com'>"
                + "<link href='https://fonts.googleapis.com/css2?family=Plus+Jakarta+Sans:wght@400;500;600;700;800&display=swap' rel='stylesheet'>"
                + "<style>" + CSS + "</style></head><body>"
                + "<div class='top'><div class='top-in'><span class='logo'>" + ic("cap") + "</span><div class='brand'><b>Taal High School - Department of Education</b><small>Taal High School Enrollment Portal</small></div>"
                + "<div class='top-chips'><span class='chip amber'>" + ic("shield") + "Official Enrollment Portal</span><span class='chip green'><i class='dot'></i>Registration Open</span></div></div></div>"
                + "<main class='wrap'>" + body + "</main><script>" + script + "</script></body></html>";
    }

    static final String CSS = """
        :root{--bg:#f8fafc;--ink:#0f172a;--mut:#64748b;--line:#e2e8f0;--brand:#0a5c8f;--navy:#0b2447;--ok:#10b981;--okd:#067a52;--err:#e11d48;--soft:#f1f5f9;color-scheme:light}
        *{box-sizing:border-box}
        body{margin:0;background:var(--bg);color:var(--ink);font:15px/1.5 "Plus Jakarta Sans","Segoe UI",system-ui,sans-serif;-webkit-font-smoothing:antialiased;padding:env(safe-area-inset-top,0) 0 env(safe-area-inset-bottom,0)}
        [hidden]{display:none!important}
        .ic{width:18px;height:18px;flex:none;fill:none;stroke:currentColor;stroke-width:2;stroke-linecap:round;stroke-linejoin:round}
        .top{background:#fff;border-bottom:1px solid var(--line)}
        .top-in{max-width:1040px;margin:0 auto;padding:12px 16px;display:flex;align-items:center;gap:12px;flex-wrap:wrap}
        .logo{width:44px;height:44px;border-radius:12px;display:grid;place-items:center;color:#fff;background:linear-gradient(145deg,#1c86c7,#0a5c8f);box-shadow:0 6px 14px rgba(10,92,143,.3)}
        .logo .ic{width:24px;height:24px}
        .brand{flex:1;min-width:190px;line-height:1.2}.brand b{display:block;font-size:1.05rem;font-weight:800}.brand small{color:var(--brand);font-weight:600;font-size:.76rem}
        .top-chips{display:flex;gap:8px;flex-wrap:wrap}
        .chip{display:inline-flex;align-items:center;gap:6px;border-radius:999px;padding:5px 12px;font-size:.72rem;font-weight:700;border:1px solid transparent}
        .chip .ic{width:13px;height:13px}
        .chip.amber{background:#fef3c7;color:#b45309;border-color:#fcd34d}
        .chip.green{background:#d1fae5;color:#047857;border-color:#6ee7b7}.chip .dot{width:7px;height:7px;border-radius:50%;background:var(--ok)}
        .chip.dark{background:rgba(255,255,255,.14);color:#e2e8f0;text-transform:uppercase;letter-spacing:.4px}
        .chip.gold{background:#fbbf24;color:#422006;text-transform:uppercase;letter-spacing:.4px}
        .wrap{max-width:1040px;margin:0 auto;padding:20px 16px 80px}
        .hero{position:relative;overflow:hidden;border-radius:24px;padding:26px 28px;color:#fff;background:linear-gradient(100deg,#0b2447 0%,#0a4a7c 62%,#0a5c8f 100%);box-shadow:0 14px 30px rgba(11,36,71,.28)}
        .hero-in{position:relative;z-index:1;max-width:680px}
        .chips{display:flex;gap:8px;flex-wrap:wrap;margin-bottom:12px}
        .hero h1{margin:0 0 6px;font-size:clamp(1.5rem,4.5vw,2.1rem);font-weight:800;letter-spacing:-.02em;line-height:1.15}
        .hero p{margin:0;color:#dbe7f3;font-size:.93rem}
        .hero-ic{position:absolute;right:-6px;bottom:-14px;opacity:.14;color:#fff}.hero-ic .ic{width:150px;height:150px;stroke-width:1.5}
        .hero-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
        .gbtn{display:inline-flex;align-items:center;gap:7px;padding:8px 14px;border-radius:10px;font-weight:600;font-size:.84rem;font-family:inherit;color:#fff;text-decoration:none;background:rgba(255,255,255,.12);border:1px solid rgba(255,255,255,.28)}
        .gbtn:hover{background:rgba(255,255,255,.2)}.gbtn.solid{background:#fff;color:var(--brand);border-color:#fff}
        .stepper{background:#fff;border:1px solid var(--line);border-radius:20px;padding:18px 8px 14px;margin:20px 0;box-shadow:0 1px 3px rgba(15,23,42,.05)}
        .stepper ol{list-style:none;margin:0;padding:0;display:flex;position:relative}
        .stepper ol::before,.stepper ol::after{content:"";position:absolute;top:22px;left:10%;height:4px;border-radius:4px}
        .stepper ol::before{right:10%;background:#e8edf3}
        .stepper ol::after{background:var(--brand);width:calc(80% * var(--p,0));transition:width .3s}
        .st{flex:1;text-align:center;position:relative;z-index:1;min-width:0}
        .dot{display:grid;place-items:center;width:48px;height:48px;margin:0 auto 8px;border:0;border-radius:14px;background:#eef2f7;color:#94a3b8;cursor:default;font:inherit}
        .dot .ic{width:21px;height:21px}.dot .ck{display:none}
        .st .lbl{display:block;font-size:.72rem;font-weight:600;color:#94a3b8;padding:0 2px}
        .st.active .dot{background:var(--brand);color:#fff;box-shadow:0 0 0 4px rgba(10,92,143,.16),0 8px 16px rgba(10,92,143,.3)}.st.active .lbl{color:var(--brand);font-weight:700}
        .st.done .dot{background:var(--ok);color:#fff;cursor:pointer}.st.done .ck{display:block}.st.done .mi{display:none}.st.done .lbl{color:var(--okd)}
        .dot:focus-visible,.btn:focus-visible,.gbtn:focus-visible{outline:3px solid rgba(10,92,143,.4);outline-offset:2px}
        .card{background:#fff;border:1px solid var(--line);border-radius:24px;padding:26px 28px;margin:0 0 22px;box-shadow:0 10px 28px rgba(15,23,42,.07)}
        .card>h2,.panel h2{margin:0;font-size:1.2rem;font-weight:700;letter-spacing:-.01em}
        .card>h2{margin-bottom:12px}
        .ph{display:flex;align-items:center;gap:12px;padding-bottom:16px;margin-bottom:20px;border-bottom:1px solid var(--line)}
        .pic{width:38px;height:38px;border-radius:10px;display:grid;place-items:center;background:#e0f0fa;color:var(--brand);flex:none}
        .ph .muted{margin:2px 0 0}
        .muted{color:var(--mut);font-size:.88rem;margin:0 0 12px}
        .grid{display:grid;gap:16px;margin-bottom:16px}.grid.top{align-items:start}
        .g2{grid-template-columns:repeat(2,1fr)}.g3{grid-template-columns:repeat(3,1fr)}.g4{grid-template-columns:repeat(4,1fr)}
        @media(max-width:760px){.g3,.g4{grid-template-columns:repeat(2,1fr)}.card{padding:22px 18px}.hero{padding:22px 20px}}
        @media(max-width:520px){.g2,.g3{grid-template-columns:1fr}.top-chips{width:100%}.stepper .lbl{font-size:.64rem}.dot{width:42px;height:42px}.stepper ol::before,.stepper ol::after{top:19px}}
        .f{display:flex;flex-direction:column;gap:6px;font-size:.8rem;font-weight:700;min-width:0}
        .req{color:var(--err);font-style:normal}
        .f input,.f select,.pin,.row2 select{width:100%;height:46px;padding:0 14px;border:1px solid var(--line);border-radius:12px;background:#fafbfd;color:var(--ink);font-weight:500;font-size:.92rem;font-family:inherit;outline:0}
        .f input::placeholder,.pin::placeholder{color:#94a3b8;font-weight:400}
        .f input:focus,.f select:focus,.pin:focus,.row2 select:focus{border-color:var(--brand);box-shadow:0 0 0 3px rgba(10,92,143,.15);background:#fff}
        .f input[readonly]{background:var(--soft);color:var(--mut)}
        .f input:not([type=date]):not([readonly]):not([type=hidden]),.pin{text-transform:uppercase}
        .f input::placeholder,.pin::placeholder{text-transform:none}
        .f select,.row2 select{appearance:none;-webkit-appearance:none;padding-right:38px;cursor:pointer;background-image:linear-gradient(45deg,transparent 50%,#64748b 50%),linear-gradient(135deg,#64748b 50%,transparent 50%);background-position:calc(100% - 19px) 52%,calc(100% - 13px) 52%;background-size:6px 6px;background-repeat:no-repeat}
        .hint{font-weight:400;color:var(--mut);font-size:.74rem}
        .other-input{margin-top:6px}
        .group{background:#f8fafc;border:1px solid var(--line);border-radius:16px;padding:18px;margin:0 0 16px}
        .group>h3{margin:0 0 14px;font-size:.98rem;font-weight:700}
        .group .grid:last-child{margin-bottom:0}
        .gt{margin:0 0 12px;font-size:.74rem;font-weight:800;letter-spacing:.5px;text-transform:uppercase;color:var(--brand)}
        .lab{display:block;margin-bottom:8px;font-size:.84rem;font-weight:700}
        .row2{display:grid;grid-template-columns:1fr 1fr;gap:14px;align-items:center}
        @media(max-width:620px){.row2{grid-template-columns:1fr}}
        .sh{display:flex;align-items:center;gap:8px;margin:6px 0 14px;font-size:1rem;font-weight:700}.sh .ic{color:var(--brand);width:17px;height:17px}.sh.top{margin-top:26px;padding-top:20px;border-top:1px solid var(--line)}
        .sh-row{display:flex;justify-content:space-between;align-items:center;gap:10px;flex-wrap:wrap;margin-top:8px}.sh-row .sh{margin:0 0 14px}
        .q{display:flex;justify-content:space-between;align-items:center;gap:10px 20px;flex-wrap:wrap;margin:0 0 12px}.q.stack{flex-direction:column;align-items:flex-start}
        .ql{font-size:.86rem;font-weight:700}
        .opts{display:flex;flex-wrap:wrap;gap:8px 18px}.opts.checkboxs{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;width:100%}
        @media(max-width:760px){.opts.checkboxs{grid-template-columns:repeat(2,1fr)}}@media(max-width:520px){.opts.checkboxs{grid-template-columns:1fr}}
        .o{display:flex;align-items:center;gap:9px;cursor:pointer;font-size:.88rem;font-weight:500}
        .o input{width:17px;height:17px;margin:0;accent-color:var(--brand);flex:none}
        .o.box{background:#fff;border:1px solid var(--line);border-radius:12px;padding:12px 14px}
        .o.box:has(input:checked){border-color:var(--brand);background:#eef7fc}
        .o.pill{background:#e8f4fc;border:1px solid #bfe0f5;border-radius:10px;padding:7px 12px;color:var(--brand);font-weight:600;font-size:.8rem}
        .certrow{align-items:flex-start;margin-top:16px}.certrow input{margin-top:3px}
        .err{color:var(--err);font-size:.8rem;margin:6px 0 0}
        .review{display:grid;grid-template-columns:1fr 1fr;gap:16px 24px;background:#f8fafc;border:1px solid var(--line);border-radius:16px;padding:18px;margin:0 0 18px}
        .review dt{font-size:.7rem;font-weight:700;letter-spacing:.4px;text-transform:uppercase;color:#94a3b8}.review dd{margin:2px 0 0;font-weight:700;overflow-wrap:anywhere}.review .hl{color:var(--brand)}
        @media(max-width:620px){.review{grid-template-columns:1fr}}
        .cert{background:#fffbeb;border:1px solid #fde68a;border-radius:14px;padding:14px 18px;color:#92400e;font-size:.85rem}.cert p{margin:0 0 8px}.cert p:last-child{margin:0}
        .foot{display:flex;justify-content:space-between;gap:12px;margin-top:22px;padding-top:20px;border-top:1px solid var(--line)}
        .btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:11px 20px;border-radius:12px;border:1px solid var(--line);background:#fff;color:var(--ink);font-weight:600;font-size:.9rem;font-family:inherit;text-decoration:none;cursor:pointer}
        .btn:hover{background:var(--soft)}.btn:disabled{opacity:.45;cursor:not-allowed}
        .btn.primary{background:var(--brand);border-color:var(--brand);color:#fff;box-shadow:0 8px 16px rgba(10,92,143,.28)}.btn.primary:hover{background:#094f7b}
        .btn.ok{background:var(--okd);border-color:var(--okd);color:#fff;box-shadow:0 8px 16px rgba(6,122,82,.28)}
        .actions{display:flex;flex-wrap:wrap;gap:12px;margin-top:20px}.actions.mid{justify-content:center}.btn.wide{flex:1 1 100%}
        .sum{display:grid;grid-template-columns:minmax(140px,240px) 1fr;gap:10px 20px;margin:0}.sum dt{color:var(--mut);font-size:.86rem}.sum dd{margin:0;font-weight:600;overflow-wrap:anywhere}
        @media(max-width:540px){.sum{grid-template-columns:1fr}}
        .done{max-width:480px;margin:10px auto 22px;text-align:center}.done .muted{margin-bottom:16px}
        .okic{width:56px;height:56px;border-radius:50%;display:grid;place-items:center;margin:0 auto 12px;background:#d1fae5;color:var(--okd)}.okic .ic{width:28px;height:28px}
        .ref{text-align:left;background:#f8fafc;border:1px solid var(--line);border-radius:12px;padding:12px 16px;font-size:.88rem}.ref span{color:var(--brand)}
        .tiles{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:18px;margin-top:20px}
        .tile{display:flex;align-items:center;gap:16px;padding:20px;background:#fff;border:1px solid var(--line);border-radius:20px;color:var(--ink);text-decoration:none;box-shadow:0 6px 18px rgba(15,23,42,.06)}
        .tile:hover{border-color:var(--brand);box-shadow:0 10px 24px rgba(10,92,143,.14)}
        .tile-ic{width:48px;height:48px;border-radius:14px;display:grid;place-items:center;background:#e0f0fa;color:var(--brand);flex:none}.tile-ic .ic{width:22px;height:22px}
        .tile-b{flex:1;display:flex;flex-direction:column;gap:2px;min-width:0}.tile-b b{font-size:1.02rem;font-weight:700}.tile-b span{color:var(--mut);font-size:.84rem}
        .tile-n{min-width:44px;height:44px;padding:0 12px;border-radius:12px;display:grid;place-items:center;background:var(--soft);font-size:1.25rem;font-weight:800;color:var(--brand)}
        .need{grid-column:1/-1;margin:0}.need ul{margin:0;padding-left:20px;color:var(--mut);display:grid;gap:6px}
        .scroll{overflow-x:auto;border:1px solid var(--line);border-radius:14px}
        table{width:100%;border-collapse:collapse;font-size:.88rem}
        th,td{text-align:left;padding:11px 14px;white-space:nowrap;border-bottom:1px solid var(--line)}tr:last-child td{border-bottom:0}
        th{background:var(--soft);color:var(--mut);font-weight:700;font-size:.78rem}
        .lnk{color:var(--brand);font-weight:700;text-decoration:none;padding:5px 12px;border-radius:8px;background:#e0f0fa}
        @media (prefers-reduced-motion:reduce){*{transition:none!important}}
        @media print{body{background:#fff}.top,.hero,.stepper,.actions,.foot{display:none}.card{box-shadow:none;border:1px solid #ccc}}
        """;

    static final String JS = """
        const form = document.getElementById('enrollForm');
        const panels = [...form.querySelectorAll('.panel')], steps = [...form.querySelectorAll('.st')];
        const prev = document.getElementById('prev'), next = document.getElementById('next'), sbtn = document.getElementById('submitBtn');
        const cert = document.getElementById('certify'), certErr = document.getElementById('certErr');
        let cur = 0;
        function val(n){
          const els = document.getElementsByName(n);
          if(!els.length) return '';
          const t = els[0].type;
          if(t === 'radio' || t === 'checkbox') return [...els].filter(e => e.checked).map(e => e.value).join('|');
          return els[0].value;
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
        refresh();

        /* grade: "Non-Graded" checkbox drives the hidden gradeType value */
        const ng = document.getElementById('nonGraded'), gsel = document.getElementById('grade');
        ng.addEventListener('change', () => {
          form.elements.gradeType.value = ng.checked ? 'Non-graded (SNEd only)' : 'Graded';
          gsel.disabled = ng.checked; gsel.required = !ng.checked;
          if(ng.checked) gsel.value = '';
          refresh();
        });
        /* permanent address: same-as-current checkbox drives the hidden permSame value */
        const pc = document.getElementById('permChk'), pb = document.getElementById('permBox'), pn = document.getElementById('permNote');
        pc.addEventListener('change', () => {
          form.elements.permSame.value = pc.checked ? 'Yes' : 'No';
          pb.hidden = pc.checked; pn.hidden = !pc.checked;
        });
        /* guardian contact is only required once a guardian name is typed */
        const gIn = ['guardianLast', 'guardianFirst', 'guardianMiddle'].map(n => form.elements[n]);
        gIn.forEach(e => e.addEventListener('input', () => { form.elements.guardianContact.required = gIn.some(x => x.value.trim() !== ''); }));

        /* wizard */
        function fv(n){
          const e = form.elements[n];
          if(!e) return '';
          if(e.tagName === 'SELECT' && e.value === '__other__'){
            const o = e.parentElement.querySelector('.other-input');
            return o ? o.value.trim() : '';
          }
          return (e.value || '').trim();
        }
        function fillReview(){
          const set = (id, t) => { document.getElementById(id).textContent = t || '-'; };
          const given = [fv('firstName'), fv('middleName'), fv('extName')].filter(Boolean).join(' ');
          set('rvName', [fv('lastName'), given].filter(Boolean).join(', ').toUpperCase());
          const sy = form.elements.syFrom.value + ' - ' + form.elements.syTo.value;
          set('rvGrade', (form.elements.gradeType.value === 'Graded' ? fv('grade') : 'Non-graded (SNED)') + ' (SY ' + sy + ')');
          set('rvIds', 'LRN: ' + (fv('lrn') || '-') + ' | PSA: ' + (fv('psa') || '-'));
          set('rvDob', (fv('birthdate') || '-') + ' (' + (fv('sex') || '-') + ')');
          set('rvAddr', ['curHouse', 'curStreet', 'curBarangay', 'curCity', 'curProvince'].map(fv).filter(Boolean).join(', '));
          const who = (p) => [fv(p + 'First'), fv(p + 'Last')].filter(Boolean).join(' ');
          const parts = [];
          if(who('mother')) parts.push(who('mother') + (fv('motherContact') ? ' - ' + fv('motherContact') : ''));
          if(who('guardian')) parts.push(who('guardian') + (fv('guardianContact') ? ' - ' + fv('guardianContact') : ''));
          set('rvContact', parts.join(' / '));
        }
        function show(i){
          cur = i;
          panels.forEach((p, k) => { p.hidden = k !== i; });
          steps.forEach((s, k) => { s.classList.toggle('active', k === i); s.classList.toggle('done', k < i); });
          form.style.setProperty('--p', i / (steps.length - 1));
          prev.disabled = i === 0;
          next.hidden = i === steps.length - 1;
          sbtn.hidden = !next.hidden;
          if(i === steps.length - 1) fillReview();
          document.querySelector('.stepper').scrollIntoView({behavior: 'smooth', block: 'start'});
        }
        function valid(i){
          const els = [...panels[i].querySelectorAll('input,select')].filter(e => !e.disabled && !e.closest('[hidden]'));
          for(const e of els){ if(!e.checkValidity()){ e.reportValidity(); return false; } }
          return true;
        }
        next.addEventListener('click', () => { if(valid(cur)) show(cur + 1); });
        prev.addEventListener('click', () => { if(cur > 0) show(cur - 1); });
        form.querySelectorAll('[data-go]').forEach(b => b.addEventListener('click', () => { const k = +b.dataset.go; if(k < cur) show(k); }));
        cert.addEventListener('change', () => { certErr.hidden = cert.checked; });
        form.addEventListener('submit', e => {
          if(cur < steps.length - 1){ e.preventDefault(); if(valid(cur)) show(cur + 1); return; }
          if(!cert.checked){ e.preventDefault(); certErr.hidden = false; cert.focus(); return; }
          form.querySelectorAll('select[data-other]').forEach(s => {
            if(s.value !== '__other__') return;
            const t = s.parentElement.querySelector('.other-input').value.trim();
            if(t){ const o = s.options[s.selectedIndex]; o.value = t; o.textContent = t; }
            else { s.value = ''; }
          });
          sbtn.disabled = true;
        });

        /* dropdowns with an "Others (type it)" box, and Province -> City -> Barangay */
        function syncOther(sel){
          const box = sel.parentElement.querySelector('.other-input');
          if(!box) return;
          const on = sel.value === '__other__';
          box.hidden = !on; box.required = on;
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
        }
        provinceSelects.forEach(wireAddress);
        form.querySelectorAll('select[data-other]').forEach(s => s.addEventListener('change', () => syncOther(s)));
        """;
}
