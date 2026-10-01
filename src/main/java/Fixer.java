import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

public class Fixer {

    public static String compile(Path src, Path out, String cp, Consumer<String> log) throws Exception {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(dc, null, null)) {
            List<String> opts = new ArrayList<>(List.of("-d", out.toString(), "-proc:none", "-Xlint:none",
                    "--release", String.valueOf(Runtime.version().feature())));
            if (!cp.isEmpty()) opts.addAll(List.of("-cp", cp));
            boolean ok = jc.getTask(null, fm, dc, opts, null, fm.getJavaFileObjects(src)).call();
            if (!ok) { reportTo(dc, log); return null; }
            return src.getFileName().toString().replaceFirst("\\.java$", "");
        }
    }

    static void reportTo(DiagnosticCollector<JavaFileObject> dc, Consumer<String> log) {
        for (var d : dc.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR && d.getKind() != Diagnostic.Kind.WARNING) continue;
            String f = d.getSource() == null ? "" : Path.of(d.getSource().toUri()).getFileName() + ":" + d.getLineNumber() + ": ";
            log.accept(f + d.getKind().toString().toLowerCase() + ": " + d.getMessage(null) + "\n");
        }
    }
}
