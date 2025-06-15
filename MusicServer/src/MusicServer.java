// MusicServer.java
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.FieldKey;

import javax.swing.*;
import javax.swing.filechooser.FileSystemView;
import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicServer {

    // Map of song ID to Song objects.
    private final Map<Integer, Song> songDatabase = new HashMap<>();
    private int songIdCounter = 0;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int PORT = 5555;  // Change if desired

    public static void main(String[] args) {
        System.out.println("MusicServer starting up...");
        MusicServer server = new MusicServer();
        server.start();
    }

    public void start() {
        System.out.println("[Server] Launching directory chooser...");
        List<File> chosenDirs = chooseMusicDirectories();
        if (!chosenDirs.isEmpty()) {
            System.out.println("[Server] Indexing selected folders...");
            indexDirectories(chosenDirs);
        } else {
            System.out.println("[Server] No folders selected.");
            JOptionPane.showMessageDialog(null, "No local folders selected.");
        }
        
        System.out.println("[Server] Checking for OneDrive URLs...");
        indexOneDriveFiles();

        System.out.println("[Server] Saving indexed database to JSON...");
        saveDatabaseToJson();

        System.out.println("[Server] Starting server socket on port " + PORT);
        ExecutorService pool = Executors.newCachedThreadPool();
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                System.out.println("[Server] Waiting for client connection...");
                Socket clientSocket = serverSocket.accept();
                System.out.println("[Server] Client connected from: " + clientSocket.getInetAddress());
                pool.execute(new ClientHandler(clientSocket, songDatabase));
            }
        } catch (IOException e) {
            System.err.println("[Server] Error in server socket:");
            e.printStackTrace();
        }
    }

    // Let the user choose directories via a Swing file chooser.
    private List<File> chooseMusicDirectories() {
        List<File> directories = new ArrayList<>();
        JButton selectButton = new JButton("Choose Folders to Index");
        JFrame frame = new JFrame("Select Music Folders");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(selectButton, BorderLayout.CENTER);
        frame.setSize(300, 100);
        frame.setLocationRelativeTo(null);
        selectButton.addActionListener((ActionEvent e) -> {
            System.out.println("[Server] Opening file chooser dialog...");
            JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(true);
            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                directories.addAll(Arrays.asList(chooser.getSelectedFiles()));
                for (File dir : chooser.getSelectedFiles()) {
                    System.out.println("[Server] Selected folder: " + dir.getAbsolutePath());
                }
            }
            frame.dispose();
        });
        frame.setVisible(true);
        while (frame.isDisplayable()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {}
        }
        return directories;
    }

    // Recursively index files in each chosen directory.
    private void indexDirectories(List<File> directories) {
        for (File dir : directories) {
            System.out.println("[Server] Indexing folder: " + dir.getAbsolutePath());
            indexFolder(dir);
        }
    }

    private void indexFolder(File folder) {
        File[] files = folder.listFiles();
        if (files == null) {
            System.out.println("[Server] Folder " + folder.getAbsolutePath() + " is empty or inaccessible.");
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                indexFolder(file);
            } else {
                String fileName = file.getName().toLowerCase();
                if (fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                    fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                    fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                    fileName.endsWith(".ogg")) {
                    
                    String title = file.getName();
                    String album = "Unknown";
                    String genre = "Unknown";
                    try {
                        System.out.println("[Server] Reading metadata for: " + file.getAbsolutePath());
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
                        System.err.println("[Server] Metadata extraction failed for: " + file.getAbsolutePath());
                    }
                    Song song = new Song(++songIdCounter, title, album, genre, file.getAbsolutePath());
                    songDatabase.put(song.getId(), song);
                    System.out.println("[Server] Indexed file (" + song.getId() + "): " + file.getAbsolutePath());
                }
            }
        }
    }

    // Read OneDrive URLs from "onedrive_list.txt" and download them.
    private void indexOneDriveFiles() {
        File oneDriveList = new File("onedrive_list.txt");
        if (!oneDriveList.exists()) {
            System.out.println("[Server] onedrive_list.txt not found; skipping OneDrive integration.");
            return;
        }
        File downloadDir = new File("downloaded_onedrive");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        System.out.println("[Server] Found onedrive_list.txt. Processing remote URLs...");
        try (BufferedReader reader = new BufferedReader(new FileReader(oneDriveList))) {
            String urlStr;
            while ((urlStr = reader.readLine()) != null) {
                urlStr = urlStr.trim();
                if (urlStr.isEmpty()) continue;
                try {
                    URL url = new URL(urlStr);
                    String path = url.getPath();
                    String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
                    if (!(fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                          fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                          fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                          fileName.endsWith(".ogg"))) {
                        System.out.println("[Server] Skipping unsupported remote file: " + fileName);
                        continue;
                    }
                    File localFile = new File(downloadDir, fileName);
                    System.out.println("[Server] Downloading remote file: " + urlStr);
                    downloadFile(urlStr, localFile);
                    // Index the downloaded file.
                    indexFolder(localFile.getParentFile());
                } catch (Exception ex) {
                    System.err.println("[Server] Error processing remote URL: " + urlStr);
                    ex.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Error reading onedrive_list.txt");
            e.printStackTrace();
        }
    }

    // Downloads a file from a URL to a local destination.
    private void downloadFile(String urlStr, File destination) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) > 0) {
                out.write(buffer, 0, count);
            }
        }
        System.out.println("[Server] Downloaded remote file to: " + destination.getAbsolutePath());
    }

    // Save the current database as JSON.
    private void saveDatabaseToJson() {
        try (Writer writer = new FileWriter("indexed_music.json")) {
            gson.toJson(songDatabase.values(), writer);
            System.out.println("[Server] Indexed music database saved to indexed_music.json");
        } catch (IOException e) {
            System.err.println("[Server] Error saving indexed_music.json:");
            e.printStackTrace();
        }
    }
}

