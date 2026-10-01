import javax.swing.*;
import javax.swing.event.*;
import javax.swing.filechooser.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

public class Editor extends JFrame {
    static final String JRUN_HOME = System.getenv().getOrDefault("JRUN_HOME",
            System.getProperty("user.home") + "/.jrun");
    static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    private final JTextArea code   = new JTextArea();
    private final JTextArea output = new JTextArea();
    private final JLabel    status = new JLabel(" Ready");
    private final JButton   runBtn = new JButton("\u25b6 Run");
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
        if (p != null) openFile(p); else newFile();
        setSize(940, 700);
        setLocationRelativeTo(null);
    }

    void buildUI() {
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        code.setTabSize(4);
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
        bar.add(btn("Open\u2026", e -> { if (confirmDiscard()) chooseOpen(); }));
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
        setStatus("Compiling\u2026");
        runBtn.setEnabled(false);

        Thread.ofVirtual().start(() -> {
            Path tmpSrcDir = null, tmpOut = null;
            try {
                String className = extractClassName(currentText);
                tmpSrcDir = Files.createTempDirectory("jedit-src-");
                Path src  = tmpSrcDir.resolve(className + ".java");
                Files.writeString(src, currentText);

                tmpOut = Files.createTempDirectory("jedit-");
                String main = Fixer.compile(src, tmpOut, "", this::appendOut);
                if (main == null) { setStatus("Compile failed."); return; }
                setStatus("Running " + main + "\u2026");

                Process proc = new ProcessBuilder(JAVA, "-cp", tmpOut.toString(), main)
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
        String t = "jrun \u2014 " + (file != null ? file.getFileName() : "Untitled") + (dirty ? " \u25cf" : "");
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
