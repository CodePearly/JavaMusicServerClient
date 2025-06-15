import javafx.application.Application;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.stage.Stage;

public class AudioPlayerApp extends Application {
    private static String mediaUrl;
    
    public static void launchApp(String url) {
        mediaUrl = url;
        // Launch JavaFX application on a new thread.
        new Thread(() -> Application.launch(AudioPlayerApp.class)).start();
    }
    
    @Override
    public void start(Stage primaryStage) {
        Media media = new Media(mediaUrl);
        MediaPlayer player = new MediaPlayer(media);
        player.setOnEndOfMedia(() -> {
            player.dispose();
            primaryStage.close();
        });
        player.play();
    }
}
