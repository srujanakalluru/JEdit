import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.undo.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.*;

public class Editor extends JFrame {
    static final String JRUN_HOME = System.getenv().getOrDefault("JRUN_HOME",
            System.getProperty("user.home") + "/.jrun");
    static final String MVN  = JRUN_HOME + "/maven/bin/mvn";
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private final JTextArea code   = new JTextArea();
    private final JTextArea output = new JTextArea();
    private final JLabel    status = new JLabel(" Ready");
    private final JButton       runBtn = new JButton("▶ Run");
    private final UndoManager   undo   = new UndoManager();
    private Path    file;
    private boolean dirty;

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "jrun");
        Path p = args.length > 0 ? Path.of(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new Editor(p).setVisible(true);
        });
    }

    Editor(Path p) {
        super("jrun");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) { maybeQuit(); }
        });
        buildUI();
        Path autosave = Path.of(JRUN_HOME, "autosave.java");
        if (p != null) {
            openFile(p);
        } else if (Files.exists(autosave)) {
            try { code.setText(Files.readString(autosave)); Files.deleteIfExists(autosave); dirty = true; updateTitle(); }
            catch (IOException ignored) { newFile(); }
        } else {
            newFile();
        }
        setSize(940, 700);
        setLocationRelativeTo(null);
    }

    void buildUI() {
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        code.setTabSize(4);
        code.getDocument().addUndoableEditListener(undo);
        code.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markDirty(); }
            public void removeUpdate(DocumentEvent e) { markDirty(); }
            public void changedUpdate(DocumentEvent e) {}
        });

        output.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        output.setEditable(false);
        output.setBackground(new Color(40, 44, 52));
        output.setForeground(new Color(171, 178, 191));
        output.setCaretColor(new Color(171, 178, 191));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(code), new JScrollPane(output));
        split.setResizeWeight(0.72);

        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.add(btn("New",   e -> { if (confirmDiscard()) newFile(); }));
        bar.add(btn("Open…", e -> { if (confirmDiscard()) chooseOpen(); }));
        bar.add(btn("Save",  e -> save()));
        bar.addSeparator();
        runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD));
        runBtn.addActionListener(e -> doRun());
        bar.add(runBtn);

        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 0));
        add(bar,    BorderLayout.NORTH);
        add(split,  BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        bind(KeyEvent.VK_S, mod, e -> save());
        bind(KeyEvent.VK_R, mod, e -> doRun());
        bind(KeyEvent.VK_Z, mod, e -> { if (undo.canUndo()) undo.undo(); });
        bind(KeyEvent.VK_Z, mod | KeyEvent.SHIFT_DOWN_MASK, e -> { if (undo.canRedo()) undo.redo(); });
        bind(KeyEvent.VK_SLASH,      mod, e -> toggleComment());
        bind(KeyEvent.VK_D,          mod, e -> duplicateLine());
        bind(KeyEvent.VK_BACK_SPACE, mod, e -> deleteLine());
    }

    void bind(int key, int mod, ActionListener a) {
        getRootPane().registerKeyboardAction(a,
                KeyStroke.getKeyStroke(key, mod), JComponent.WHEN_IN_FOCUSED_WINDOW);
    }

    JButton btn(String label, ActionListener a) {
        JButton b = new JButton(label); b.addActionListener(a); return b;
    }

    static String extractClassName(String src) {
        Matcher m = Pattern.compile("\\bpublic\\s+class\\s+(\\w+)").matcher(src);
        if (m.find()) return m.group(1);
        m = Pattern.compile("\\bclass\\s+(\\w+)").matcher(src);
        if (m.find()) return m.group(1);
        return "Main";
    }

    void doRun() {
        String currentText = code.getText();
        output.setText("");
        setStatus("Compiling…");
        runBtn.setEnabled(false);

        Thread.ofVirtual().start(() -> {
            Path tmpSrcDir = null, tmpOut = null;
            try {
                String className = extractClassName(currentText);
                tmpSrcDir = Files.createTempDirectory("jedit-src-");
                Path src  = tmpSrcDir.resolve(className + ".java");
                Files.writeString(src, currentText);

                String cp  = classpath(currentText);
                tmpOut = Files.createTempDirectory("jedit-");
                String main = Fixer.compile(src, tmpOut, cp, this::importDialog, this::appendOut);

                String updated = Files.readString(src);
                if (!updated.equals(currentText)) {
                    final String u = updated;
                    SwingUtilities.invokeLater(() -> {
                        int pos = code.getCaretPosition();
                        code.setText(u);
                        code.setCaretPosition(Math.min(pos, u.length()));
                        dirty = true; updateTitle();
                    });
                }

                if (main == null) { setStatus("Compile failed."); return; }
                setStatus("Running " + main + "…");

                Process proc = new ProcessBuilder(JAVA, "-cp",
                        tmpOut + (cp.isEmpty() ? "" : File.pathSeparator + cp), main)
                        .redirectErrorStream(true).start();
                try (var br = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    br.lines().forEach(l -> appendOut(l + "\n"));
                }
                int exit = proc.waitFor();
                setStatus(exit == 0 ? "Done (exit 0)." : "Exited with code " + exit + ".");
            } catch (Exception ex) {
                appendOut("[Error] " + ex.getMessage() + "\n");
                setStatus("Error.");
            } finally {
                for (Path dir : new Path[]{tmpOut, tmpSrcDir}) {
                    if (dir == null) continue;
                    try (var w = Files.walk(dir)) {
                        w.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                    } catch (Exception ignored) {}
                }
                SwingUtilities.invokeLater(() -> runBtn.setEnabled(true));
            }
        });
    }

    void ensureMaven() throws Exception {
        if (Files.exists(Path.of(MVN))) return;
        String url = "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz";
        Path tar = Path.of(JRUN_HOME, "maven.tar.gz");
        Files.createDirectories(Path.of(JRUN_HOME));
        appendOut("Downloading Maven 3.9.11\u2026\n");
        Process dl = new ProcessBuilder("curl", "-fsSL", "-o", tar.toString(), url)
                .redirectErrorStream(true).start();
        try (var br = new BufferedReader(new InputStreamReader(dl.getInputStream()))) {
            br.lines().forEach(l -> appendOut(l + "\n"));
        }
        if (dl.waitFor() != 0) throw new RuntimeException("Maven download failed");
        appendOut("Extracting Maven\u2026\n");
        Process ex = new ProcessBuilder("tar", "-xzf", tar.toString(), "-C", JRUN_HOME)
                .redirectErrorStream(true).start();
        try (var br = new BufferedReader(new InputStreamReader(ex.getInputStream()))) {
            br.lines().forEach(l -> appendOut(l + "\n"));
        }
        if (ex.waitFor() != 0) throw new RuntimeException("Maven extraction failed");
        try (var s = Files.list(Path.of(JRUN_HOME))) {
            s.filter(p -> p.getFileName().toString().startsWith("apache-maven-"))
             .findFirst().ifPresent(p -> {
                 try { Files.move(p, Path.of(JRUN_HOME, "maven")); }
                 catch (IOException e) { throw new UncheckedIOException(e); }
             });
        }
        Files.deleteIfExists(tar);
        Path.of(MVN).toFile().setExecutable(true);
        appendOut("Maven ready.\n");
    }

    String classpath(String src) throws Exception {
        List<String> deps = src.lines()
                .filter(l -> l.stripLeading().startsWith("//DEPS"))
                .map(l -> l.replaceFirst("^\\s*//DEPS\\s*", ""))
                .flatMap(l -> Arrays.stream(l.split("[,\\s]+")))
                .filter(s -> !s.isEmpty() && s.contains(":"))
                .sorted().distinct().toList();
        if (deps.isEmpty()) return "";

        String key = String.join("\n", deps);
        String hex = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(key.getBytes())).substring(0, 16);
        Path cdir = Path.of(JRUN_HOME, "cache", "deps", hex);
        Path cpf  = cdir.resolve("cp.txt");
        if (Files.exists(cpf)) return Files.readString(cpf).trim();
        Files.createDirectories(cdir);

        var pom = new StringBuilder(
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\">" +
                "<modelVersion>4.0.0</modelVersion>" +
                "<groupId>jrun</groupId><artifactId>s</artifactId><version>0</version>" +
                "<dependencies>");
        for (String dep : deps) {
            String[] p = dep.split(":"); if (p.length < 3) throw new IllegalArgumentException("Bad dep: " + dep);
            pom.append("<dependency><groupId>").append(p[0])
               .append("</groupId><artifactId>").append(p[1])
               .append("</artifactId><version>").append(p[2]).append("</version></dependency>");
        }
        pom.append("</dependencies></project>");
        Files.writeString(cdir.resolve("pom.xml"), pom);

        appendOut("Resolving dependencies…\n");
        Process mvn = new ProcessBuilder(MVN, "-q", "-B", "-f",
                cdir.resolve("pom.xml").toString(),
                "dependency:build-classpath", "-Dmdep.outputFile=" + cpf)
                .redirectErrorStream(true).start();
        try (var br = new BufferedReader(new InputStreamReader(mvn.getInputStream()))) {
            br.lines().forEach(l -> appendOut(l + "\n"));
        }
        if (mvn.waitFor() != 0) { Files.deleteIfExists(cpf); throw new RuntimeException("Maven failed"); }
        return Files.readString(cpf).trim();
    }

    String importDialog(String name, List<String> cands) throws Exception {
        if (cands.isEmpty()) { appendOut("No import found for '" + name + "'\n"); return null; }
        if (cands.size() == 1) { appendOut("+ import " + cands.get(0) + "\n"); return cands.get(0); }
        var latch = new CountDownLatch(1);
        var pick  = new String[1];
        SwingUtilities.invokeLater(() -> {
            var list = new JList<>(cands.toArray(new String[0]));
            list.setSelectedIndex(0);
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            int res = JOptionPane.showConfirmDialog(this,
                    new Object[]{"Multiple imports for '" + name + "'. Choose one:", new JScrollPane(list)},
                    "Choose Import", JOptionPane.OK_CANCEL_OPTION);
            if (res == JOptionPane.OK_OPTION) pick[0] = list.getSelectedValue();
            latch.countDown();
        });
        latch.await();
        return pick[0];
    }

    void newFile() {
        file = null; dirty = false;
        code.setText("public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello!\");\n    }\n}\n");
        code.setCaretPosition(0);
        undo.discardAllEdits();
        updateTitle();
    }

    void chooseOpen() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Java files (*.java)", "java"));
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            openFile(fc.getSelectedFile().toPath());
    }

    void openFile(Path p) {
        try {
            code.setText(Files.readString(p));
            code.setCaretPosition(0);
            undo.discardAllEdits();
            file = p; dirty = false; updateTitle();
        } catch (IOException ex) { showErr("Cannot open: " + ex.getMessage()); }
    }

    boolean save() {
        if (file == null) return saveAs();
        return writeToDisk(file);
    }

    boolean saveAs() {
        JFileChooser fc = new JFileChooser();
        fc.setFileFilter(new FileNameExtensionFilter("Java files (*.java)", "java"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return false;
        Path p = fc.getSelectedFile().toPath();
        if (!p.toString().endsWith(".java")) p = p.resolveSibling(p.getFileName() + ".java");
        return writeToDisk(p);
    }

    boolean writeToDisk(Path p) {
        try {
            Files.writeString(p, code.getText());
            file = p; dirty = false; updateTitle(); return true;
        } catch (IOException ex) { showErr("Cannot save: " + ex.getMessage()); return false; }
    }

    void appendOut(String s) {
        SwingUtilities.invokeLater(() -> {
            output.append(s);
            output.setCaretPosition(output.getDocument().getLength());
        });
    }

    void setStatus(String s) { SwingUtilities.invokeLater(() -> status.setText(" " + s)); }
    void markDirty() { dirty = true; updateTitle(); }

    void updateTitle() {
        String t = "jrun — " + (file != null ? file.getFileName() : "Untitled") + (dirty ? " ●" : "");
        SwingUtilities.invokeLater(() -> setTitle(t));
    }

    boolean confirmDiscard() {
        if (!dirty) return true;
        return JOptionPane.showConfirmDialog(this, "Discard unsaved changes?",
                "Unsaved Changes", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
    }

    void toggleComment() {
        String t = code.getText();
        int start = t.lastIndexOf('\n', code.getCaretPosition() - 1) + 1;
        int end   = t.indexOf('\n', code.getCaretPosition());
        if (end < 0) end = t.length();
        String line = t.substring(start, end);
        String replaced = line.stripLeading().startsWith("//")
                ? line.replaceFirst("//\\s?", "")
                : "//" + line;
        code.select(start, end);
        code.replaceSelection(replaced);
        code.setCaretPosition(Math.min(start + replaced.length(), code.getText().length()));
    }

    void duplicateLine() {
        String t = code.getText();
        int pos   = code.getCaretPosition();
        int start = t.lastIndexOf('\n', pos - 1) + 1;
        int end   = t.indexOf('\n', pos);
        if (end < 0) end = t.length();
        String line = t.substring(start, end);
        code.select(end, end);
        code.replaceSelection("\n" + line);
        code.setCaretPosition(end + 1 + line.length());
    }

    void deleteLine() {
        String t = code.getText();
        int pos   = code.getCaretPosition();
        int start = t.lastIndexOf('\n', pos - 1) + 1;
        int end   = t.indexOf('\n', pos);
        if (end < 0) { code.select(start > 0 ? start - 1 : 0, t.length()); }
        else          { code.select(start, end + 1); }
        code.replaceSelection("");
    }

    void maybeQuit() {
        if (confirmDiscard()) {
            try { Files.createDirectories(Path.of(JRUN_HOME)); Files.writeString(Path.of(JRUN_HOME, "autosave.java"), code.getText()); }
            catch (IOException ignored) {}
            System.exit(0);
        }
    }
    void showErr(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }
}
