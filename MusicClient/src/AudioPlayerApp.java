// AudioPlayerApp.java
import javafx.application.Application;
import javafx.scene.media.Media;
import javafx.scene.media.MediaException;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

public class AudioPlayerApp extends Application {
    private static String mediaUrl;
    
    public static void launchApp(String url) {
        mediaUrl = url;
        // Launch the JavaFX application on a new thread.
        new Thread(() -> Application.launch(AudioPlayerApp.class)).start();
    }
    
    @Override
    public void start(Stage primaryStage) {
        try {
            Media media = new Media(mediaUrl);
            MediaPlayer player = new MediaPlayer(media);
            player.setOnError(() -> {
                System.err.println("MediaPlayer Error: " + player.getError());
            });
            player.setOnEndOfMedia(() -> {
                player.dispose();
                primaryStage.close();
            });
            player.play();
            System.out.println("JavaFX MediaPlayer started for: " + mediaUrl);
        } catch (MediaException me) {
            System.err.println("MediaException in AudioPlayerApp: " + me.getMessage());
            me.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
