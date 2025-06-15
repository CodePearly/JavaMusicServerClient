// MusicClient.java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javazoom.jl.player.Player; // For MP3 playback via JLayer

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.DefaultCellEditor;
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
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
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
import java.util.List;

public class MusicClient extends JFrame {

    private String serverIp;
    private int serverPort;
    JTable table;
    private SongTableModel tableModel;
    private JTextField filterField;

    public static void main(String[] args) {
        // Start the GUI on the Event Dispatch Thread.
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

        // Add extra columns for "Play" and "Download" actions.
        TableColumn playCol = new TableColumn();
        playCol.setHeaderValue("Play");
        table.addColumn(playCol);

        TableColumn downloadCol = new TableColumn();
        downloadCol.setHeaderValue("Download");
        table.addColumn(downloadCol);

        // Set custom renderers and editors for the action buttons.
        table.getColumnModel().getColumn(table.getColumnCount() - 2)
                .setCellRenderer(new ButtonRenderer("Play"));
        table.getColumnModel().getColumn(table.getColumnCount() - 2)
                .setCellEditor(new ButtonEditor(new JCheckBox(), "Play", this));

        table.getColumnModel().getColumn(table.getColumnCount() - 1)
                .setCellRenderer(new ButtonRenderer("Download"));
        table.getColumnModel().getColumn(table.getColumnCount() - 1)
                .setCellEditor(new ButtonEditor(new JCheckBox(), "Download", this));

        add(new JScrollPane(table), BorderLayout.CENTER);

        JPanel topPanel = new JPanel(new FlowLayout());
        topPanel.add(new JLabel("Filter (Title/Album/Genre): "));
        filterField = new JTextField(20);
        topPanel.add(filterField);
        JButton filterBtn = new JButton("Filter");
        filterBtn.addActionListener(e -> filterTable());
        topPanel.add(filterBtn);
        add(topPanel, BorderLayout.NORTH);

        setSize(800, 600);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
    }

    private void showConfigDialog() {
        JTextField ipField = new JTextField("127.0.0.1");
        JTextField portField = new JTextField("5555");
        Object[] message = { "Server IP:", ipField, "Server Port:", portField };
        int option = JOptionPane.showConfirmDialog(null, message, "Connect to Music Server", JOptionPane.OK_CANCEL_OPTION);
        if (option == JOptionPane.OK_OPTION) {
            serverIp = ipField.getText();
            serverPort = Integer.parseInt(portField.getText());
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
        try (Socket socket = new Socket(serverIp, serverPort);
             OutputStream out = socket.getOutputStream();
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream()))
        {
            PrintWriter writer = new PrintWriter(out, true);
            writer.println("STREAM " + song.getId());

            String filePathLower = song.getFilePath().toLowerCase();
            // MP3 playback via JLayer.
            if (filePathLower.endsWith(".mp3")) {
                try {
                    System.out.println("Playing MP3: " + song.getTitle());
                    Player mp3Player = new Player(in);
                    mp3Player.play();
                } catch (Exception e) {
                    e.printStackTrace();
                    JOptionPane.showMessageDialog(this, "Error playing MP3: " + song.getTitle());
                }
                return;
            }
            // For AAC, WMA, and OGG, use JavaFX-based playback.
            else if (filePathLower.endsWith(".aac") ||
                     filePathLower.endsWith(".wma") ||
                     filePathLower.endsWith(".ogg")) {
                // Write the stream to a temporary file.
                File tempFile = File.createTempFile("tempAudio", filePathLower.substring(filePathLower.lastIndexOf('.')));
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("Playing external format (" + filePathLower + "): " + song.getTitle());
                // Launch the JavaFX media player to play the temporary file.
                // Note: The AudioPlayerApp class must exist in your project.
                new Thread(() -> {
                    try {
                        AudioPlayerApp.launchApp(tempFile.toURI().toString());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            }
            // Otherwise, use native Java Sound API (supporting WAV, AIFF, FLAC).
            else {
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
}

//
// Supporting Classes
//

class Song implements java.io.Serializable {
    private int id;
    private String title;
    private String album;
    private String genre;
    private String filePath;

    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getAlbum() { return album; }
    public String getGenre() { return genre; }
    public String getFilePath() { return filePath; }
}

class SongTableModel extends AbstractTableModel {
    private String[] columnNames = { "ID", "Title", "Album", "Genre" };
    private List<Song> songs = new java.util.ArrayList<>();

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
            default: return "";
        }
    }

    @Override
    public String getColumnName(int col) {
        return columnNames[col];
    }
}

class ButtonRenderer extends JButton implements TableCellRenderer {
    public ButtonRenderer(String text) {
        setText(text);
    }
    @Override
    public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                                                            boolean isSelected, boolean hasFocus,
                                                            int row, int column) {
        return this;
    }
}

class ButtonEditor extends DefaultCellEditor {
    protected JButton button;
    private String action;
    private MusicClient client;
    private int currentRow;

    public ButtonEditor(JCheckBox checkBox, String action, MusicClient client) {
        super(checkBox);
        this.action = action;
        this.client = client;
        button = new JButton(action);
        button.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                fireEditingStopped();
            }
        });
        button.addActionListener(e -> {
            int modelRow = client.table.convertRowIndexToModel(currentRow);
            Song song = ((SongTableModel) client.table.getModel()).getSongAt(modelRow);
            client.performAction(action, song);
        });
    }

    @Override
    public java.awt.Component getTableCellEditorComponent(JTable table, Object value,
                                                            boolean isSelected, int row, int column) {
        this.currentRow = row;
        return button;
    }

    @Override
    public Object getCellEditorValue() {
        return action;
    }
}
