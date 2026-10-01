import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public class Editor extends JFrame {

    private final JTextArea code   = new JTextArea();
    private final JTextArea output = new JTextArea();
    private final JLabel    status = new JLabel(" Ready");
    private final JButton   runBtn = new JButton("▶ Run");

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "jrun");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) {}
            new Editor().setVisible(true);
        });
    }

    Editor() {
        super("jrun");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        buildUI();
        setSize(940, 700);
        setLocationRelativeTo(null);
    }

    void buildUI() {
        code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));
        code.setTabSize(4);

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
        bar.add(new JButton("New"));
        bar.add(new JButton("Open…"));
        bar.add(new JButton("Save"));
        bar.addSeparator();
        runBtn.setFont(runBtn.getFont().deriveFont(Font.BOLD));
        bar.add(runBtn);

        status.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 0));
        add(bar,    BorderLayout.NORTH);
        add(split,  BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }
}
