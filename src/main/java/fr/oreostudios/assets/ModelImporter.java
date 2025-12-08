package fr.oreostudios.assets;

import java.io.File;

public interface ModelImporter {

    OreoModel importModel(File file) throws Exception;

    String getDescription();

    String[] getSupportedExtensions();
}
