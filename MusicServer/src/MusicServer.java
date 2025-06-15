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
