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
        System.out.println("[AudioPlayerApp] Launching with media URL: " + mediaUrl);
        new Thread(() -> Application.launch(AudioPlayerApp.class)).start();
    }
    
    @Override
    public void start(Stage primaryStage) {
        try {
            System.out.println("[AudioPlayerApp] Inside start; attempting to create media for: " + mediaUrl);
            Media media = new Media(mediaUrl);
            MediaPlayer player = new MediaPlayer(media);
            player.setOnError(() -> {
                System.err.println("[AudioPlayerApp] MediaPlayer error: " + player.getError());
            });
            player.setOnEndOfMedia(() -> {
                System.out.println("[AudioPlayerApp] Media playback finished.");
                player.dispose();
                primaryStage.close();
            });
            player.play();
            System.out.println("[AudioPlayerApp] MediaPlayer started playback for: " + mediaUrl);
        } catch (MediaException me) {
            System.err.println("[AudioPlayerApp] MediaException: " + me.getMessage());
            me.printStackTrace();
        } catch (Exception e) {
            System.err.println("[AudioPlayerApp] Exception in start method: ");
            e.printStackTrace();
        }
    }
}
