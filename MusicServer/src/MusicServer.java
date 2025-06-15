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
    private final int PORT = 5555;  // Change as needed

    public static void main(String[] args) {
        MusicServer server = new MusicServer();
        server.start();
    }

    public void start() {
        // Let the user choose local folders to index.
        List<File> chosenDirs = chooseMusicDirectories();
        if (!chosenDirs.isEmpty()) {
            indexDirectories(chosenDirs);
        } else {
            JOptionPane.showMessageDialog(null, "No local folders selected.");
        }
        
        // Also try to index OneDrive URLs if available.
        indexOneDriveFiles();

        // Save the index to a JSON file.
        saveDatabaseToJson();

        // Start the server socket.
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

    // Allow selection of directories via a Swing file chooser.
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
        while (frame.isDisplayable()) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ignore) {}
        }
        return directories;
    }

    // Index files recursively in each chosen directory.
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
                if (fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                    fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                    fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                    fileName.endsWith(".ogg")) {
                    
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
                        // Use defaults if metadata extraction fails.
                    }
                    Song song = new Song(++songIdCounter, title, album, genre, file.getAbsolutePath());
                    songDatabase.put(song.getId(), song);
                    System.out.println("Indexed file: " + file.getAbsolutePath());
                }
            }
        }
    }

    // Try to index remote OneDrive files listed in "onedrive_list.txt"
    private void indexOneDriveFiles() {
        File oneDriveList = new File("onedrive_list.txt");
        if (!oneDriveList.exists()) {
            System.out.println("No onedrive_list.txt file found; skipping OneDrive downloads.");
            return;
        }
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
                    URL url = new URL(urlStr);
                    String path = url.getPath();
                    String fileName = path.substring(path.lastIndexOf('/') + 1).toLowerCase();
                    if (!(fileName.endsWith(".mp3")   || fileName.endsWith(".wav")  ||
                          fileName.endsWith(".flac")  || fileName.endsWith(".aiff") ||
                          fileName.endsWith(".aac")   || fileName.endsWith(".wma")  ||
                          fileName.endsWith(".ogg"))) {
                        System.out.println("Skipping unsupported OneDrive file: " + fileName);
                        continue;
                    }
                    File localFile = new File(downloadDir, fileName);
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

    // Download a file from a URL.
    private void downloadFile(String urlStr, File destination) throws IOException {
        System.out.println("Downloading from OneDrive: " + urlStr);
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
        System.out.println("Downloaded to: " + destination.getAbsolutePath());
    }

    // Write the current song database to indexed_music.json.
    private void saveDatabaseToJson() {
        try (Writer writer = new FileWriter("indexed_music.json")) {
            gson.toJson(songDatabase.values(), writer);
            System.out.println("Indexed music database saved in indexed_music.json");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}

// A simple data class representing a music file.
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

// Handles commands from a connected client.
class ClientHandler implements Runnable {

    private final Socket clientSocket;
    private final Map<Integer, Song> songDatabase;

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
                break; // End after one command.
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
