#!/bin/bash
set -e

echo "=== Creating project directories ==="
# Create directories for MusicServer and MusicClient
mkdir -p MusicServer/src MusicServer/lib
mkdir -p MusicClient/src MusicClient/lib

echo "=== Creating MusicServer source file ==="
cat << 'EOF' > MusicServer/src/MusicServer.java
// MusicServer.java
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.FieldKey;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicServer {

    // Store a mapping from a unique ID to Song objects.
    private Map<Integer, Song> songDatabase = new HashMap<>();
    private int songIdCounter = 0;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int PORT = 5555; // or choose a port

    public static void main(String[] args) {
        MusicServer server = new MusicServer();
        server.start();
    }

    public void start() {
        // First, open a GUI to choose folders
        List<File> chosenDirs = chooseMusicDirectories();
        if (chosenDirs.isEmpty()) {
            JOptionPane.showMessageDialog(null, "No folders selected. Exiting.");
            System.exit(0);
        }
        // Index folders for music files
        indexDirectories(chosenDirs);

        // Save indexed list to JSON file (indexed_music.json)
        saveDatabaseToJson();

        // Start the server socket (in its own thread)
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            System.out.println("Music server started on port " + PORT);
            while (true) {
                Socket clientSocket = serverSocket.accept();
                pool.execute(new ClientHandler(clientSocket, songDatabase));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Let user choose one or several directories using JFileChooser
    private List<File> chooseMusicDirectories() {
        List<File> directories = new ArrayList<>();
        // Creating a simple panel where user clicks a button to select directories
        JButton selectButton = new JButton("Choose Folders");
        JFrame frame = new JFrame("Select Music Folders");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(selectButton, BorderLayout.CENTER);
        frame.setSize(300, 100);
        frame.setLocationRelativeTo(null);

        selectButton.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(true);
            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                directories.addAll(Arrays.asList(chooser.getSelectedFiles()));
                frame.dispose();
            }
        });
        frame.setVisible(true);

        // Wait until the frame is disposed
        while (frame.isDisplayable()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {}
        }
        return directories;
    }

    // Recursively index through all chosen directories for files with music extensions
    private void indexDirectories(List<File> directories) {
        for (File dir : directories) {
            indexFolder(dir);
        }
    }

    private void indexFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null)
            return;

        for (File file : files) {
            if (file.isDirectory()) {
                indexFolder(file);
            } else {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".mp3") || fileName.endsWith(".wav") || fileName.endsWith(".flac")) {
                    // Extract metadata; if extraction fails use default values.
                    String title = file.getName();
                    String album = "Unknown";
                    String genre = "Unknown";

                    try {
                        AudioFile audioFile = AudioFileIO.read(file);
                        Tag tag = audioFile.getTag();
                        if (tag != null) {
                            String t = tag.getFirst(FieldKey.TITLE);
                            String a = tag.getFirst(FieldKey.ALBUM);
                            String g = tag.getFirst(FieldKey.GENRE);
                            if (t != null && !t.isEmpty()) title = t;
                            if (a != null && !a.isEmpty()) album = a;
                            if (g != null && !g.isEmpty()) genre = g;
                        }
                    } catch (Exception e) {
                        // Could not read metadata; leave the defaults.
                    }

                    Song song = new Song(++songIdCounter, title, album, genre, file.getAbsolutePath());
                    songDatabase.put(song.getId(), song);
                }
            }
        }
    }

    private void saveDatabaseToJson() {
        try (Writer writer = new FileWriter("indexed_music.json")) {
            gson.toJson(songDatabase.values(), writer);
            System.out.println("Indexed music database saved in indexed_music.json");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// A simple class representing a music file.
class Song {
    private int id;
    private String title;
    private String album;
    private String genre;
    private String filePath;

    public Song(int id, String title, String album, String genre, String filePath) {
        this.id = id;
        this.title = title;
        this.album = album;
        this.genre = genre;
        this.filePath = filePath;
    }

    // Getters...
    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getAlbum() { return album; }
    public String getGenre() { return genre; }
    public String getFilePath() { return filePath; }
}

// Handles client requests in a separate thread.
class ClientHandler implements Runnable {

    private Socket clientSocket;
    private Map<Integer, Song> songDatabase;

    public ClientHandler(Socket clientSocket, Map<Integer, Song> songDatabase) {
        this.clientSocket = clientSocket;
        this.songDatabase = songDatabase;
    }

    @Override
    public void run() {
        try (BufferedReader in =
                     new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             BufferedOutputStream out =
                     new BufferedOutputStream(clientSocket.getOutputStream());
             PrintWriter writer = new PrintWriter(out, true)) {

            String request;
            while ((request = in.readLine()) != null) {
                // Simple protocol: LIST, STREAM <id>, DOWNLOAD <id>
                if (request.equalsIgnoreCase("LIST")) {
                    // Send the JSON list of songs.
                    String json = new com.google.gson.Gson().toJson(songDatabase.values());
                    writer.println(json);
                } else if (request.startsWith("STREAM")) {
                    String[] tokens = request.split(" ");
                    if (tokens.length >= 2) {
                        int songId = Integer.parseInt(tokens[1]);
                        Song song = songDatabase.get(songId);
                        if (song != null) {
                            // Stream file content.
                            try (FileInputStream fileIn = new FileInputStream(song.getFilePath())) {
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = fileIn.read(buffer)) > 0) {
                                    out.write(buffer, 0, count);
                                }
                                out.flush();
                            }
                        }
                    }
                } else if (request.startsWith("DOWNLOAD")) {
                    String[] tokens = request.split(" ");
                    if (tokens.length >= 2) {
                        int songId = Integer.parseInt(tokens[1]);
                        Song song = songDatabase.get(songId);
                        if (song != null) {
                            // Send file content for download.
                            try (FileInputStream fileIn = new FileInputStream(song.getFilePath())) {
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = fileIn.read(buffer)) > 0) {
                                    out.write(buffer, 0, count);
                                }
                                out.flush();
                            }
                        }
                    }
                }
                break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
EOF

echo "=== Creating MusicClient source file ==="
cat << 'EOF' > MusicClient/src/MusicClient.java
// MusicClient.java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.lang.reflect.Type;
import java.net.Socket;
import java.util.*;
import java.util.List;

public class MusicClient extends JFrame {

    private String serverIp;
    private int serverPort;
    JTable table;
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

        // Add play and download columns as icons/buttons
        TableColumn playCol = new TableColumn();
        playCol.setHeaderValue("Play");
        table.addColumn(playCol);

        TableColumn downloadCol = new TableColumn();
        downloadCol.setHeaderValue("Download");
        table.addColumn(downloadCol);

        table.getColumnModel().getColumn(table.getColumnCount()-2).setCellRenderer(new ButtonRenderer("Play"));
        table.getColumnModel().getColumn(table.getColumnCount()-2).setCellEditor(new ButtonEditor(new JCheckBox(), "Play", this));

        table.getColumnModel().getColumn(table.getColumnCount()-1).setCellRenderer(new ButtonRenderer("Download"));
        table.getColumnModel().getColumn(table.getColumnCount()-1).setCellEditor(new ButtonEditor(new JCheckBox(), "Download", this));

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
        Object[] message = {
            "Server IP:", ipField,
            "Server Port:", portField
        };
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
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            out.println("LIST");
            String json = in.readLine();
            Gson gson = new Gson();
            Type listType = new TypeToken<List<Song>>(){}.getType();
            List<Song> songs = gson.fromJson(json, listType);
            tableModel.setSongs(songs);
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Failed to retrieve song list from server.");
        }
    }

    public void performAction(String action, Song song) {
        if ("Play".equals(action)) {
            new Thread(() -> playSong(song)).start();
        } else if ("Download".equals(action)) {
            new Thread(() -> downloadSong(song)).start();
        }
    }

    private void playSong(Song song) {
        try (Socket socket = new Socket(serverIp, serverPort);
             OutputStream out = socket.getOutputStream();
             BufferedInputStream in = new BufferedInputStream(socket.getInputStream())) {

            PrintWriter writer = new PrintWriter(out, true);
            writer.println("STREAM " + song.getId());

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
        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(this, "Error playing song: " + song.getTitle());
        }
    }

    private void downloadSong(Song song) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setSelectedFile(new File(song.getTitle() + ".mp3"));
        int option = fileChooser.showSaveDialog(this);
        if (option == JFileChooser.APPROVE_OPTION) {
            File saveFile = fileChooser.getSelectedFile();
            try (Socket socket = new Socket(serverIp, serverPort);
                 OutputStream out = socket.getOutputStream();
                 BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
                 FileOutputStream fos = new FileOutputStream(saveFile)) {

                PrintWriter writer = new PrintWriter(out, true);
                writer.println("DOWNLOAD " + song.getId());
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, bytesRead);
                }
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
        if (text.trim().length() == 0) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(RowFilter.regexFilter("(?i)" + text));
        }
    }
}

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
    private String[] columnNames = {"ID", "Title", "Album", "Genre"};
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
    public Component getTableCellRendererComponent(JTable table, Object value,
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
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mousePressed(java.awt.event.MouseEvent e) {
                fireEditingStopped();
            }
        });
        button.addActionListener(e -> {
            SongTableModel model = (SongTableModel) client.table.getModel();
            Song song = model.getSongAt(client.table.convertRowIndexToModel(currentRow));
            client.performAction(action, song);
        });
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object value,
                                                 boolean isSelected, int row, int column) {
        this.currentRow = row;
        return button;
    }

    @Override
    public Object getCellEditorValue() {
        return action;
    }
}
EOF

echo "=== Creating build script ==="
cat << 'EOF' > build.sh
#!/bin/bash
set -e

echo "=== Building MusicServer ==="
cd MusicServer/src
javac -cp "../lib/gson.jar:../lib/jaudiotagger.jar" *.java
echo "MusicServer compiled successfully."
cd ../../

echo "=== Building MusicClient ==="
cd MusicClient/src
javac -cp "../lib/gson.jar" *.java
echo "MusicClient compiled successfully."
cd ../../

echo "=== Build Process Complete ==="
EOF

chmod +x build.sh
chmod +x setup_project.sh

echo "=== Setup Complete ==="
echo "Project structure created."
echo "Don't forget to add the required JAR files (e.g., gson.jar, jaudiotagger.jar) into the lib directories."
echo "You can now run './build.sh' to compile the project."
