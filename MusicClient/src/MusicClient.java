// MusicClient.java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javazoom.jl.player.Player;  // for MP3 playback via JLayer

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.AbstractCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellEditor;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class MusicClient extends JFrame {

    private String serverIp;
    private int serverPort;
    private JTable table;
    private SongTableModel tableModel;
    private JTextField filterField;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MusicClient client = new MusicClient();
            client.showConfigDialog();
        });
    }

    public MusicClient() {
        super("Music Client");
        setLayout(new BorderLayout());
        tableModel = new SongTableModel();
        table = new JTable(tableModel);
        // Set a custom cell renderer and editor for the "Play" and "Download" columns.
        // In our table model, columns index 4 and 5 are for Play and Download.
        TableColumnModel colModel = table.getColumnModel();
        colModel.getColumn(4).setCellRenderer(new ButtonRenderer("Play"));
        colModel.getColumn(4).setCellEditor(new ButtonEditor(new JCheckBox(), "Play", this));
        colModel.getColumn(5).setCellRenderer(new ButtonRenderer("Download"));
        colModel.getColumn(5).setCellEditor(new ButtonEditor(new JCheckBox(), "Download", this));

        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.add(new JLabel("Filter (Title/Album/Genre): "));
        filterField = new JTextField(20);
        topPanel.add(filterField);
        JButton filterBtn = new JButton("Filter");
        filterBtn.addActionListener((ActionEvent e) -> filterTable());
        topPanel.add(filterBtn);
        add(topPanel, BorderLayout.NORTH);

        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    // Public accessor for the table so inner classes can access it.
    public JTable getTable() {
        return table;
    }

    private void showConfigDialog() {
        JTextField ipField = new JTextField("127.0.0.1");
        JTextField portField = new JTextField("5555");
        Object[] message = { "Server IP:", ipField, "Server Port:", portField };
        int option = JOptionPane.showConfirmDialog(this, message, "Connect to Music Server", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            serverIp = ipField.getText().trim();
            try {
                serverPort = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "Invalid port number.");
                System.exit(1);
            }
            fetchSongList();
            setVisible(true);
        } else {
            System.exit(0);
        }
    }

    public void fetchSongList() {
        try (Socket socket = new Socket(serverIp, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
        {
            out.println("LIST");
            String json = in.readLine();
            Gson gson = new Gson();
            java.lang.reflect.Type listType = new TypeToken<List<Song>>() {}.getType();
            List<Song> songs = gson.fromJson(json, listType);
            tableModel.setSongs(songs);
            System.out.println("Fetched " + songs.size() + " songs from server.");
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to retrieve song list from server.");
        }
    }

    public void performAction(String action, Song song) {
        System.out.println("Action: " + action + " on song: " + song.getTitle());
        if ("Play".equals(action)) {
            new Thread(() -> playSong(song)).start();
        } else if ("Download".equals(action)) {
            new Thread(() -> downloadSong(song)).start();
        }
    }

    private void playSong(Song song) {
        System.out.println("Play button pressed for song: " + song.getTitle());
        try (Socket socket = new Socket(serverIp, serverPort);
             OutputStream out = socket.getOutputStream();
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream()))
        {
            PrintWriter writer = new PrintWriter(out, true);
            writer.println("STREAM " + song.getId());
            String filePathLower = song.getFilePath().toLowerCase();
            if (filePathLower.endsWith(".mp3")) {
                System.out.println("Playing MP3: " + song.getTitle());
                // Use JLayer to play MP3.
                Player mp3Player = new Player(in);
                mp3Player.play();
                return;
            } else if (filePathLower.endsWith(".aac") ||
                       filePathLower.endsWith(".wma") ||
                       filePathLower.endsWith(".ogg")) {
                // Write stream to a temporary file for JavaFX to play.
                File tempFile = File.createTempFile("tempAudio", filePathLower.substring(filePathLower.lastIndexOf('.')));
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("Launching JavaFX Media Player for: " + song.getTitle());
                new Thread(() -> {
                    try {
                        AudioPlayerApp.launchApp(tempFile.toURI().toString());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            } else {
                System.out.println("Playing native format: " + song.getTitle());
                AudioInputStream audioStream = AudioSystem.getAudioInputStream(in);
                AudioFormat format = audioStream.getFormat();
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                SourceDataLine speaker = (SourceDataLine) AudioSystem.getLine(info);
                speaker.open(format);
                speaker.start();
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = audioStream.read(buffer, 0, buffer.length)) != -1) {
                    speaker.write(buffer, 0, bytesRead);
                }
                speaker.drain();
                speaker.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error playing song: " + song.getTitle());
        }
    }

    private void downloadSong(Song song) {
        JFileChooser fileChooser = new JFileChooser();
        String suggestedName = song.getTitle() + song.getFilePath().substring(song.getFilePath().lastIndexOf('.'));
        fileChooser.setSelectedFile(new File(suggestedName));
        int option = fileChooser.showSaveDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            try (Socket socket = new Socket(serverIp, serverPort);
                 OutputStream out = socket.getOutputStream();
                 BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                 FileOutputStream fos = new FileOutputStream(saveFile))
            {
                PrintWriter writer = new PrintWriter(out, true);
                writer.println("DOWNLOAD " + song.getId());
                byte[] buffer = new byte[4096];
                int bytesRead;
                System.out.println("Downloading: " + song.getTitle());
                while ((bytesRead = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, bytesRead);
                }
                System.out.println("Download finished: " + song.getTitle());
                JOptionPane.showMessageDialog(this, "Downloaded " + song.getTitle());
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error downloading song: " + song.getTitle());
            }
        }
    }

    private void filterTable() {
        String text = filterField.getText();
        TableRowSorter<SongTableModel> sorter = new TableRowSorter<>(tableModel);
        table.setRowSorter(sorter);
        if (text.trim().isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
        }
    }

    // -----------------
    // Inner Classes
    // -----------------

    public static class Song {
        private int id;
        private String title;
        private String album;
        private String genre;
        private String filePath;

        // Getters
        public int getId() { return id; }
        public String getTitle() { return title; }
        public String getAlbum() { return album; }
        public String getGenre() { return genre; }
        public String getFilePath() { return filePath; }
    }

    public static class SongTableModel extends AbstractTableModel {
        private String[] columnNames = { "ID", "Title", "Album", "Genre", "Play", "Download" };
        private List<Song> songs = new ArrayList<>();

        public void setSongs(List<Song> songs) {
            this.songs = songs;
            fireTableDataChanged();
        }

        public Song getSongAt(int row) {
            return songs.get(row);
        }

        @Override
        public int getRowCount() {
            return songs.size();
        }

        @Override
        public int getColumnCount() {
            return columnNames.length;
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            Song song = songs.get(rowIndex);
            switch (columnIndex) {
                case 0: return song.getId();
                case 1: return song.getTitle();
                case 2: return song.getAlbum();
                case 3: return song.getGenre();
                // For the button columns, just return an empty String.
                case 4: return "";
                case 5: return "";
                default: return "";
            }
        }

        @Override
        public String getColumnName(int col) {
            return columnNames[col];
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            // Only the "Play" (4) and "Download" (5) columns are editable.
            return col == 4 || col == 5;
        }
    }

    public static class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer(String text) {
            setText(text);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus,
                                                       int row, int column) {
            return this;
        }
    }

    public static class ButtonEditor extends AbstractCellEditor implements TableCellEditor {
        protected JButton button;
        private String action;
        private MusicClient client;
        private int currentRow;

        public ButtonEditor(JCheckBox checkBox, String action, MusicClient client) {
            this.action = action;
            this.client = client;
            button = new JButton(action);
            button.addMouseListener(new MouseAdapter() {
                public void mousePressed(MouseEvent e) {
                    fireEditingStopped();
                }
            });
            button.addActionListener(e -> {
                int modelRow = client.getTable().convertRowIndexToModel(currentRow);
                Song song = ((SongTableModel) client.getTable().getModel()).getSongAt(modelRow);
                System.out.println("Button '" + action + "' clicked for song: " + song.getTitle());
                client.performAction(action, song);
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable table, Object value,
                                                     boolean isSelected, int row, int column) {
            currentRow = row;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return action;
        }
    }
}
