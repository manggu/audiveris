//------------------------------------------------------------------------------------------------//
//                                                                                                //
//                                  A n n o t a t i o n s S t e p                                 //
//                                                                                                //
//------------------------------------------------------------------------------------------------//
// <editor-fold defaultstate="collapsed" desc="hdr">
//
//  Copyright © Audiveris 2018. All rights reserved.
//
//  This program is free software: you can redistribute it and/or modify it under the terms of the
//  GNU Affero General Public License as published by the Free Software Foundation, either version
//  3 of the License, or (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
//  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
//  See the GNU Affero General Public License for more details.
//
//  You should have received a copy of the GNU Affero General Public License along with this
//  program.  If not, see <http://www.gnu.org/licenses/>.
//------------------------------------------------------------------------------------------------//
// </editor-fold>
package org.audiveris.omr.step;

import ij.process.ByteProcessor;

import org.audiveris.omr.WellKnowns;
import org.audiveris.omr.classifier.Annotation;
import org.audiveris.omr.classifier.AnnotationIndex;
import org.audiveris.omr.classifier.AnnotationJsonParser;
import org.audiveris.omr.classifier.PatchClassifier;
import org.audiveris.omr.classifier.ui.AnnotationBoard;
import org.audiveris.omr.classifier.ui.AnnotationService;
import org.audiveris.omr.classifier.ui.AnnotationView;
import org.audiveris.omr.constant.Constant;
import org.audiveris.omr.constant.ConstantSet;
import org.audiveris.omr.sheet.Picture;
import org.audiveris.omr.sheet.Sheet;
import org.audiveris.omr.sheet.ui.PixelBoard;
import org.audiveris.omr.sheet.ui.SheetTab;
import org.audiveris.omr.ui.BoardsPane;
import org.audiveris.omr.ui.view.ScrollView;
import org.audiveris.omr.util.FileUtil;
import org.audiveris.omr.util.MultipartUtility;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Class {@code AnnotationsStep} implements <b>ANNOTATIONS</b>, which delegates to the
 * full-page classifier the detection and classification of most symbols of the sheet.
 * <p>
 * The full-page classifier is provided a sheet image properly scaled and returns a collection of
 * annotations (one per detected symbol).
 * <p>
 * For the time being, the classifier is accessed as a (local) web service.
 *
 * @author Hervé Bitteur
 */
