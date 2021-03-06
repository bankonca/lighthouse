package lighthouse.subwindows;

import com.google.common.io.ByteStreams;
import com.google.protobuf.ByteString;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Pane;
import javafx.stage.FileChooser;
import lighthouse.Main;
import lighthouse.model.ProjectModel;
import lighthouse.protocol.LHUtils;
import lighthouse.utils.DownloadProgress;
import lighthouse.utils.ValidationLink;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.controlsfx.control.PopOver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;

import static lighthouse.protocol.LHUtils.unchecked;
import static lighthouse.utils.GuiUtils.*;

/**
 * A window that lets you create a new project by typing in its details.
 */
public class AddProjectWindow {
    private static final Logger log = LoggerFactory.getLogger(AddProjectWindow.class);
    private static final String COVERPHOTO_SITE = "coverphotofinder.com";

    @FXML BorderPane rootPane;
    @FXML Label coverPhotoSiteLink;
    @FXML Label coverImageLabel;
    @FXML ImageView coverImageView;
    @FXML TextField addressEdit;
    @FXML TextField goalAmountEdit;
    @FXML TextField titleEdit;
    @FXML TextField minPledgeEdit;
    @FXML TextArea descriptionEdit;
    @FXML Button nextButton;
    @FXML Pane createPane;

    private PopOver maxPledgesPopOver;

    public Main.OverlayUI<InnerWindow> overlayUI;

    private ProjectModel model;

    // Called by FXMLLoader.
    public void initialize() {
        // TODO: This fixed value won't work properly with internationalization.
        rootPane.setPrefWidth(618);
        rootPane.prefHeightProperty().bind(Main.instance.scene.heightProperty().multiply(0.8));

        // We grab a fresh address here because a project is identified by the hash of its output address and its
        // title. If we didn't use a fresh address here, two projects created in sequence that for whatever reason
        // had the same title would end up having the same ID.
        final Address address = Main.wallet.freshReceiveAddress();
        addressEdit.setText(address.toString());
        this.model = new ProjectModel(Main.wallet);
        this.model.title.bind(titleEdit.textProperty());
        this.model.memo.bind(descriptionEdit.textProperty());
        this.model.address.bind(addressEdit.textProperty());

        coverPhotoSiteLink.setText(COVERPHOTO_SITE);

        ValidationLink goalValid = new ValidationLink(goalAmountEdit, str -> !LHUtils.didThrow(() -> valueOrThrow(str)));
        goalAmountEdit.textProperty().addListener((obj, prev, cur) -> {
            if (goalValid.isValid.get())
                this.model.goalAmount.set(valueOrThrow(cur).value);
        });
        // Figure out the smallest pledge that is allowed based on the goal divided by number of inputs we can have.
        model.minPledgeAmountProperty().addListener(o -> {
            minPledgeEdit.setPromptText(model.getMinPledgeAmount().toPlainString());
        });
        minPledgeEdit.setPromptText("");
        ValidationLink minPledgeValue = new ValidationLink(minPledgeEdit, str -> {
            if (str.isEmpty())
                return true;  // default is used
            Coin coin = valueOrNull(str);
            if (coin == null) return false;
            Coin amount = model.getMinPledgeAmount();
            // If min pledge == suggested amount it's ok, or if it's between min amount and goal.
            return coin.equals(amount) || (coin.isGreaterThan(amount) && coin.isLessThan(Coin.valueOf(this.model.goalAmount.get())));
        });

        ValidationLink.autoDisableButton(nextButton,
                goalValid,
                new ValidationLink(titleEdit, str -> !str.isEmpty()),
                minPledgeValue);


        roundCorners(coverImageView, 10);
        setupDefaultCoverImage();

        Label maxPledgesWarning = new Label(String.format("You can collect a maximum of %d pledges, due to limits in the Bitcoin protocol.", ProjectModel.MAX_NUM_INPUTS));
        maxPledgesWarning.setStyle("-fx-font-size: 12; -fx-padding: 10");
        maxPledgesPopOver = new PopOver(maxPledgesWarning);
        maxPledgesPopOver.setDetachable(false);
        maxPledgesPopOver.setArrowLocation(PopOver.ArrowLocation.BOTTOM_CENTER);
        minPledgeEdit.focusedProperty().addListener(o -> {
            if (minPledgeEdit.isFocused())
                maxPledgesPopOver.show(minPledgeEdit);
            else
                maxPledgesPopOver.hide();
        });
    }

