import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.regex.*;

public class Editor extends JFrame {
    static final String JRUN_HOME = System.getenv().getOrDefault("JRUN_HOME",
            System.getProperty("user.home") + "/.jrun");
    static final String MVN  = JRUN_HOME + "/maven/bin/mvn";
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private final JTextArea code   = new JTextArea();
    private final JTextArea output = new JTextArea();
    private final JLabel    status = new JLabel(" Ready");
    private final JButton   runBtn = new JButton("▶ Run");
    private Path    file;
    private boolean dirty;

    private final Imports imports = new Imports();
    private final javax.swing.Timer importTimer = new javax.swing.Timer(400, e -> checkImports());
    private final Highlighter.HighlightPainter unresolvedPaint =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 214, 214));
    private final List<Object> unresolvedTags = new ArrayList<>();
    private List<Imports.Ref> refs = List.of();
    private Popup hint;

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
        importTimer.setRepeats(false);
        Thread.ofVirtual().start(() -> {
            try { imports.load(""); } catch (IOException ignored) {}
            SwingUtilities.invokeLater(this::checkImports);
        });
        if (p != null) openFile(p); else newFile();
        setSize(940, 700);
        setLocationRelativeTo(null);
    }

    void buildUI() {
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        code.setTabSize(4);
        code.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { markDirty(); scheduleImportCheck(); }
            public void removeUpdate(DocumentEvent e) { markDirty(); scheduleImportCheck(); }
            public void changedUpdate(DocumentEvent e) {}
        });
        code.addCaretListener(e -> scheduleImportCheck());
        code.addFocusListener(new FocusAdapter() {
            public void focusLost(FocusEvent e) { hideHint(); }
        });
        code.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.ALT_DOWN_MASK), "import");
        code.getActionMap().put("import", new AbstractAction() {
            public void actionPerformed(ActionEvent e) { showImportPopup(); }
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
                Thread.ofVirtual().start(() -> {
                    try { imports.load(cp); } catch (IOException ignored) {}
                    SwingUtilities.invokeLater(this::checkImports);
                });
                String main = Fixer.compile(src, tmpOut, cp, this::appendOut);
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

    void scheduleImportCheck() {
        hideHint();
        importTimer.restart();
    }

    void checkImports() {
        refs = imports.unresolved(code.getText());
        Highlighter h = code.getHighlighter();
        unresolvedTags.forEach(h::removeHighlight);
        unresolvedTags.clear();
        for (var r : refs) {
            try { unresolvedTags.add(h.addHighlight(r.start(), r.start() + r.name().length(), unresolvedPaint)); }
            catch (BadLocationException ignored) {}
        }
        int caret = code.getCaretPosition();
        refs.stream().filter(r -> touches(r, caret)).findFirst().ifPresent(this::showHint);
    }

    void showHint(Imports.Ref r) {
        if (!code.isShowing()) return;
        List<String> shown = r.cands().subList(0, Math.min(3, r.cands().size()));
        String text = String.join("  |  ", shown) + (r.cands().size() > 3 ? "  |  …" : "") + "    ⌥↵";
        JLabel label = new JLabel(text);
        label.setFont(code.getFont().deriveFont(12f));
        label.setOpaque(true);
        label.setBackground(new Color(255, 255, 225));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(190, 190, 190)),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        try {
            var at = code.modelToView2D(r.start());
            Point p = new Point((int) at.getX(), (int) at.getMaxY() + 2);
            SwingUtilities.convertPointToScreen(p, code);
            hint = PopupFactory.getSharedInstance().getPopup(code, label, p.x, p.y);
            hint.show();
        } catch (BadLocationException ignored) {}
    }

    void hideHint() {
        if (hint != null) { hint.hide(); hint = null; }
    }

    boolean touches(Imports.Ref r, int pos) {
        return pos >= r.start() && pos <= r.start() + r.name().length();
    }

    void addImport(String fq) {
        String src = code.getText();
        if (Pattern.compile("^\\s*import\\s+" + Pattern.quote(fq) + "\\s*;", Pattern.MULTILINE).matcher(src).find()) return;
        try { code.getDocument().insertString(Imports.insertAt(src), Imports.importLine(src, fq), null); }
        catch (BadLocationException ignored) {}
    }

    void showImportPopup() {
        hideHint();
        refs = imports.unresolved(code.getText());
        int caret = code.getCaretPosition();
        Imports.Ref r = refs.stream().filter(x -> touches(x, caret)).findFirst()
                .orElse(refs.isEmpty() ? null : refs.get(0));
        if (r == null) { setStatus("Nothing to import."); return; }
        JPopupMenu menu = new JPopupMenu();
        for (String fq : r.cands().subList(0, Math.min(10, r.cands().size()))) {
            JMenuItem item = new JMenuItem(fq);
            item.addActionListener(e -> { addImport(fq); checkImports(); });
            menu.add(item);
        }
        try {
            var at = code.modelToView2D(r.start());
            menu.show(code, (int) at.getX(), (int) at.getMaxY());
            MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[]{menu, (MenuElement) menu.getComponent(0)});
        } catch (BadLocationException ignored) {}
    }

    void newFile() {
        file = null; dirty = false;
        code.setText("public class Main {\n    public static void main(String[] args) {\n        System.out.println(\"Hello!\");\n    }\n}\n");
        code.setCaretPosition(0);
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

    void maybeQuit() { if (confirmDiscard()) System.exit(0); }
    void showErr(String msg) { JOptionPane.showMessageDialog(this, msg, "Error", JOptionPane.ERROR_MESSAGE); }
}
