import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.text.BadLocationException;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.undo.*;
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
    static final boolean MAC = System.getProperty("os.name").startsWith("Mac");

    static final Color CANVAS       = Color.WHITE;
    static final Color CHROME       = new Color(0xF5F5F7);
    static final Color CONSOLE      = new Color(0xFBFBFD);
    static final Color HAIRLINE     = new Color(0xD2D2D7);
    static final Color LABEL        = new Color(0x1D1D1F);
    static final Color SECONDARY    = new Color(0x86868B);
    static final Color TERTIARY     = new Color(0xB8B8BD);
    static final Color ACCENT       = new Color(0x007AFF);
    static final Color SELECTION    = new Color(0xB3D7FF);
    static final Color CURRENT_LINE = new Color(0xF4F7FB);
    static final Color ERROR        = new Color(0xFF3B30);
    static final String MONO = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames()).contains("SF Mono") ? "SF Mono" : MAC ? "Menlo" : Font.MONOSPACED;

    private final JTextArea code = new JTextArea() {
        @Override protected void paintComponent(Graphics g) {
            g.setColor(getBackground());
            g.fillRect(0, 0, getWidth(), getHeight());
            try {
                var line = modelToView2D(getCaretPosition());
                g.setColor(CURRENT_LINE);
                g.fillRect(0, (int) line.getY(), getWidth(), (int) line.getHeight());
            } catch (BadLocationException ignored) {}
            super.paintComponent(g);
        }
    };
    private final JTextArea output = new JTextArea();
    private final JLabel    status = new JLabel("Ready");
    private final JButton       runBtn = new ToolButton("▶  Run", true, e -> doRun());
    private final UndoManager   undo   = new UndoManager();
    private Path    file;
    private boolean dirty;

    private final Imports imports = new Imports();
    private final javax.swing.Timer importTimer = new javax.swing.Timer(400, e -> checkImports());
    private final Highlighter.HighlightPainter unresolvedPaint = Editor::paintSquiggle;
    private final List<Object> unresolvedTags = new ArrayList<>();
    private List<Imports.Ref> refs = List.of();
    private Popup hint;

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "JEdit");
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameAqua");
        Path p = args.length > 0 ? Path.of(args[0]) : null;
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new Editor(p).setVisible(true);
        });
    }

    Editor(Path p) {
        super("JEdit");
        getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
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
        Path autosave = Path.of(JRUN_HOME, "autosave.java");
        if (p != null) {
            openFile(p);
        } else if (Files.exists(autosave)) {
            try { code.setText(Files.readString(autosave)); Files.deleteIfExists(autosave); dirty = true; updateTitle(); }
            catch (IOException ignored) { newFile(); }
        } else {
            newFile();
        }
        setSize(980, 720);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(null);
    }

    void buildUI() {
        code.setFont(new Font(MONO, Font.PLAIN, 13));
        code.setTabSize(4);
        code.setOpaque(false);
        code.setBackground(CANVAS);
        code.setForeground(LABEL);
        code.setCaretColor(LABEL);
        code.setSelectionColor(SELECTION);
        code.setSelectedTextColor(LABEL);
        code.setMargin(new Insets(12, 6, 12, 16));
        code.addCaretListener(e -> code.repaint());
        code.getDocument().addUndoableEditListener(undo);
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

        output.setFont(new Font(MONO, Font.PLAIN, 12));
        output.setEditable(false);
        output.setBackground(CONSOLE);
        output.setForeground(LABEL);
        output.setCaretColor(CONSOLE);
        output.setSelectionColor(SELECTION);
        output.setMargin(new Insets(10, 16, 10, 16));

        JScrollPane codeScroll = flat(new JScrollPane(code));
        codeScroll.setRowHeaderView(new Gutter());
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codeScroll, flat(new JScrollPane(output)));
        split.setResizeWeight(0.72);
        split.setBorder(null);
        split.setDividerSize(1);
        split.setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics g) { g.setColor(HAIRLINE); g.fillRect(0, 0, getWidth(), getHeight()); }
                };
            }
        });

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.setOpaque(false);
        left.add(tool("New", null, e -> { if (confirmDiscard()) newFile(); }));
        left.add(tool("Open…", null, e -> { if (confirmDiscard()) chooseOpen(); }));
        left.add(tool("Save", "⌘S", e -> save()));
        runBtn.setToolTipText("Run  ⌘R");

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(CHROME);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(MAC ? 11 : 8, MAC ? 78 : 10, 9, 12)));
        bar.add(left, BorderLayout.WEST);
        bar.add(runBtn, BorderLayout.EAST);

        status.setFont(status.getFont().deriveFont(11f));
        status.setForeground(SECONDARY);
        status.setOpaque(true);
        status.setBackground(CHROME);
        status.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, HAIRLINE),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
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

    JButton tool(String label, String shortcut, ActionListener a) {
        JButton b = new ToolButton(label, false, a);
        if (shortcut != null) b.setToolTipText(label + "  " + shortcut);
        return b;
    }

    static JScrollPane flat(JScrollPane sp) {
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.setViewportBorder(null);
        sp.getViewport().setBackground(sp.getViewport().getView().getBackground());
        return sp;
    }

    static void paintSquiggle(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
        try {
            var a = c.modelToView2D(p0);
            var b = c.modelToView2D(p1);
            int y = (int) a.getMaxY() - 2;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ERROR);
            for (int x = (int) a.getX(); x < (int) b.getX(); x += 4) {
                g2.drawLine(x, y, x + 2, y - 2);
                g2.drawLine(x + 2, y - 2, x + 4, y);
            }
            g2.dispose();
        } catch (BadLocationException ignored) {}
    }

    static final class ToolButton extends JButton {
        private final boolean primary;

        ToolButton(String label, boolean primary, ActionListener a) {
            super(label);
            this.primary = primary;
            addActionListener(a);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setForeground(primary ? Color.WHITE : LABEL);
            setFont(getFont().deriveFont(primary ? Font.BOLD : Font.PLAIN, 13f));
            setBorder(BorderFactory.createEmptyBorder(5, primary ? 14 : 10, 5, primary ? 14 : 10));
        }

        @Override protected void paintComponent(Graphics g) {
            ButtonModel m = getModel();
            Color fill = primary
                    ? !isEnabled() ? new Color(0x99C7FF) : m.isPressed() ? new Color(0x0062CC) : m.isRollover() ? new Color(0x0A84FF) : ACCENT
                    : m.isPressed() ? new Color(0xDCDCE1) : m.isRollover() ? new Color(0xE8E8ED) : null;
            if (fill != null) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(fill);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    final class Gutter extends JComponent {
        Gutter() {
            setFont(new Font(MONO, Font.PLAIN, 11));
            code.getDocument().addDocumentListener(new DocumentListener() {
                public void insertUpdate(DocumentEvent e) { revalidate(); repaint(); }
                public void removeUpdate(DocumentEvent e) { revalidate(); repaint(); }
                public void changedUpdate(DocumentEvent e) {}
            });
            code.addCaretListener(e -> repaint());
        }

        @Override public Dimension getPreferredSize() {
            int digits = Math.max(3, String.valueOf(code.getLineCount()).length());
            return new Dimension(getFontMetrics(getFont()).charWidth('0') * digits + 22, code.getPreferredSize().height);
        }

        @Override protected void paintComponent(Graphics g) {
            Rectangle clip = g.getClipBounds();
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(CANVAS);
            g2.fill(clip);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int ascent = code.getFontMetrics(code.getFont()).getAscent();
            try {
                int caretLine = code.getLineOfOffset(code.getCaretPosition());
                int first = code.getLineOfOffset(code.viewToModel2D(new Point(0, clip.y)));
                int last  = code.getLineOfOffset(code.viewToModel2D(new Point(0, clip.y + clip.height)));
                for (int i = first; i <= last; i++) {
                    var line = code.modelToView2D(code.getLineStartOffset(i));
                    String n = String.valueOf(i + 1);
                    g2.setColor(i == caretLine ? SECONDARY : TERTIARY);
                    g2.drawString(n, getWidth() - 12 - fm.stringWidth(n), (int) line.getY() + ascent);
                }
            } catch (BadLocationException ignored) {}
        }
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

    void ensureMaven() throws Exception {
        if (Files.exists(Path.of(MVN))) return;
        String url = "https://archive.apache.org/dist/maven/maven-3/3.9.11/binaries/apache-maven-3.9.11-bin.tar.gz";
        Path tar = Path.of(JRUN_HOME, "maven.tar.gz");
        Files.createDirectories(Path.of(JRUN_HOME));
        appendOut("Downloading Maven 3.9.11…\n");
        Process dl = new ProcessBuilder("curl", "-fsSL", "-o", tar.toString(), url)
                .redirectErrorStream(true).start();
        try (var br = new BufferedReader(new InputStreamReader(dl.getInputStream()))) {
            br.lines().forEach(l -> appendOut(l + "\n"));
        }
        if (dl.waitFor() != 0) throw new RuntimeException("Maven download failed");
        appendOut("Extracting Maven…\n");
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

        ensureMaven();
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
        String text = "<html>" + String.join("<span style='color:#B8B8BD'>&nbsp;&nbsp;|&nbsp;&nbsp;</span>", shown)
                + (r.cands().size() > 3 ? "<span style='color:#86868B'>&nbsp;&nbsp;+" + (r.cands().size() - 3) + "</span>" : "")
                + "<span style='color:#86868B'>&nbsp;&nbsp;&nbsp;&nbsp;⌥↵ import</span></html>";
        JLabel label = new JLabel(text);
        label.setFont(new Font(MONO, Font.PLAIN, 12));
        label.setForeground(LABEL);
        label.setOpaque(true);
        label.setBackground(CANVAS);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(HAIRLINE),
                BorderFactory.createEmptyBorder(5, 9, 5, 9)));
        try {
            var at = code.modelToView2D(r.start());
            Point p = new Point((int) at.getX() - 9, (int) at.getMaxY() + 4);
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
        String t = file != null ? file.getFileName().toString() : "Untitled";
        boolean edited = dirty;
        SwingUtilities.invokeLater(() -> {
            setTitle(t);
            getRootPane().putClientProperty("Window.documentModified", edited);
        });
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
