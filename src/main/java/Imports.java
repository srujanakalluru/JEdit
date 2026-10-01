import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.regex.*;
import java.util.stream.*;

public class Imports {
    static final Pattern NOISE  = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?\\*/|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");
    static final Pattern IMPORT = Pattern.compile("^[ \\t]*import\\s+(static\\s+)?([\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);
    static final Pattern HEADER = Pattern.compile("^[ \\t]*(?:package|import)\\b[^;]*;", Pattern.MULTILINE);
    static final Pattern PKG    = Pattern.compile("^[ \\t]*package\\s+[\\w.]+\\s*;[^\\n]*\\n?", Pattern.MULTILINE);
    static final Pattern DECL   = Pattern.compile("\\b(?:class|interface|enum|record)\\s+(\\w+)");
    static final Pattern TYPE   = Pattern.compile("\\b[A-Z]\\w*\\b");

    public record Ref(String name, int start, List<String> cands) {}

    private volatile Map<String, List<String>> index = Map.of();
    private String loadedCp;

    public synchronized void load(String cp) throws IOException {
        if (cp.equals(loadedCp)) return;
        Map<String, List<String>> idx = new HashMap<>();
        Set<String> pkgs = new HashSet<>();
        for (Module m : ModuleLayer.boot().modules())
            m.getDescriptor().exports().stream().filter(e -> !e.isQualified()).forEach(e -> pkgs.add(e.source()));
        FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        try (Stream<Path> w = Files.walk(jrt.getPath("/modules"))) {
            w.forEach(p -> {
                String s = p.toString();
                if (!s.endsWith(".class") || s.contains("$")) return;
                add(idx, pkgs, s.substring(s.indexOf('/', "/modules/".length()) + 1));
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
        Comparator<String> rank = Comparator.comparingInt(Imports::score).thenComparing(Comparator.naturalOrder());
        idx.values().forEach(l -> l.sort(rank));
        index = idx;
        loadedCp = cp;
    }

    public List<Ref> unresolved(String src) {
        Map<String, List<String>> idx = index;
        String code = blank(HEADER, blank(NOISE, src));

        Set<String> known = new HashSet<>();
        Set<String> wildcards = new HashSet<>(Set.of("java.lang"));
        Matcher im = IMPORT.matcher(blank(NOISE, src));
        while (im.find()) {
            if (im.group(3) != null) wildcards.add(im.group(2));
            else known.add(im.group(2).substring(im.group(2).lastIndexOf('.') + 1));
        }
        Matcher dm = DECL.matcher(code);
        while (dm.find()) known.add(dm.group(1));

        List<Ref> refs = new ArrayList<>();
        Matcher tm = TYPE.matcher(code);
        while (tm.find()) {
            String name = tm.group();
            if (name.length() == 1 || known.contains(name) || (tm.start() > 0 && code.charAt(tm.start() - 1) == '.')) continue;
            List<String> cands = idx.getOrDefault(name, List.of());
            if (cands.isEmpty() || cands.stream().anyMatch(c -> wildcards.contains(c.substring(0, c.lastIndexOf('.'))))) continue;
            refs.add(new Ref(name, tm.start(), cands));
        }
        return refs;
    }

    public static int insertAt(String src) {
        Matcher im = IMPORT.matcher(src);
        int end = -1;
        while (im.find()) end = im.end();
        if (end >= 0) return src.indexOf('\n', end) + 1;
        Matcher pm = PKG.matcher(src);
        if (pm.find()) return pm.end();
        Matcher lead = Pattern.compile("\\A(?:[ \\t]*//[^\\n]*\\n|[ \\t]*\\n)*").matcher(src);
        return lead.find() ? lead.end() : 0;
    }

    public static String importLine(String src, String fq) {
        if (IMPORT.matcher(src).find()) return "import " + fq + ";\n";
        if (PKG.matcher(src).find()) return "\nimport " + fq + ";\n";
        return "import " + fq + ";\n\n";
    }

    static String blank(Pattern p, String s) {
        return p.matcher(s).replaceAll(m -> m.group().replaceAll("[^\\n]", " "));
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
}
