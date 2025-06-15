// MusicClient.java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javazoom.jl.player.Player;  // For MP3 playback via JLayer

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.swing.JButton;
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
import javax.swing.event.MouseInputAdapter;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
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
            System.out.println("[Client] Starting MusicClient GUI...");
            MusicClient client = new MusicClient();
            client.showConfigDialog();
        });
    }

    public MusicClient() {
        super("Music Client");
        setLayout(new BorderLayout());
        tableModel = new SongTableModel();
        table = new JTable(tableModel);
        
        // Set preferred column widths:
        TableColumnModel colModel = table.getColumnModel();
        colModel.getColumn(0).setPreferredWidth(30);    // ID
        colModel.getColumn(1).setPreferredWidth(150);   // Title
        colModel.getColumn(2).setPreferredWidth(100);   // Artist
        colModel.getColumn(3).setPreferredWidth(100);   // Album
        colModel.getColumn(4).setPreferredWidth(100);   // Album Artist
        colModel.getColumn(5).setPreferredWidth(80);    // Genre
        colModel.getColumn(6).setPreferredWidth(50);    // Year
        colModel.getColumn(7).setPreferredWidth(60);    // Track Length
        colModel.getColumn(8).setPreferredWidth(100);   // Producers
        colModel.getColumn(9).setPreferredWidth(100);   // Publisher
        colModel.getColumn(10).setPreferredWidth(150);  // File Name
        colModel.getColumn(11).setPreferredWidth(80);   // Album Image
        colModel.getColumn(12).setPreferredWidth(60);   // Play
        colModel.getColumn(13).setPreferredWidth(80);   // Download

        // For the Play and Download columns, set a renderer that resembles a clickable button.
        colModel.getColumn(12).setCellRenderer(new ButtonCellRenderer("Play"));
        colModel.getColumn(13).setCellRenderer(new ButtonCellRenderer("Download"));
        
        // Add a MouseListener to detect clicks on the "Play" and "Download" columns.
        table.addMouseListener(new MouseInputAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Point p = e.getPoint();
                int row = table.rowAtPoint(p);
                int col = table.columnAtPoint(p);
                if (row == -1 || col == -1) return;
                int modelCol = table.convertColumnIndexToModel(col);
                // Check for Play button (column index 12) or Download button (column index 13)
                if (modelCol == 12) {
                    Song song = tableModel.getSongAt(table.convertRowIndexToModel(row));
                    System.out.println("[Client] 'Play' clicked for song: " + song.getTitle());
                    performAction("Play", song);
                } else if (modelCol == 13) {
                    Song song = tableModel.getSongAt(table.convertRowIndexToModel(row));
                    System.out.println("[Client] 'Download' clicked for song: " + song.getTitle());
                    performAction("Download", song);
                }
            }
            @Override
            public void mouseEntered(MouseEvent e) {
                Point p = e.getPoint();
                int col = table.columnAtPoint(p);
                int modelCol = table.convertColumnIndexToModel(col);
                if (modelCol == 12 || modelCol == 13) {
                    table.setCursor(new Cursor(Cursor.HAND_CURSOR));
                } else {
                    table.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
                }
            }
            @Override
            public void mouseExited(MouseEvent e) {
                table.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
            }
        });
        
        add(new JScrollPane(table), BorderLayout.CENTER);
        
        // Create filter panel.
        JPanel topPanel = new JPanel(new BorderLayout());
        JPanel filterPanel = new JPanel();
        filterPanel.add(new JLabel("Filter (Title/Album/Genre): "));
        filterField = new JTextField(20);
        filterPanel.add(filterField);
        JButton filterBtn = new JButton("Filter");
        filterBtn.addActionListener((ActionEvent e) -> filterTable());
        filterPanel.add(filterBtn);
        topPanel.add(filterPanel, BorderLayout.NORTH);
        add(topPanel, BorderLayout.NORTH);
        
        setSize(1200, 600); // Increase width to accommodate more columns
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        System.out.println("[Client] GUI constructed.");
    }
    
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
            System.out.println("[Client] Connecting to server at " + serverIp + ":" + serverPort);
            fetchSongList();
            setVisible(true);
        } else {
            System.out.println("[Client] User cancelled configuration. Exiting.");
            System.exit(0);
        }
    }
    
    public void fetchSongList() {
        try (Socket socket = new Socket(serverIp, serverPort);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream())))
        {
            System.out.println("[Client] Requesting song list from server...");
            out.println("LIST");
            String json = in.readLine();
            Gson gson = new Gson();
            java.lang.reflect.Type listType = new TypeToken<List<Song>>() {}.getType();
            List<Song> songs = gson.fromJson(json, listType);
            tableModel.setSongs(songs);
            System.out.println("[Client] Fetched " + songs.size() + " songs from server.");
        } catch (Exception e) {
            System.err.println("[Client] Error fetching song list:");
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to retrieve song list from server.");
        }
    }
    
    public void performAction(String action, Song song) {
        System.out.println("[Client] Action: " + action + " on song: " + song.getTitle());
        if ("Play".equals(action)) {
            new Thread(() -> playSong(song)).start();
        } else if ("Download".equals(action)) {
            new Thread(() -> downloadSong(song)).start();
        }
    }
    
    private void playSong(Song song) {
        System.out.println("[Client] Play activated for song: " + song.getTitle());
        try (Socket socket = new Socket(serverIp, serverPort);
             OutputStream out = socket.getOutputStream();
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream()))
        {
            PrintWriter writer = new PrintWriter(out, true);
            writer.println("STREAM " + song.getId());
            String filePathLower = song.getFilePath().toLowerCase();
            if (filePathLower.endsWith(".mp3")) {
                System.out.println("[Client] Playing MP3: " + song.getTitle());
                Player mp3Player = new Player(in);
                mp3Player.play();
                return;
            } else if (filePathLower.endsWith(".aac") || filePathLower.endsWith(".ogg")) {
                // Write stream to temporary file and launch JavaFX player.
                File tempFile = File.createTempFile("tempAudio", filePathLower.substring(filePathLower.lastIndexOf('.')));
                try (FileOutputStream fos = new FileOutputStream(tempFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        fos.write(buffer, 0, bytesRead);
                    }
                }
                System.out.println("[Client] Temporary file created at: " + tempFile.getAbsolutePath() +
                                   " (size: " + tempFile.length() + " bytes)");
                if (tempFile.length() < 1024) {
                    JOptionPane.showMessageDialog(this, "The downloaded media file appears to be too small.");
                    return;
                }
                System.out.println("[Client] Launching JavaFX Media Player for: " + song.getTitle());
                new Thread(() -> {
                    try {
                        AudioPlayerApp.launchApp(tempFile.toURI().toString());
                    } catch (Exception ex) {
                        ex.printStackTrace();
                    }
                }).start();
                return;
            } else if (filePathLower.endsWith(".wma")){
                System.out.println("[Client] WMA playback is not supported.");
                JOptionPane.showMessageDialog(this, "Playback for WMA files is not supported.");
                return;
            } else {
                System.out.println("[Client] Playing native format: " + song.getTitle());
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
            System.err.println("[Client] Error playing song " + song.getTitle() + ":");
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
            System.out.println("[Client] Initiating download for: " + song.getTitle());
            try (Socket socket = new Socket(serverIp, serverPort);
                 OutputStream out = socket.getOutputStream();
                 BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                 FileOutputStream fos = new FileOutputStream(saveFile))
            {
                PrintWriter writer = new PrintWriter(out, true);
                writer.println("DOWNLOAD " + song.getId());
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, bytesRead);
                }
                System.out.println("[Client] Download completed for: " + song.getTitle());
                JOptionPane.showMessageDialog(this, "Downloaded " + song.getTitle());
            } catch (Exception e) {
                System.err.println("[Client] Error downloading song " + song.getTitle() + ":");
                e.printStackTrace();
                JOptionPane.showMessageDialog(this, "Error downloading song: " + song.getTitle());
            }
        } else {
            System.out.println("[Client] Download cancelled by user.");
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
    
    // ------------- Inner Classes --------------
    
    public static class Song {
        private int id;
        private String title;
        private String album;
        private String genre;
        private String filePath;
        // Extra metadata fields.
        private String artist;
        private String albumArtist;
        private String year;
        private int trackLength;
        private String producers;
        private String publisher;
        private String fileName;
        private String albumImageBase64;
        
        public int getId() { return id; }
        public String getTitle() { return title; }
        public String getAlbum() { return album; }
        public String getGenre() { return genre; }
        public String getFilePath() { return filePath; }
        public String getArtist() { return artist; }
        public String getAlbumArtist() { return albumArtist; }
        public String getYear() { return year; }
        public int getTrackLength() { return trackLength; }
        public String getProducers() { return producers; }
        public String getPublisher() { return publisher; }
        public String getFileName() { return fileName; }
        public String getAlbumImageBase64() { return albumImageBase64; }
    }
    
    public static class SongTableModel extends AbstractTableModel {
        // 14 columns are defined.
        private String[] columnNames = { 
            "ID", "Title", "Artist", "Album", "Album Artist", "Genre", "Year", "Length", 
            "Producers", "Publisher", "File Name", "Album Image", "Play", "Download" 
        };
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
                case 2: return song.getArtist();
                case 3: return song.getAlbum();
                case 4: return song.getAlbumArtist();
                case 5: return song.getGenre();
                case 6: return song.getYear();
                case 7: return song.getTrackLength();
                case 8: return song.getProducers();
                case 9: return song.getPublisher();
                case 10: return song.getFileName();
                case 11: return song.getAlbumImageBase64() == null || song.getAlbumImageBase64().isEmpty() ? "No" : "Yes";
                case 12: return "Play";
                case 13: return "Download";
                default: return "";
            }
        }
        
        @Override
        public String getColumnName(int col) {
            return columnNames[col];
        }
        
        @Override
        public boolean isCellEditable(int row, int col) {
            return false;  // Use mouse listener for actions.
        }
    }
    
    // Renderer to mimic a clickable button appearance.
    public static class ButtonCellRenderer extends JButton implements javax.swing.table.TableCellRenderer {
        public ButtonCellRenderer(String label) {
            setText(label);
            setOpaque(true);
            setForeground(Color.BLUE.darker());
            setCursor(new Cursor(Cursor.HAND_CURSOR));
            setFont(new Font("SansSerif", Font.BOLD, 12));
        }
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                       boolean hasFocus, int row, int column) {
            return this;
        }
    }
}
