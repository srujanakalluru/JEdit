import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.*;

public class Fixer {
    static final Pattern MISSING = Pattern.compile("symbol:\\s+(?:class|variable) (\\w+)");
    static final Pattern PKG = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    @FunctionalInterface
    public interface Chooser {
        String choose(String name, List<String> cands) throws Exception;
    }

    public static void main(String[] a) throws Exception {
        Path src = Path.of(a[0]), out = Path.of(a[1]);
        String cp = a.length > 2 ? a[2] : "";
        String mode = a.length > 3 ? a[3] : "-n";
        boolean auto = mode.equals("-y"), tty = mode.equals("-i");
        String main = compile(src, out, cp,
                (name, cands) -> choose(name, cands, auto, tty),
                System.err::print);
        if (main != null) System.out.println(main);
        else System.exit(1);
    }

    public static String compile(Path src, Path out, String cp, Chooser chooser, Consumer<String> log) throws Exception {
        Set<String> declined = new HashSet<>();
        Map<String, List<String>> index = null;
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();

        for (int round = 0; round < 20; round++) {
            DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = jc.getStandardFileManager(dc, null, null)) {
                List<String> opts = new ArrayList<>(List.of("-d", out.toString(), "-proc:none", "-Xlint:none",
                        "--release", String.valueOf(Runtime.version().feature())));
                if (!cp.isEmpty()) opts.addAll(List.of("-cp", cp));
                boolean ok = jc.getTask(null, fm, dc, opts, null, fm.getJavaFileObjects(src)).call();
                if (ok) return mainClass(src, out, cp);
            }
            LinkedHashSet<String> missing = new LinkedHashSet<>();
            for (var d : dc.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                Matcher m = MISSING.matcher(d.getMessage(null));
                if (m.find() && Character.isUpperCase(m.group(1).charAt(0)) && !declined.contains(m.group(1)))
                    missing.add(m.group(1));
            }
            if (missing.isEmpty()) { reportTo(dc, log); return null; }
            if (index == null) index = buildIndex(cp);

            List<String> imports = new ArrayList<>();
            for (String name : missing) {
                String pick = chooser.choose(name, index.getOrDefault(name, List.of()));
                if (pick == null) declined.add(name); else imports.add(pick);
            }
            if (imports.isEmpty()) { reportTo(dc, log); return null; }
            addImports(src, imports);
        }
        log.accept("jrun: gave up after 20 import rounds\n");
        return null;
    }

    static final BufferedReader IN = new BufferedReader(new InputStreamReader(System.in));

    static String ask(String prompt) throws IOException { System.err.print(prompt); System.err.flush(); return IN.readLine(); }

    static String choose(String name, List<String> cands, boolean auto, boolean tty) throws IOException {
        if (cands.isEmpty()) { System.err.println("jrun: no import found for '" + name + "'"); return null; }
        if (auto || !tty) {
            if (cands.size() == 1 || auto) { System.err.println("jrun: + import " + cands.get(0)); return cands.get(0); }
            System.err.println("jrun: '" + name + "' is ambiguous: " + cands); return null;
        }
        if (cands.size() == 1) {
            String r = ask("Missing '" + name + "'. Add import " + cands.get(0) + "? [Y/n] ");
            return r == null || r.isBlank() || r.trim().toLowerCase().startsWith("y") ? cands.get(0) : null;
        }
        System.err.println("Missing '" + name + "'. Choose import:");
        int n = Math.min(cands.size(), 9);
        for (int i = 0; i < n; i++) System.err.println("  " + (i + 1) + ") " + cands.get(i));
        String r = ask("  [1-" + n + ", Enter=1, s=skip] ");
        if (r == null || r.isBlank()) return cands.get(0);
        try { int i = Integer.parseInt(r.trim()); return i >= 1 && i <= n ? cands.get(i - 1) : null; }
        catch (NumberFormatException e) { return null; }
    }

    static void addImports(Path src, List<String> imports) throws IOException {
        String s = Files.readString(src);
        String block = imports.stream().sorted().map(i -> "import " + i + ";\n").collect(Collectors.joining());
        Matcher pm = PKG.matcher(s);
        int at = 0;
        if (pm.find()) at = s.indexOf('\n', pm.end()) + 1;
        else {
            Matcher lead = Pattern.compile("\\A(?:\\s*//[^\\n]*\\n)*").matcher(s);
            if (lead.find()) at = lead.end();
        }
        Files.writeString(src, s.substring(0, at) + block + s.substring(at));
    }

    static Map<String, List<String>> buildIndex(String cp) throws IOException {
        Map<String, List<String>> idx = new HashMap<>();
        Set<String> pkgs = new HashSet<>();
        for (Module m : ModuleLayer.boot().modules())
            m.getDescriptor().exports().stream().filter(e -> !e.isQualified()).forEach(e -> pkgs.add(e.source()));
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        try (Stream<Path> w = Files.walk(jrt.getPath("/modules"))) {
            w.forEach(p -> {
                String s = p.toString();
                if (!s.endsWith(".class") || s.contains("$")) return;
                String rel = s.substring(s.indexOf('/', "/modules/".length()) + 1);
                add(idx, pkgs, rel);
            });
        }
        for (String e : cp.split(File.pathSeparator)) {
            if (!e.endsWith(".jar") || !Files.exists(Path.of(e))) continue;
            try (JarFile jf = new JarFile(e)) {
                jf.stream().map(JarEntry::getName)
                  .filter(n -> n.endsWith(".class") && !n.contains("$") && !n.startsWith("META-INF"))
                  .forEach(n -> add(idx, null, n));
            }
        }
        Comparator<String> rank = Comparator.comparingInt(Fixer::score).thenComparing(Comparator.naturalOrder());
        idx.values().forEach(l -> l.sort(rank));
        return idx;
    }

    static void add(Map<String, List<String>> idx, Set<String> pkgs, String rel) {
        String fq = rel.substring(0, rel.length() - 6).replace('/', '.');
        int dot = fq.lastIndexOf('.');
        if (dot < 0) return;
        if (pkgs != null && !pkgs.contains(fq.substring(0, dot))) return;
        if (fq.contains(".internal.") || fq.contains(".impl.")) return;
        idx.computeIfAbsent(fq.substring(dot + 1), k -> new ArrayList<>()).add(fq);
    }

    static int score(String fq) {
        if (fq.startsWith("java.util.") && fq.indexOf('.', 10) < 0) return 0;
        if (fq.startsWith("java.io.") || fq.startsWith("java.nio.file.") || fq.startsWith("java.time.")) return 1;
        if (fq.startsWith("java.")) return 2;
        if (fq.startsWith("javax.")) return 4;
        if (fq.startsWith("jdk.") || fq.startsWith("com.sun.") || fq.startsWith("sun.")) return 6;
        return 3;
    }

    static String mainClass(Path src, Path out, String cp) throws Exception {
        String s = Files.readString(src);
        String name = src.getFileName().toString().replaceFirst("\\.java$", "");
        Matcher pm = PKG.matcher(s);
        String fq = pm.find() ? pm.group(1) + "." + name : name;
        if (Files.exists(out.resolve(fq.replace('.', '/') + ".class"))) return fq;
        List<java.net.URL> urls = new ArrayList<>(List.of(out.toUri().toURL()));
        for (String e : cp.split(File.pathSeparator)) if (!e.isEmpty()) urls.add(Path.of(e).toUri().toURL());
        try (var cl = new java.net.URLClassLoader(urls.toArray(java.net.URL[]::new));
             Stream<Path> w = Files.walk(out)) {
            for (Path p : w.filter(p -> p.toString().endsWith(".class")).toList()) {
                String cn = out.relativize(p).toString().replace(File.separatorChar, '.').replaceFirst("\\.class$", "");
                for (var m : Class.forName(cn, false, cl).getDeclaredMethods())
                    if (m.getName().equals("main")) return cn;
            }
        }
        return fq;
    }

    static void reportTo(DiagnosticCollector<JavaFileObject> dc, Consumer<String> log) {
        for (var d : dc.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR && d.getKind() != Diagnostic.Kind.WARNING) continue;
            String f = d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ";
            log.accept(f + d.getKind().toString().toLowerCase() + ": " + d.getMessage(null) + "\n");
        }
    }
}