    private void setupDefaultCoverImage() {
        // The default image is nice, so a lot of people (including possibly me) will be lazy and not change it. To
        // keep things interesting we randomly recolour the image here.
        try {
            ColorAdjust colorAdjust = new ColorAdjust();
            double randomHueAdjust = Math.random() * 2 - 1.0;
            colorAdjust.setHue(randomHueAdjust);
            // Draw into a canvas and then apply the effect, because if we snapshotted the image view, we'd end up
            // with the rounded corners which we don't want.
            Image image = new Image(getResource("default-cover-image.png").openStream());
            Canvas canvas = new Canvas(image.getWidth(), image.getHeight());
            GraphicsContext gc = canvas.getGraphicsContext2D();
            gc.drawImage(image, 0, 0);
            gc.applyEffect(colorAdjust);
            WritableImage colouredImage = new WritableImage((int) image.getWidth(), (int) image.getHeight());
            canvas.snapshot(new SnapshotParameters(), colouredImage);
            coverImageView.setImage(colouredImage);
            // Convert to a PNG and store in the project model.
            ImageIO.setUseCache(false);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(SwingFXUtils.fromFXImage(colouredImage, null), "png", baos);
            model.image.set(ByteString.copyFrom(baos.toByteArray()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @FXML
    public void nextClicked(ActionEvent event) {
        AddProjectTypeWindow.open(model);
    }

    @FXML
    public void imageSelectorClicked(MouseEvent event) {
        log.info("Image selector clicked");
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select an image file");
        chooser.getExtensionFilters().setAll(new FileChooser.ExtensionFilter("Images (JPG/PNG/GIF)", "*.jpg", "*.jpeg", "*.png", "*.gif"));
        platformFiddleChooser(chooser);
        File result = chooser.showOpenDialog(Main.instance.mainStage);
        if (result == null) return;
        setImageTo(unchecked(() -> result.toURI().toURL()));
    }

    private void setImageTo(URL result) {
        try {
            log.info("Setting image to {}", result);
            if (result.getProtocol().startsWith("http")) {   // Also catch https
                final String oldLabel = coverImageLabel.getText();
                final DownloadProgress task = new DownloadProgress(result);
                task.setOnSucceeded(ev -> {
                    log.info("Image downloaded succeeded");
                    ByteString bytes = task.getValue();
                    coverImageLabel.setGraphic(null);
                    coverImageLabel.setText(oldLabel);
                    setImageTo(bytes);
                });
                task.setOnFailed(ev -> {
                    informationalAlert("Image load failed", "Could not download the image from the remote server: %s", task.getException().getLocalizedMessage());
                    coverImageLabel.setGraphic(null);
                    coverImageLabel.setText(oldLabel);
                });
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.progressProperty().bind(task.progressProperty());
                indicator.setPrefHeight(50);
                indicator.setPrefWidth(50);
                coverImageLabel.setGraphic(indicator);
                coverImageLabel.setText("");
                Thread download = new Thread(task);
                download.setName("Download of " + result);
                download.setDaemon(true);
                download.start();
            } else {
                // Load in a blocking fashion.
                byte[] bits = ByteStreams.toByteArray(result.openStream());
                if (bits.length > 1024 * 1024 * 5) {
                    informationalAlert("Image too large", "Please make sure your image is smaller than 5mb, any larger is excessive.");
                    return;
                }
                final ByteString bytes = ByteString.copyFrom(bits);
                setImageTo(bytes);
            }
        } catch (Exception e) {
            log.error("Failed to load image", e);
            informationalAlert("Failed to load image", "%s", e.getLocalizedMessage());
        }
    }

    private void setImageTo(ByteString bytes) {
        coverImageView.setEffect(null);
        final Image image = new Image(bytes.newInput());
        if (image.getException() == null) {
            model.image.set(bytes);
            coverImageView.setImage(image);
        } else {
            log.error("Could not load image", image.getException());
        }
    }

    public void imageSelectorDragOver(DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        if (dragboard.getFiles().size() == 1) {
            final String name = dragboard.getFiles().get(0).toString().toLowerCase();
            if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                return;
            }
        }
        if (dragboard.getUrl() != null) {
            // We accept all URLs and filter out the non-image ones later.
            event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
        }
    }

    @FXML
    public void imageSelectorDropped(DragEvent event) {
        log.info("Drop: {}", event);
        if (event.getDragboard().getFiles().size() == 1) {
            setImageTo(unchecked(() -> event.getDragboard().getFiles().get(0).toURI().toURL()));
        } else if (event.getDragboard().getUrl() != null) {
            setImageTo(unchecked(() -> new URL(event.getDragboard().getUrl())));
        }
    }

    @FXML
    public void openCoverPhotoFinder(MouseEvent event) {
        log.info("cover photo URL clicked");
        Main.instance.getHostServices().showDocument(String.format("http://%s/", COVERPHOTO_SITE));
        event.consume();
    }

    @FXML
    public void cancelClicked(ActionEvent event) {
        overlayUI.done();
    }
}
