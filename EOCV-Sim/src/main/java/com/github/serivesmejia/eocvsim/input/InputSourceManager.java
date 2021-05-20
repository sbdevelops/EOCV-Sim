/*
 * Copyright (c) 2021 Sebastian Erives
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package com.github.serivesmejia.eocvsim.input;

import com.github.serivesmejia.eocvsim.EOCVSim;
import com.github.serivesmejia.eocvsim.gui.Visualizer;
import com.github.serivesmejia.eocvsim.input.source.ImageSource;
import com.github.serivesmejia.eocvsim.pipeline.PipelineManager;
import com.github.serivesmejia.eocvsim.util.Log;
import com.github.serivesmejia.eocvsim.util.SysUtil;
import org.opencv.core.Mat;
import org.opencv.core.Size;

import javax.swing.SwingUtilities;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class InputSourceManager {

    private final EOCVSim eocvSim;

    public volatile Mat lastMatFromSource = null;
    public volatile InputSource currentInputSource = null;

    public volatile HashMap<String, InputSource> sources = new HashMap<>();

    public InputSourceLoader inputSourceLoader = new InputSourceLoader();

    public InputSourceManager(EOCVSim eocvSim) {
        this.eocvSim = eocvSim;
    }
    public void init() {

        Log.info("InputSourceManager", "Initializing...");

        if(lastMatFromSource == null)
            lastMatFromSource = new Mat();

        Size size = new Size(320, 240);
        createDefaultImgInputSource("/images/ug_4.jpg", "ug_eocvsim_4.jpg", "Ultimate Goal 4 Ring", size);
        createDefaultImgInputSource("/images/ug_1.jpg", "ug_eocvsim_1.jpg", "Ultimate Goal 1 Ring", size);
        createDefaultImgInputSource("/images/ug_0.jpg", "ug_eocvsim_0.jpg", "Ultimate Goal 0 Ring", size);

        inputSourceLoader.loadInputSourcesFromFile();

        for (Map.Entry<String, InputSource> entry : inputSourceLoader.loadedInputSources.entrySet()) {
            addInputSource(entry.getKey(), entry.getValue());
        }

        Log.blank();
    }

    private void createDefaultImgInputSource(String resourcePath, String fileName, String sourceName, Size imgSize) {
        try {

            InputStream is = InputSource.class.getResourceAsStream(resourcePath);
            File f = SysUtil.copyFileIsTemp(is, fileName, true).file;

            ImageSource src = new ImageSource(f.getAbsolutePath(), imgSize);
            src.isDefault = true;
            src.createdOn = sources.size();

            addInputSource(sourceName, src);

        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void update(boolean isPaused) {
        if(currentInputSource == null) return;
        currentInputSource.setPaused(isPaused);

        try {
            Mat m = currentInputSource.update();
            if(m != null && !m.empty()) m.copyTo(lastMatFromSource);
        } catch(Exception ex) {
            Log.error("InputSourceManager", "Error while processing current source", ex);
        }
    }

    public void addInputSource(String name, InputSource inputSource) {
        if (inputSource == null) {
            return;
        }

        if (sources.containsKey(name)) return;

        inputSource.name = name;

        sources.put(name, inputSource);

        if(inputSource.createdOn == -1)
            inputSource.createdOn = System.currentTimeMillis();

        if(!inputSource.isDefault) {
            inputSourceLoader.saveInputSource(name, inputSource);
            inputSourceLoader.saveInputSourcesToFile();
        }

        if(eocvSim.visualizer.sourceSelectorPanel != null) {
            eocvSim.visualizer.sourceSelectorPanel.updateSourcesList();

            SwingUtilities.invokeLater(() -> {
                int index = eocvSim.visualizer.sourceSelectorPanel.getIndexOf(name);

                eocvSim.visualizer.sourceSelectorPanel
                    .getSourceSelector().setSelectedIndex(index);

                eocvSim.onMainUpdate.doOnce(() -> {
                    eocvSim.pipelineManager.requestSetPaused(false);
                    pauseIfImageTwoFrames();
                });
            });
        }

        Log.info("InputSourceManager", "Adding InputSource " + inputSource.toString() + " (" + inputSource.getClass().getSimpleName() + ")");
    }

    public void deleteInputSource(String sourceName) {
        InputSource src = sources.get(sourceName);

        if (src == null) return;
        if (src.isDefault) return;

        sources.remove(sourceName);

        inputSourceLoader.deleteInputSource(sourceName);
        inputSourceLoader.saveInputSourcesToFile();
    }

    public boolean setInputSource(String sourceName) {
        InputSource src = sources.get(sourceName);

        if (src != null) {
            src.reset();
            src.eocvSim = eocvSim;
        }

        //check if source type is a camera, and if so, create a please wait dialog
        Visualizer.AsyncPleaseWaitDialog apwd = showApwdIfNeeded(sourceName);

        if (src != null) {
            if (!src.init()) {
                if (apwd != null) {
                    apwd.destroyDialog();
                }

                eocvSim.visualizer.asyncPleaseWaitDialog("Error while loading requested source", "Falling back to previous source",
                        "Close", new Dimension(300, 150), true, true);

                Log.error("InputSourceManager", "Error while loading requested source (" + sourceName + ") reported by itself (init method returned false)");

                return false;
            }
        }

        //if there's a please wait dialog for a camera source, destroy it.
        if (apwd != null) {
            apwd.destroyDialog();
        }

        if (currentInputSource != null) {
            currentInputSource.reset();
        }

        currentInputSource = src;

        //if pause on images option is turned on by user
        if (eocvSim.configManager.getConfig().pauseOnImages)
            pauseIfImage();

        Log.info("InputSourceManager", "Set InputSource to " + currentInputSource.toString() + " (" + src.getClass().getSimpleName() + ")");

        return true;

    }

    public boolean isNameOnUse(String name) {
        return sources.containsKey(name);
    }

    public void pauseIfImage() {
        //if the new input source is an image, we will pause the next frame
        //to execute one shot analysis on images and save resources.
        if (SourceType.fromClass(currentInputSource.getClass()) == SourceType.IMAGE) {
            eocvSim.onMainUpdate.doOnce(() ->
                    eocvSim.pipelineManager.setPaused(
                            true,
                            PipelineManager.PauseReason.IMAGE_ONE_ANALYSIS
                    )
            );
        }
    }

    public void pauseIfImageTwoFrames() {
        //if the new input source is an image, we will pause the next frame
        //to execute one shot analysis on images and save resources.
        eocvSim.onMainUpdate.doOnce(this::pauseIfImage);
    }

    public void requestSetInputSource(String name) {
        eocvSim.onMainUpdate.doOnce(() -> setInputSource(name));
    }

    public Visualizer.AsyncPleaseWaitDialog showApwdIfNeeded(String sourceName) {
        Visualizer.AsyncPleaseWaitDialog apwd = null;

        if (getSourceType(sourceName) == SourceType.CAMERA || getSourceType(sourceName) == SourceType.VIDEO) {
            apwd = eocvSim.visualizer.asyncPleaseWaitDialog(
                    "Opening source...", null, "Exit",
                    new Dimension(300, 150), true
            );

            apwd.onCancel(() -> eocvSim.destroy());
        }

        return apwd;
    }

    public SourceType getSourceType(String sourceName) {
        InputSource source = sources.get(sourceName);
        return SourceType.fromClass(source.getClass());
    }

    public InputSource[] getSortedInputSources() {
        ArrayList<InputSource> sources = new ArrayList<>(this.sources.values());
        Collections.sort(sources);

        return sources.toArray(new InputSource[0]);
    }

}
