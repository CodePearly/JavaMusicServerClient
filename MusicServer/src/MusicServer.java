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
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicServer {

    // Map songID -> Song
    private Map<Integer, Song> songDatabase = new HashMap<>();
    private int songIdCounter = 0;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final int PORT = 5555; // adjust as needed

    public static void main(String[] args) {
        MusicServer server = new MusicServer();
        server.start();
    }

    public void start() {
        // First, choose local folders for indexing.
        List<File> chosenDirs = chooseMusicDirectories();
        if (!chosenDirs.isEmpty()) {
            indexDirectories(chosenDirs);
        } else {
            JOptionPane.showMessageDialog(null, "No local folders selected.");
        }
        
        // Also check for OneDrive URLs from onedrive_list.txt (if available).
        indexOneDriveFiles();

        // Save the indexed list to JSON
        saveDatabaseToJson();

        // Start the server socket to serve clients.
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

    // Let the user choose directories containing audio files
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
            JFileChooser chooser = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            chooser.setMultiSelectionEnabled(true);
            int returnVal = chooser.showOpenDialog(null);
            if (returnVal == JFileChooser.APPROVE_OPTION) {
                directories.addAll(Arrays.asList(chooser.getSelectedFiles()));
            }
            frame.dispose();
        });
        frame.setVisible(true);
        // Wait until frame closes.
        while (frame.isDisplayable()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {}
        }
        return directories;
    }

    // Recursively index files in each chosen local directory.
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
                // Check for supported audio extensions.
                if (fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                    fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                    fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                    fileName.endsWith(".ogg")) {
                    
                    // Extract metadata using Jaudiotagger, if possible.
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
                        // On failure, keep default values.
                    }
                    Song song = new Song(++songIdCounter, title, album, genre, file.getAbsolutePath());
                    songDatabase.put(song.getId(), song);
                    System.out.println("Indexed file: " + file.getAbsolutePath());
                }
            }
        }
    }

    // Reads a text file "onedrive_list.txt" (if present) containing OneDrive audio file URLs (one per line),
    // downloads them to a folder, and indexes them.
    private void indexOneDriveFiles() {
        File oneDriveList = new File("onedrive_list.txt");
        if (!oneDriveList.exists()) {
            System.out.println("No onedrive_list.txt file found; skipping OneDrive downloads.");
            return;
        }
        // Create a folder to store downloaded files.
        File downloadDir = new File("downloaded_onedrive");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(oneDriveList))) {
            String urlStr;
            while ((urlStr = reader.readLine()) != null) {
                urlStr = urlStr.trim();
                if (urlStr.isEmpty()) continue;
                try {
                    // Determine filename from URL.
                    URL url = new URL(urlStr);
                    String path = url.getPath();
                    String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
                    // Check for supported file types.
                    if (!(fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                          fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                          fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                          fileName.endsWith(".ogg"))) {
                        System.out.println("Skipping unsupported OneDrive file: " + fileName);
                        continue;
                    }
                    File localFile = new File(downloadDir, fileName);
                    // Download the file.
                    downloadFile(urlStr, localFile);
                    // Index the downloaded file.
                    indexFolder(localFile.getParentFile());
                } catch (Exception ex) {
                    System.err.println("Error downloading or indexing OneDrive file: " + urlStr);
                    ex.printStackTrace();
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading onedrive_list.txt");
            e.printStackTrace();
        }
    }

    // Downloads a file from a URL to a local file.
    private void downloadFile(String urlStr, File destination) throws IOException {
        System.out.println("Downloading from OneDrive: " + urlStr);
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        // Optionally set timeouts.
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        System.out.println("Downloaded to: " + destination.getAbsolutePath());
    }

    // Save indexed songs to a JSON file.
    private void saveDatabaseToJson() {
        try (Writer writer = new FileWriter("indexed_music.json")) {
            gson.toJson(songDatabase.values(), writer);
            System.out.println("Indexed music database saved in indexed_music.json");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// A simple data class representing a song.
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

    // Getters...
    public int getId() {
        return id;
    }
    public String getTitle() {
        return title;
    }
    public String getAlbum() {
        return album;
    }
    public String getGenre() {
        return genre;
    }
    public String getFilePath() {
        return filePath;
    }
}

// A separate thread class to handle client requests.
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
                // Protocol: LIST, STREAM <id>, DOWNLOAD <id>
                if (request.equalsIgnoreCase("LIST")) {
                    String json = new Gson().toJson(songDatabase.values());
                    writer.println(json);
                } else if (request.startsWith("STREAM")) {
                    String[] tokens = request.split(" ");
                    if (tokens.length >= 2) {
                        int songId = Integer.parseInt(tokens[1]);
                        Song song = songDatabase.get(songId);
                        if (song != null) {
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
                break; // End after one request.
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