// Simple Song data class.
class Song implements Serializable {
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
    public int getId() { return id; }
    public String getTitle() { return title; }
    public String getAlbum() { return album; }
    public String getGenre() { return genre; }
    public String getFilePath() { return filePath; }
}

// Handles each client connection.
class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final Map<Integer, Song> songDatabase;

    public ClientHandler(Socket clientSocket, Map<Integer, Song> songDatabase) {
        this.clientSocket = clientSocket;
        this.songDatabase = songDatabase;
    }

    @Override
    public void run() {
        System.out.println("[Server] Handling client at " + clientSocket.getInetAddress());
        try (BufferedReader in =
                     new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             BufferedOutputStream out =
                     new BufferedOutputStream(clientSocket.getOutputStream());
             PrintWriter writer = new PrintWriter(out, true)) {

            String request;
            while ((request = in.readLine()) != null) {
                System.out.println("[Server] Received request: " + request);
                if (request.equalsIgnoreCase("LIST")) {
                    String json = new Gson().toJson(songDatabase.values());
                    System.out.println("[Server] Sending song list...");
                    writer.println(json);
                } else if (request.startsWith("STREAM")) {
                    String[] tokens = request.split(" ");
                    if (tokens.length >= 2) {
                        int songId = Integer.parseInt(tokens[1]);
                        Song song = songDatabase.get(songId);
                        if (song != null) {
                            System.out.println("[Server] Streaming song id " + songId + ": " + song.getTitle());
                            try (FileInputStream fileIn = new FileInputStream(song.getFilePath())) {
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = fileIn.read(buffer)) > 0) {
                                    out.write(buffer, 0, count);
                                }
                                out.flush();
                                System.out.println("[Server] Finished streaming song: " + song.getTitle());
                            }
                        }
                    }
                } else if (request.startsWith("DOWNLOAD")) {
                    String[] tokens = request.split(" ");
                    if (tokens.length >= 2) {
                        int songId = Integer.parseInt(tokens[1]);
                        Song song = songDatabase.get(songId);
                        if (song != null) {
                            System.out.println("[Server] Download requested for song: " + song.getTitle());
                            try (FileInputStream fileIn = new FileInputStream(song.getFilePath())) {
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = fileIn.read(buffer)) > 0) {
                                    out.write(buffer, 0, count);
                                }
                                out.flush();
                                System.out.println("[Server] Finished sending file for song: " + song.getTitle());
                            }
                        }
                    }
                }
                break; // End after processing one command.
            }
        } catch (IOException e) {
            System.err.println("[Server] Error handling client:");
            e.printStackTrace();
        }
    }
}
