package peergos.shared.user.fs;

//import javafx.application.Platform;
//import javafx.embed.swing.JFXPanel;
//import javafx.embed.swing.SwingFXUtils;
//import javafx.scene.image.WritableImage;
//import javafx.scene.media.Media;
//import javafx.scene.media.MediaPlayer;
//import javafx.scene.media.MediaView;
//import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class VideoThumbnail {

    private static void sleep(int duration) {
        try {
            Thread.sleep(duration);
        }catch(InterruptedException ie) {
        }
    }

//    private static double getDuration(Media video) {
//        boolean finished = false;
//        int counter = 0;
//        while(!finished) {
//            Duration duration = video.getDuration();
//            if(duration.isUnknown()) {
//                sleep(1000);
//                counter++;
//                if(counter > 10) {
//                    return 0;
//                }
//            } else {
//                return duration.toSeconds();
//            }
//        }
//        return 0;
//    }
    private static boolean isLikelyValidImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int threshold = width * height / 10 * 8;
        int blackCount = 0;
        int whiteCount = 0;
        for( int i = 0; i < width; i++) {
            for (int j = 0; j < height; j++) {
                int val = image.getRGB(i, j);
                int r = (val >> 16) & 0xFF;
                int g = (val >> 8) & 0xFF;
                int b = val & 0xFF;
                int total = r + g + b;
                if(total < 10) {
                    if(++blackCount > threshold) {
                        return false;
                    }
                } else if(total > 760) {
                    if(++whiteCount > threshold) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public static Optional<Thumbnail> create(String filename, int height, int width) {
//        new JFXPanel(); // initialises toolkit
//        Media video = new Media("file://" + filename);
//        MediaPlayer mediaPlayer = new MediaPlayer(video);
//        WritableImage wim = new WritableImage(width, height);
//        MediaView mv = new MediaView();
//        mv.setFitWidth(width);
//        mv.setFitHeight(height);
//        mv.setMediaPlayer(mediaPlayer);
//        mv.setPreserveRatio(false);
//
//        double duration = getDuration(video);
//        double increment = duration/10; //seconds
//        double currentIncrement = 0;
//        while(currentIncrement < duration) {
//            currentIncrement = currentIncrement + increment;
//            mediaPlayer.seek(Duration.seconds(currentIncrement));
//            CompletableFuture<BufferedImage> res = new CompletableFuture<>();
//            Platform.runLater(() -> {
//                mv.snapshot(null, wim);
//                BufferedImage image = SwingFXUtils.fromFXImage(wim, null);
//                res.complete(image);
//            });
//            ByteArrayOutputStream bout = new ByteArrayOutputStream();
//            try {
//                BufferedImage image = res.get();
//                if(isLikelyValidImage(image)){
//                    ImageIO.write(image, "png", bout);
//                    bout.flush();
//                    bout.close();
//                    return bout.toByteArray();
//                }
//            }catch(IOException | InterruptedException | ExecutionException ex){
//                ex.printStackTrace();
//            }
//        }
        return Optional.empty();
    }
}