public class AnnotationsStep
        extends AbstractStep
{
    //~ Static fields/initializers -----------------------------------------------------------------

    private static final Constants constants = new Constants();

    private static final Logger logger = LoggerFactory.getLogger(AnnotationsStep.class);

    //~ Methods ------------------------------------------------------------------------------------
    //----------------------//
    // displayAnnotationTab //
    //----------------------//
    /**
     * Convenient method to (re)display the tab dedicated to annotations data.
     *
     * @param sheet the related sheet
     */
    public static void displayAnnotationTab (Sheet sheet)
    {
        AnnotationService service = (AnnotationService) sheet.getAnnotationIndex().getEntityService();
        AnnotationView view = new AnnotationView(service, sheet);
        sheet.getStub().getAssembly().addViewTab(
                SheetTab.ANNOTATION_TAB,
                new ScrollView(view),
                new BoardsPane(
                        new PixelBoard(sheet),
                        new AnnotationBoard(sheet.getAnnotationIndex().getEntityService(), true)));
    }

    //-----------//
    // displayUI //
    //-----------//
    @Override
    public void displayUI (Step step,
                           Sheet sheet)
    {
        if (constants.displayAnnotations.isSet()) {
            displayAnnotationTab(sheet);
        }
    }

    //------//
    // doit //
    //------//
    /**
     * Submit the (properly scaled) image of the provided sheet to the full-page
     * classifier and record the detected annotations.
     *
     * @param sheet the provided sheet
     */
    @Override
    public void doit (Sheet sheet)
    {
        try {
            // Scale image if different from expected interline
            final int interline = sheet.getScale().getInterline();
            final int expected = constants.expectedInterline.getValue();
            final double ratio = (double) expected / interline;
            final Picture picture = sheet.getPicture();
            final ByteProcessor buf;

            if (expected == PatchClassifier.getPatchInterline()) {
                // Buffer is available as a standard source
                buf = picture.getSource(Picture.SourceKey.LARGE_TARGET);
            } else {
                // Build buffer from scaled standard initial or binary
                ByteProcessor buffer = picture.getSource(Picture.SourceKey.INITIAL);

                if (buffer == null) {
                    buffer = picture.getSource(Picture.SourceKey.BINARY);
                }

                buf = (ratio != 1.0) ? scaledBuffer(buffer, ratio) : buffer;
            }

            BufferedImage img = buf.getBufferedImage();
            String name = sheet.getId();
            File file = WellKnowns.TEMP_FOLDER.resolve(name + ".png").toFile();
            ImageIO.write(img, "png", file);
            logger.info("Saved {}", file);

            //
            // Post image to web service
            // Receive annotations (json file)
            List<Annotation> annotations = null;
            //TODO: Exchange test file with actual post request.
            //            String jsonString;
            //            try (FileInputStream inputStream = new FileInputStream("data/examples/Bach_Fuge_C_DUR.json")) {
            //                jsonString = IOUtils.toString(inputStream);
            //            } catch (Exception e) {
            //                logger.error(e.getMessage());
            //                return;
            //            }
            //            annotations = AnnotationJsonParser.parse(jsonString, ratio);
            annotations = postRequest(file, ratio);

            AnnotationIndex index = sheet.getAnnotationIndex();

            for (Annotation annotation : annotations) {
                index.register(annotation);
            }

            index.setModified(true);
        } catch (Exception ex) {
            logger.warn("Exception in Annotations step", ex);
        }
    }

    //-------------//
    // postRequest //
    //-------------//
    /**
     * For a page classifier accessed as a web service, this method posts the input image
     * and then waits for the detected annotations.
     *
     * @param file  file that contains the input image
     * @param ratio scale ratio already applied to the image
     * @return the (un-scaled) detected annotations
     * @throws Exception if anything goes wrong
     */
    private List<Annotation> postRequest (File file,
                                          double ratio)
            throws Exception
    {
        MultipartUtility mu = new MultipartUtility(
                constants.webServiceUrl.getValue(),
                StandardCharsets.UTF_8);
        logger.info("Posting image {}", file);
        mu.addFilePart("image", file);
        logger.info("Waiting for response...");

        final long start = System.currentTimeMillis();
        List<String> answers = mu.finish();
        final long stop = System.currentTimeMillis();
        logger.info("Duration= {} seconds", (stop - start) / 1000);
        logger.debug("Answers= {}", answers);

        // Save json string into json file (to ease later debugging)
        String radix = FileUtil.getNameSansExtension(file);
        Path jsonPath = file.toPath().resolveSibling(radix + ".json");
        byte[] bytes = answers.get(0).getBytes(StandardCharsets.UTF_8);
        Files.write(jsonPath, bytes);

        // Parse annotations out of json data
        List<Annotation> annotations = AnnotationJsonParser.parse(answers.get(0), ratio);

        return annotations;
    }

    //--------------//
    // scaledBuffer //
    //--------------//
    /**
     * Report a scaled version of the provided buffer, according to the desired ratio.
     *
     * @param binBuffer initial (binary) buffer
     * @param ratio     desired ratio
     * @return the scaled buffer (no longer binary)
     */
    private ByteProcessor scaledBuffer (ByteProcessor binBuffer,
                                        double ratio)
    {
        final int scaledWidth = (int) Math.ceil(binBuffer.getWidth() * ratio);
        final int scaledHeight = (int) Math.ceil(binBuffer.getHeight() * ratio);
        final ByteProcessor scaledBuffer = (ByteProcessor) binBuffer.resize(
                scaledWidth,
                scaledHeight,
                true); // True => use averaging when down-scaling

        return scaledBuffer;
    }

    //~ Inner Classes ------------------------------------------------------------------------------
    //-----------//
    // Constants //
    //-----------//
    private static final class Constants
            extends ConstantSet
    {
        //~ Instance fields ------------------------------------------------------------------------

        private final Constant.Boolean displayAnnotations = new Constant.Boolean(
                true,
                "Should we display the view on annotations?");

        private final Constant.Integer expectedInterline = new Constant.Integer(
                "pixels",
                10,
                "Expected interline for classifier input image");

        private final Constant.String webServiceUrl = new Constant.String(
                "http://127.0.0.1:5000/classify",
                "URL of Detection Web Service");
    }
}